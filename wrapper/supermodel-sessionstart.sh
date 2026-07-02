#!/usr/bin/env bash
# supermodel-sessionstart.sh — SessionStart-Hook für den Claude-Switcher Supermodell-Modus.
#
# Prüft beim Session-Start live, ob der Supermodell-Modus an ist (GET /api/supermodel).
# Wenn ja → injiziert die Delegations-Anweisung in den Session-Kontext (additionalContext),
# damit der Orchestrator (Opus) Fleißarbeit an den EINEN @supermodel-Agenten delegiert.
# Wenn aus / Switcher nicht erreichbar → No-Op (keine Ausgabe), beeinflusst die Session nicht.
set -uo pipefail
SWITCHER_URL="${CLAUDE_SWITCHER_URL:-http://localhost:2000}"
resp=$(curl -sS --max-time 2 "$SWITCHER_URL/api/supermodel" 2>/dev/null) || exit 0
printf '%s' "$resp" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if not d.get("enabled"):
    sys.exit(0)
pool = d.get("pool", "cloud")
ctx = (
    f"Supermodell-Modus ist AN (Pool: {pool}). Delegiere selbst-enthaltene Teilaufgaben an den "
    f"EINEN @supermodel-Agenten (kind = implement | review | research | dispatch); er routet zur "
    f"llm-cascade (Kategorie {{kind}}-{pool}) bzw. zur Gemini-MCP (research). Du bleibst Orchestrator "
    f"+ finale Synthese; Planung/Architektur behaeltst du selbst. Lokaler Pool = fail-closed. "
    f"KRITISCH: Wenn @supermodel einen DELEGATION FEHLER meldet, implementiere NIEMALS selbst — "
    f"auch nicht bei kleinen Aufgaben. Melde den Fehler dem User und stoppe. "
    f"Der Orchestrator orchestriert, er implementiert nicht."
)
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
' 2>/dev/null || exit 0
exit 0
