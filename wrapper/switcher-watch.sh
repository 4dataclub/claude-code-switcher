#!/usr/bin/env bash
# switcher-watch.sh — ALLES in einem Fenster:
#   [UI]      State-Events aus der UI  (Provider-Switch, Supermodell AN/AUS, Pool, Failover)
#   [ROUTER]  Routing der Haupt-Session durch ccr  (welches Backend/Modell wirklich antwortet)
#   [DELEG]   Supermodel-Delegation durch llm-cascade  (wohin die Rollen-Subtasks gehen)
#
# Ersetzt router-watch.sh + delegator-watch.sh als eine Anlaufstelle.
#
# Verwendung:
#   ./switcher-watch.sh
# Beendet: Ctrl+C

set -uo pipefail

SWITCHER_URL="${CLAUDE_SWITCHER_URL:-http://localhost:2000}"
ROUTER_CONTAINER="${ROUTER_CONTAINER:-claude-switcher-router-1}"
CASCADE_URL="${OP_CASCADE_URL:-http://localhost:8091}"
POLL_SECONDS="${DELEGATOR_WATCH_POLL:-1}"

if [[ -t 1 ]]; then
  C_RESET=$'\e[0m'; C_DIM=$'\e[2m'; C_BOLD=$'\e[1m'
  C_GREEN=$'\e[32m'; C_BLUE=$'\e[34m'; C_YELLOW=$'\e[33m'
  C_MAGENTA=$'\e[35m'; C_CYAN=$'\e[36m'; C_RED=$'\e[31m'
else
  C_RESET=""; C_DIM=""; C_BOLD=""
  C_GREEN=""; C_BLUE=""; C_YELLOW=""; C_MAGENTA=""; C_CYAN=""; C_RED=""
fi

cleanup() {
  [[ -n "${SSE_PID:-}" ]]   && kill "$SSE_PID"   2>/dev/null
  [[ -n "${LOG_PID:-}" ]]   && kill "$LOG_PID"   2>/dev/null
  [[ -n "${DELEG_PID:-}" ]] && kill "$DELEG_PID" 2>/dev/null
  exit 0
}
trap cleanup INT TERM

echo "${C_BOLD}╔════════════════════════════════════════════════════════════════╗${C_RESET}"
echo "${C_BOLD}║  Switcher Watch — UI-Events + Router-Routing + Delegator        ║${C_RESET}"
echo "${C_BOLD}╚════════════════════════════════════════════════════════════════╝${C_RESET}"
echo "  ${C_MAGENTA}[UI]${C_RESET}     Provider-Switch · Supermodell AN/AUS · Pool · Failover"
echo "  ${C_BLUE}[ROUTER]${C_RESET} welches Backend die Haupt-Session bedient (nur via Router)"
echo "  ${C_CYAN}[DELEG]${C_RESET}  wohin der Delegator die Rollen-Subtasks schickt"
echo ""

# Aktueller Status
echo -n "${C_DIM}Aktueller Switcher-Status: ${C_RESET}"
curl -sS --max-time 3 "$SWITCHER_URL/api/status" 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    p = d.get('provider', '?'); m = d.get('model', '?')
    pool = d.get('pool', '?'); sm = d.get('supermodel', False)
    ar = d.get('activeRoute') if isinstance(d.get('activeRoute'), dict) else None
    smtxt = '\033[1;32mAN\033[0m' if sm else '\033[1;31mAUS\033[0m'
    base = f'\033[1m{p}\033[0m / \033[36m{m}\033[0m'
    if ar: base = f'\033[1m{ar.get(\"provider\")}\033[0m / \033[36m{ar.get(\"model\")}\033[0m'
    print(f'{base}   Pool: \033[1;36m{pool}\033[0m   Supermodell: {smtxt}')
except Exception:
    print('(unbekannt — Switcher erreichbar?)')
" 2>/dev/null || echo "(Switcher unter $SWITCHER_URL nicht erreichbar)"
echo ""

# ─── [UI] SSE-Stream für State-Events ──────────────────────────────────────
(
  curl -sS --no-buffer "$SWITCHER_URL/api/events" 2>/dev/null | \
  while IFS= read -r line; do
    case "$line" in
      "event:switch"|"event: switch")
        read -r data_line
        json="${data_line#data:}"; json="${json# }"
        echo "$json" | python3 -u -c "
import sys, json
try:
    d = json.load(sys.stdin)
    p = d.get('provider'); m = d.get('model')
    ar = d.get('activeRoute') or {}
    real_p = ar.get('provider') or p; real_m = ar.get('model') or m
    tag = '\033[1;35m[UI]\033[0m'
    if p == 'anthropic':
        out = f'\n{tag} \033[1;33m▼▼▼ SWITCH ▼▼▼\033[0m → \033[1;33mANTHROPIC direkt\033[0m / \033[1;36m{m}\033[0m (kein Router)\n'
    else:
        out = f'\n{tag} \033[1;33m▼▼▼ SWITCH ▼▼▼\033[0m → echtes Backend: \033[1;35m{real_p}\033[0m / \033[1;36m{real_m}\033[0m\n'
    sys.stdout.write(out); sys.stdout.flush()
except: pass
" 2>/dev/null
        ;;
      "event:auto-switched"|"event: auto-switched")
        read -r data_line
        json="${data_line#data:}"; json="${json# }"
        echo "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    t = d.get('to', {})
    print(f'\n\033[1;35m[UI]\033[0m \033[1;33m▼▼▼ AUTO-SWITCH (Failover) ▼▼▼ → \033[1;35m{t.get(\"provider\")}\033[0m \033[1;33m/ \033[1;36m{t.get(\"model\")}\033[0m\n')
except: pass
" 2>/dev/null
        ;;
      "event:mode"|"event: mode")
        read -r data_line
        json="${data_line#data:}"; json="${json# }"
        echo "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    on = bool(d.get('supermodel')); pool = d.get('pool', '?')
    pending = bool(d.get('localOrchestratorPending'))
    state = '\033[1;32mAN\033[0m' if on else '\033[1;31mAUS\033[0m'
    extra = '  \033[2m(local-Orchestrator wartet auf Neustart)\033[0m' if pending else ''
    print(f'\n\033[1;35m[UI]\033[0m \033[1;35m◆◆◆ SUPERMODELL {state}\033[0m  Pool: \033[1;36m{pool}\033[0m{extra}\n')
except: pass
" 2>/dev/null
        ;;
      "event:warn"|"event: warn")
        read -r data_line
        echo "${C_MAGENTA}[UI]${C_RESET} ${C_YELLOW}⚠ 90%-Warnung${C_RESET}"
        ;;
      "event:chain-promoted"|"event: chain-promoted")
        echo "${C_MAGENTA}[UI]${C_RESET} ${C_GREEN}↺ Zurück auf Anthropic${C_RESET}"
        ;;
    esac
  done
) &
SSE_PID=$!

# ─── [ROUTER] Live-Log vom ccr-Container ───────────────────────────────────
(
  docker exec "$ROUTER_CONTAINER" sh -c '
    while true; do
      LATEST=$(ls -t /root/.claude-code-router/logs/*.log 2>/dev/null | head -1)
      [ -n "$LATEST" ] && tail -F "$LATEST" 2>/dev/null
      sleep 2
    done
  ' 2>/dev/null | python3 -u -c "
import sys, json, re
from datetime import datetime
def color(s, c): return f'\033[{c}m{s}\033[0m'
pending = {}
for line in sys.stdin:
    try: d = json.loads(line)
    except: continue
    msg = d.get('msg', ''); rid = d.get('reqId', '')
    if msg == 'incoming request':
        req = d.get('req', {})
        if '/health' not in req.get('url', '') and req.get('method') == 'POST':
            pending[rid] = {'started': datetime.now().strftime('%H:%M:%S')}
    elif d.get('type') == 'request body' and rid in pending:
        body = d.get('data', {})
        pending[rid]['claude_model'] = body.get('model', '?')
        msgs = body.get('messages', [])
        if msgs:
            last = msgs[-1].get('content', '')
            if isinstance(last, list): last = last[0].get('text', '?') if last else '?'
            pending[rid]['user_text'] = (str(last)[:60] + '…') if len(str(last)) > 60 else str(last)
    elif msg == 'final request' and rid in pending:
        pending[rid]['upstream_url'] = d.get('requestUrl', '?')
    elif msg == 'request completed' and rid in pending:
        p = pending.pop(rid)
        rt_ms = d.get('responseTime', 0)
        status = d.get('res', {}).get('statusCode', '?')
        url = p.get('upstream_url', '')
        if 'generativelanguage.googleapis.com' in url: real, cc = 'GOOGLE GEMINI', '32'
        elif 'openrouter.ai' in url: real, cc = 'OPENROUTER', '35'
        elif 'anthropic.com' in url: real, cc = 'ANTHROPIC (DIRECT)', '33'
        else: real, cc = url[:40], '37'
        m = re.search(r'/models/([^:?/]+)', url)
        real_model = m.group(1) if m else '?'
        ok = '✓' if status == 200 else '✗'; ok_c = '32' if status == 200 else '31'
        print(f\"{color('[ROUTER]','34')} {color(p['started'], '2')} {color(ok, ok_c)} \"
              f\"{color(real, cc)} {color(real_model, '36;1')} {color(f'({int(rt_ms)}ms)', '2')}\", flush=True)
        if 'user_text' in p:
            print(f\"          {color('→', '2')} {color(p['user_text'], '37')}\", flush=True)
" 2>/dev/null
) &
LOG_PID=$!

# ─── [DELEG] Delegator-Calls aus llm-cascade pollen ────────────────────────
(
  LAST_ID=$(curl -fsS --max-time 5 "${CASCADE_URL}/api/stats/calls" 2>/dev/null | python3 -c '
import sys, json
try: print(max((c.get("id", 0) for c in json.load(sys.stdin)), default=0))
except Exception: print(0)
' 2>/dev/null)
  LAST_ID="${LAST_ID:-0}"
  while true; do
    NEW=$(curl -fsS --max-time 5 "${CASCADE_URL}/api/stats/calls" 2>/dev/null | LAST_ID="$LAST_ID" python3 -c '
import sys, os, json
last = int(os.environ.get("LAST_ID", "0"))
try: d = json.load(sys.stdin)
except Exception: sys.exit(0)
new = sorted((c for c in d if c.get("id", 0) > last), key=lambda c: c.get("id", 0))
if not new: sys.exit(0)
print(new[-1]["id"])
G="\033[32m"; R="\033[31m"; C="\033[36m"; D="\033[2m"; X="\033[0m"
for c in new:
    mark = f"{G}✓{X}" if c.get("success") else f"{R}✗{X}"
    t = (c.get("calledAt") or "")[11:19]
    prov = c.get("provider") or "?"; model = c.get("model") or "?"
    svc = c.get("service") or ""; chars = c.get("outputChars")
    svc_s = f"{D}[{svc}]{X}" if svc and svc != "unknown" else ""
    extra = f"{D}{chars} chars{X}" if chars else ""
    line = f"\033[36m[DELEG]\033[0m {D}{t}{X} {mark} {C}{prov}:{model}{X} {svc_s} {extra}".rstrip()
    snip = c.get("promptSnippet")  # nur befuellt wenn Datenschutz-Schalter logPromptSnippet=AN
    if snip:
        s = snip if len(snip) <= 70 else snip[:70] + "…"
        line += f" {D}→{X} \"{s}\""
    print(line)
' 2>/dev/null)
    if [ -n "$NEW" ]; then
      LAST_ID=$(printf '%s\n' "$NEW" | head -1)
      printf '%s\n' "$NEW" | tail -n +2
    fi
    sleep "$POLL_SECONDS"
  done
) &
DELEG_PID=$!

echo "${C_DIM}── Watching… (Ctrl+C beendet)${C_RESET}"
echo ""
wait