#!/usr/bin/env bash
# UserPromptSubmit-Hook: blendet Switcher-Events (90%-Warnung, Auto-Switch)
# als zusätzlichen Kontext in claude's Chat ein, sodass der User direkt
# Bescheid weiß ohne extra fragen zu müssen.
#
# Output-Spec (Claude Code Hooks): JSON auf stdout mit
#   { "hookSpecificOutput": { "hookEventName": "UserPromptSubmit",
#                              "additionalContext": "..." } }
# Bei leerem Banner: kein Output → kein Context-Eintrag.

SWITCHER_URL="${CLAUDE_SWITCHER_URL:-http://localhost:3000}"
BANNER=$(curl -sS --max-time 1 "$SWITCHER_URL/api/banner" 2>/dev/null)

if [[ -n "$BANNER" ]]; then
  # Escape für JSON: " → \"  und newlines → \n
  ESCAPED=$(printf '%s' "$BANNER" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read())[1:-1])' 2>/dev/null)
  printf '{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"%s"}}\n' "$ESCAPED"
fi
exit 0
