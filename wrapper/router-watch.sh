#!/usr/bin/env bash
# router-watch.sh — Live-Anzeige aller Anfragen die durch den Router gehen.
# Zeigt: was claude geschickt hat, wohin der Router weitergeleitet hat,
# welches Modell tatsächlich geantwortet hat. Plus: macht prominente
# Marker bei jedem Provider-Switch (sichtbar via SSE-Events vom Switcher).
#
# Verwendung:
#   ./router-watch.sh
# Oder permanent in eigenem Terminal-Tab:
#   ~/.local/bin/router-watch
#
# Beendet: Ctrl+C

set -uo pipefail

SWITCHER_URL="${CLAUDE_SWITCHER_URL:-http://localhost:3000}"
ROUTER_CONTAINER="${ROUTER_CONTAINER:-claude-switcher-router-1}"

# ANSI-Farben (auto-disable bei not-tty)
if [[ -t 1 ]]; then
  C_RESET=$'\e[0m'; C_DIM=$'\e[2m'; C_BOLD=$'\e[1m'
  C_GREEN=$'\e[32m'; C_BLUE=$'\e[34m'; C_YELLOW=$'\e[33m'
  C_MAGENTA=$'\e[35m'; C_CYAN=$'\e[36m'; C_RED=$'\e[31m'
else
  C_RESET=""; C_DIM=""; C_BOLD=""
  C_GREEN=""; C_BLUE=""; C_YELLOW=""; C_MAGENTA=""; C_CYAN=""; C_RED=""
fi

cleanup() {
  [[ -n "${SSE_PID:-}" ]] && kill "$SSE_PID" 2>/dev/null
  [[ -n "${LOG_PID:-}" ]] && kill "$LOG_PID" 2>/dev/null
  exit 0
}
trap cleanup INT TERM

echo "${C_BOLD}╔════════════════════════════════════════════════════════════════╗${C_RESET}"
echo "${C_BOLD}║  Router Watch — live anzeigen welches Modell wirklich antwortet ║${C_RESET}"
echo "${C_BOLD}╚════════════════════════════════════════════════════════════════╝${C_RESET}"
echo ""

# Aktueller Status
echo -n "${C_DIM}Aktueller Switcher-Status: ${C_RESET}"
curl -sS --max-time 3 "$SWITCHER_URL/api/status" 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    p = d.get('provider', '?')
    m = d.get('model', '?')
    ar = d.get('_switcher', {}).get('activeRoute') if isinstance(d.get('_switcher'), dict) else None
    if ar: print(f'\033[1m{ar.get(\"provider\")}\033[0m / \033[36m{ar.get(\"model\")}\033[0m  (settings.json model: {m})')
    else:  print(f'\033[1m{p}\033[0m / \033[36m{m}\033[0m')
except Exception as e: print(f'(unbekannt — Switcher erreichbar?)')
" 2>/dev/null || echo "(Switcher unter $SWITCHER_URL nicht erreichbar)"
echo ""

# ─── SSE-Stream für Switch-Events ──────────────────────────────────────────
(
  curl -sS --no-buffer "$SWITCHER_URL/api/events" 2>/dev/null | \
  while IFS= read -r line; do
    case "$line" in
      "event: switch")
        read -r data_line
        json="${data_line#data: }"
        echo "$json" | python3 -u -c "
import sys, json
try:
    d = json.load(sys.stdin)
    p = d.get('provider')
    m = d.get('model')
    ar = d.get('activeRoute') or {}
    real_p = ar.get('provider') or p
    real_m = ar.get('model') or m
    if p == 'anthropic':
        out = f'\n\033[1;33m▼▼▼ SWITCH ▼▼▼\033[0m  → \033[1;33mANTHROPIC direkt\033[0m / \033[1;36m{m}\033[0m  (kein Router)\n'
    else:
        out = f'\n\033[1;33m▼▼▼ SWITCH ▼▼▼\033[0m  → echtes Backend: \033[1;35m{real_p}\033[0m / \033[1;36m{real_m}\033[0m\n'
    sys.stdout.write(out); sys.stdout.flush()
except: pass
" 2>/dev/null
        ;;
      "event: auto-switched")
        read -r data_line
        json="${data_line#data: }"
        echo "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    t = d.get('to', {})
    print(f'\n\033[1;33m▼▼▼ AUTO-SWITCH (Failover) ▼▼▼  → \033[1;35m{t.get(\"provider\")}\033[0m \033[1;33m/ \033[1;36m{t.get(\"model\")}\033[0m\n')
except: pass
" 2>/dev/null
        ;;
      "event: warn")
        read -r data_line
        echo "${C_YELLOW}⚠ 90%-Warnung${C_RESET}"
        ;;
      "event: chain-promoted")
        echo "${C_GREEN}↺ Zurück auf Anthropic${C_RESET}"
        ;;
    esac
  done
) &
SSE_PID=$!

# ─── Live-Log vom ccr-Container ────────────────────────────────────────────
(
  docker exec "$ROUTER_CONTAINER" sh -c '
    while true; do
      LATEST=$(ls -t /root/.claude-code-router/logs/*.log 2>/dev/null | head -1)
      [ -n "$LATEST" ] && tail -F "$LATEST" 2>/dev/null
      sleep 2
    done
  ' 2>/dev/null | python3 -u -c "
import sys, json
from datetime import datetime

def color(s, c):
    return f'\033[{c}m{s}\033[0m'

# Wir bauen Anfrage→Antwort-Paare zusammen via reqId
pending = {}

for line in sys.stdin:
    try:
        d = json.loads(line)
    except: continue
    msg = d.get('msg', '')
    rid = d.get('reqId', '')

    # Ankommender Request (was claude geschickt hat)
    if msg == 'incoming request':
        req = d.get('req', {})
        if '/health' not in req.get('url', '') and req.get('method') == 'POST':
            pending[rid] = {'started': datetime.now().strftime('%H:%M:%S')}

    # Request-Body (Anthropic-Format)
    elif d.get('type') == 'request body' and rid in pending:
        body = d.get('data', {})
        pending[rid]['claude_model'] = body.get('model', '?')
        msgs = body.get('messages', [])
        if msgs:
            last = msgs[-1].get('content', '')
            if isinstance(last, list): last = last[0].get('text', '?') if last else '?'
            pending[rid]['user_text'] = (last[:60] + '…') if len(str(last)) > 60 else str(last)

    # Final-Request (wohin wirklich gerouted)
    elif msg == 'final request' and rid in pending:
        pending[rid]['upstream_url'] = d.get('requestUrl', '?')

    # Antwort kommt zurück
    elif msg == 'request completed' and rid in pending:
        p = pending.pop(rid)
        rt_ms = d.get('responseTime', 0)
        status = d.get('res', {}).get('statusCode', '?')

        # Provider erkennen aus URL
        url = p.get('upstream_url', '')
        if 'generativelanguage.googleapis.com' in url:
            real = 'GOOGLE GEMINI'
            color_c = '32'  # green
        elif 'openrouter.ai' in url:
            real = 'OPENROUTER'
            color_c = '35'  # magenta
        elif 'anthropic.com' in url:
            real = 'ANTHROPIC (DIRECT)'
            color_c = '33'  # yellow
        else:
            real = url[:40]
            color_c = '37'

        # Modell-Name aus URL (gemini: /models/X:generateContent oder :streamGenerateContent)
        import re
        m = re.search(r'/models/([^:?/]+)', url)
        real_model = m.group(1) if m else '?'

        ok = '✓' if status == 200 else '✗'
        ok_c = '32' if status == 200 else '31'

        print(f\"{color(p['started'], '2')} {color(ok, ok_c)} \"
              f\"{color(real, color_c)} {color(real_model, '36;1')} \"
              f\"{color(f'({int(rt_ms)}ms)', '2')}\", flush=True)
        if 'user_text' in p:
            print(f\"     {color('→', '2')} {color(p['user_text'], '37')}\", flush=True)
" 2>/dev/null
) &
LOG_PID=$!

echo "${C_DIM}── Watching… (Ctrl+C beendet)${C_RESET}"
echo ""

wait
