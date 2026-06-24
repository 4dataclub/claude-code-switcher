#!/usr/bin/env bash
# Prüft die Compose-Form für detect-or-provision.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

# 1. Default-Profil startet KEIN ollama (Service hinter local-llm).
if docker compose config --services 2>/dev/null | grep -qx ollama; then
  no "ollama darf im Default-Profil nicht erscheinen"
else
  ok "ollama nur unter Profil local-llm"
fi
# 2. Mit Profil erscheint ollama.
if docker compose --profile local-llm config --services 2>/dev/null | grep -qx ollama; then
  ok "ollama erscheint mit --profile local-llm"
else
  no "ollama fehlt trotz --profile local-llm"
fi
# 3. llm-cascade hat host.docker.internal extra_host.
# docker compose config normalisiert Doppelpunkt→Gleichzeichen; beide Formen prüfen.
if docker compose config 2>/dev/null | grep -qE 'host\.docker\.internal[:=]host-gateway'; then
  ok "llm-cascade extra_hosts host.docker.internal gesetzt"
else
  no "extra_hosts host.docker.internal fehlt"
fi
# 4. Kein gemma3:4b-Pull mehr im ollama-Entrypoint.
if docker compose --profile local-llm config 2>/dev/null | grep -q 'gemma3:4b'; then
  no "alter gemma3:4b-Pull noch im Entrypoint"
else
  ok "kein gemma3:4b-Pull mehr"
fi
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
