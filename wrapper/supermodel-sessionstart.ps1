# supermodel-sessionstart.ps1 — SessionStart-Hook (Windows) für den Claude-Switcher
# Supermodell-Modus. Pendant zu wrapper/supermodel-sessionstart.sh.
#
# Prüft live GET /api/supermodel; wenn an → injiziert die Delegations-Anweisung
# (additionalContext). Sonst No-Op (keine Ausgabe).
$ErrorActionPreference = 'SilentlyContinue'
$url = if ($env:CLAUDE_SWITCHER_URL) { $env:CLAUDE_SWITCHER_URL } else { 'http://localhost:2000' }
try { $resp = Invoke-RestMethod -Uri "$url/api/supermodel" -TimeoutSec 2 } catch { exit 0 }
if (-not $resp.enabled) { exit 0 }
$pool = if ($resp.pool) { $resp.pool } else { 'cloud' }
$ctx = "Supermodell-Modus ist AN (Pool: $pool). Delegiere selbst-enthaltene Teilaufgaben an den EINEN @supermodel-Agenten (kind = implement | review | research | dispatch); er routet zur llm-cascade (Kategorie {kind}-$pool) bzw. zur Gemini-MCP (research). Du bleibst Orchestrator + finale Synthese; Planung/Architektur behaeltst du selbst. Lokaler Pool = fail-closed."
$out = @{ hookSpecificOutput = @{ hookEventName = 'SessionStart'; additionalContext = $ctx } }
$out | ConvertTo-Json -Compress
exit 0
