#!/usr/bin/env bash
# watch-orchestrator.sh — Live-Watcher für die Orchestrator-Session-Konsistenz.
#
# Läuft AUSSERHALB von Claude Code (eigenes Terminal) und überlebt damit den
# Session-Neustart, den ein Pool-Wechsel auf `local` auslöst. Pollt im Intervall
# und druckt bei JEDEM Zustandswechsel eine Verdikt-Zeile — so siehst du den
# Übergang cloud→local in Echtzeit und der Watcher meldet sofort, falls Opus
# heimlich weiterläuft (fail-closed-Leak).
#
# READ-ONLY: nur GET-Endpoints + lokale Reads + `ollama ps`. Schaltet NICHTS um.
#
# Aufruf (in einem separaten Terminal, VOR dem UI-Umschalten starten):
#   bash scripts/watch-orchestrator.sh
#
# Env-Overrides:
#   SWITCHER_URL      (default http://localhost:2000)
#   OLLAMA_CONTAINER  (default claude-switcher-ollama-1)
#   WATCH_INTERVAL    (default 2  — Sekunden)
#   WATCH_HEARTBEAT   (default 30 — Sekunden ohne Änderung bis zur Status-Wiederholung; 0 = nie)
set -uo pipefail

SWITCHER_URL="${SWITCHER_URL:-http://localhost:2000}"
OLLAMA_CONTAINER="${OLLAMA_CONTAINER:-claude-switcher-ollama-1}"
INTERVAL="${WATCH_INTERVAL:-2}"
HEARTBEAT="${WATCH_HEARTBEAT:-30}"
SETTINGS="$HOME/.claude/settings.json"

ts() { date +%H:%M:%S; }

echo "Orchestrator-Watcher läuft — $SWITCHER_URL — Strg-C zum Beenden."
echo "Tipp: erst diesen Watcher starten, DANN in der UI Supermodell+Pool umschalten."
echo "------------------------------------------------------------------------------"

last_sig=""
last_print_epoch=0

while true; do
  WHO=$(curl -sS --max-time 5 "$SWITCHER_URL/api/whoami" 2>/dev/null)
  SM=$(curl -sS --max-time 5 "$SWITCHER_URL/api/supermodel" 2>/dev/null)
  MODELS=$(curl -sS --max-time 5 "$SWITCHER_URL/api/ai-models" 2>/dev/null)

  if [ -z "$SM" ]; then
    sig="UNREACHABLE"
    if [ "$sig" != "$last_sig" ]; then
      echo "[$(ts)] ⚠ Switcher nicht erreichbar ($SWITCHER_URL) — Session evtl. mitten im Neustart, warte..."
      last_sig="$sig"; last_print_epoch=$(date +%s)
    fi
    sleep "$INTERVAL"; continue
  fi

  BASE_URL=$(python3 -c "import json,sys
try:
    print(json.load(open('$SETTINGS')).get('env',{}).get('ANTHROPIC_BASE_URL',''))
except Exception:
    print('')" 2>/dev/null)

  OLLAMA_PS=$(docker exec "$OLLAMA_CONTAINER" ollama ps 2>/dev/null | awk 'NR>1{print $1}' | paste -sd, - 2>/dev/null)
  [ -z "$OLLAMA_PS" ] && OLLAMA_PS="(leer)"

  # Alles Weitere in einem python3-Aufruf auswerten (robustes JSON-Handling).
  LINE=$(WHO="$WHO" SM="$SM" MODELS="$MODELS" BASE_URL="$BASE_URL" OLLAMA_PS="$OLLAMA_PS" python3 <<'PY'
import json, os, re

who = os.environ.get("WHO","").strip()
base_url = os.environ.get("BASE_URL","").strip()
ollama_ps = os.environ.get("OLLAMA_PS","").strip()

try:
    sm = json.loads(os.environ.get("SM","{}"))
except Exception:
    sm = {}
try:
    models = json.loads(os.environ.get("MODELS","[]"))
except Exception:
    models = []

pool = sm.get("pool","?")
enabled = bool(sm.get("enabled", False))
pending = bool(sm.get("localOrchestratorPending", False))

def expected_top(pool):
    cat = "orchestrator-" + pool
    cs = [m for m in models
          if m.get("category") == cat and m.get("enabled") and not m.get("autoDisabled")]
    cs.sort(key=lambda m: m.get("orderIdx") if m.get("orderIdx") is not None else 1e9)
    return cs[0].get("modelId") if cs else None

def norm(s):
    return re.sub(r'[^a-z0-9]', '', (s or '').lower())

base_tag = ":3456" if base_url.rstrip("/").endswith(":3456") else (base_url or "(leer=Anthropic-direkt)")
who_l = who.lower()

# Signatur für Change-Detection (nur bei Änderung drucken).
expect = expected_top(pool) if enabled else None
sig = "|".join([pool, str(enabled), str(pending), who, str(expect), base_url, ollama_ps])

if not enabled:
    verdict = "○ Supermodell AUS — Konsistenz-Invariante greift nur bei Supermodell AN."
    line = f"pool={pool} supermodell=AUS | session={who} | {verdict}"
    print(sig + "\x1f" + line); raise SystemExit

# Supermodell AN ab hier.
if pool == "local":
    leaked = ("anthropic" in who_l) or ("opus" in who_l) or ("google" in who_l) or ("openrouter" in who_l)
    if expect is None or pending:
        if leaked:
            verdict = "❌ LEAK: local pending, aber whoami zeigt Cloud/Opus — fail-closed verletzt!"
        else:
            verdict = "⏸ local PENDING: kein aktives lokales Orchestrator-Modell (fail-closed, kein Cloud-Ausweich)."
        line = f"pool=local supermodell=AN | erwartet=NONE pending={pending} | session={who} | BASE_URL={base_tag} | {verdict}"
    else:
        ollama_ok = "ollama" in who_l
        baseurl_ok = base_url.rstrip("/").endswith(":3456")
        resident = norm(expect) in norm(ollama_ps) if ollama_ps != "(leer)" else False
        if leaked:
            verdict = "❌ LEAK: pool=local, aber whoami zeigt Anthropic/Opus/Google — Opus läuft heimlich weiter!"
        elif not ollama_ok:
            verdict = f"❌ INKONSISTENT: erwartet ccr→Ollama ({expect}), aber whoami sagt nicht 'Ollama': {who}"
        elif not baseurl_ok:
            verdict = f"❌ INKONSISTENT: Session sollte über Router (:3456) laufen, BASE_URL={base_tag}"
        else:
            extra = "" if resident else "  (Hinweis: Modell noch nicht im 'ollama ps' — lädt evtl. beim ersten Prompt)"
            verdict = f"✅ KONSISTENT: Session läuft lokal über ccr→Ollama auf {expect} — kein Opus.{extra}"
        line = f"pool=local supermodell=AN | erwartet(orchestrator-local)={expect} | session={who} | BASE_URL={base_tag} | ollama_ps={ollama_ps} | {verdict}"
    print(sig + "\x1f" + line); raise SystemExit

# cloud / free.
if expect is None:
    verdict = f"⚠ kein aktives orchestrator-{pool}-Modell — prüfe Cascade-Konfiguration."
else:
    match = norm(expect) in norm(who) or any(norm(t) and norm(t) in norm(who) for t in re.split(r'[:/]', expect))
    if match:
        verdict = f"✅ KONSISTENT: Session == orchestrator-{pool}-Top ({expect})."
    else:
        verdict = f"⚠ PRÜFEN: erwartet orchestrator-{pool}-Top={expect}, aber whoami={who} (ggf. Friendly-Name-Abweichung)."
line = f"pool={pool} supermodell=AN | erwartet(orchestrator-{pool})={expect} | session={who} | BASE_URL={base_tag} | {verdict}"
print(sig + "\x1f" + line)
PY
)

  sig="${LINE%%$'\x1f'*}"
  msg="${LINE#*$'\x1f'}"
  now=$(date +%s)

  if [ "$sig" != "$last_sig" ]; then
    echo "[$(ts)] $msg"
    last_sig="$sig"; last_print_epoch="$now"
  elif [ "$HEARTBEAT" -gt 0 ] && [ $((now - last_print_epoch)) -ge "$HEARTBEAT" ]; then
    echo "[$(ts)] (unverändert) $msg"
    last_print_epoch="$now"
  fi

  sleep "$INTERVAL"
done
