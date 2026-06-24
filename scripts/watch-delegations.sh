#!/usr/bin/env bash
# watch-delegations.sh — Live-Feed der Supermodell-Delegationen.
#
# Schwester-Skript zu watch-orchestrator.sh:
#   - watch-orchestrator.sh zeigt EBENE 1: ist die laufende Session der richtige
#     Orchestrator (cloud/free/local), greift fail-closed, klassisches Failover.
#   - DIESES Skript zeigt EBENE 2: welche Cascade `{bereich}-{pool}` (implement/
#     review/research/dispatch) jede einzelne Teilaufgabe real bekommen hat und
#     welches Modell geantwortet hat — der UNABHÄNGIGE Beleg, dass die anderen
#     Bereiche tatsächlich genommen werden (nicht nur das, was Opus behauptet).
#
# Quelle ist die Audit-Zeile, die der `supermodel`-Agent nach JEDEM Cascade-Call
# anhängt (nur Routing-Metadaten, NIE Aufgaben-Inhalt):
#   <ISO-Zeit>\t<kategorie>\t<modell>\t<ok|fail>\t<latencyMs>
#
# READ-ONLY: nur `tail -F` auf die Logdatei. Schaltet NICHTS um.
#
# Aufruf (eigenes Terminal, parallel zu watch-orchestrator.sh):
#   bash scripts/watch-delegations.sh
#
# Env-Override:
#   SWITCHER_DELEGATION_LOG  (default ~/.claude/supermodel-delegations.log)
set -uo pipefail

LOG="${SWITCHER_DELEGATION_LOG:-$HOME/.claude/supermodel-delegations.log}"

# Symbol je Bereich (kind = Teil vor dem '-').
symbol() {
  case "$1" in
    implement) echo "🛠 " ;;
    review)    echo "🔍" ;;
    dispatch)  echo "📨" ;;
    research)  echo "📚" ;;
    orchestrator) echo "🧠" ;;
    *)         echo "•" ;;
  esac
}

echo "Delegations-Watcher läuft — $LOG"
echo "Zeigt je Teilaufgabe: Bereich-Pool → Modell (ok/fail, Latenz). Strg-C zum Beenden."
echo "fail bei *-local = fail-closed (Modell fehlt/Ollama down) — KEIN Cloud-Ausweich."
echo "------------------------------------------------------------------------------"

# Datei muss existieren, damit tail -F sofort folgt; leer anlegen wenn nötig.
[ -f "$LOG" ] || : > "$LOG"

# -n0: nur NEUE Zeilen ab jetzt; -F: folgt auch über Rotation/Neuerstellung.
tail -n0 -F "$LOG" 2>/dev/null | while IFS=$'\t' read -r tstamp cat model ok lat; do
  [ -z "${cat:-}" ] && continue
  kind="${cat%%-*}"
  pool="${cat#*-}"
  sym="$(symbol "$kind")"
  # Zeit auf HH:MM:SS kürzen (ISO -> nach 'T', vor '+').
  hms="${tstamp#*T}"; hms="${hms%%+*}"; hms="${hms%%.*}"
  if [ "$ok" = "ok" ]; then
    verdict="✅ ok"
  else
    if [ "$pool" = "local" ]; then
      verdict="❌ fail-closed (kein Cloud-Ausweich)"
    else
      verdict="❌ fail (Opus übernimmt selbst)"
    fi
  fi
  printf '[%s] %s %-18s → %-28s %s  %sms\n' \
    "${hms:-??:??:??}" "$sym" "$cat" "${model:-?}" "$verdict" "${lat:-?}"
done
