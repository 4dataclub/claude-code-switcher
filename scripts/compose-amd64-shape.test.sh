#!/usr/bin/env bash
# Shape-Check des amd64-Build-Overrides (mirror von compose-shape.test.sh).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

# Build-Kontext muss existieren, sonst lehnt `docker compose config` u.U. ab.
mkdir -p llm-cascade
CFG="$(docker compose -f docker-compose.yml -f docker-compose.amd64.yml config 2>/dev/null)"

# 1. Override rendert ohne Fehler.
if [ -n "$CFG" ]; then ok "amd64-Override rendert"; else no "compose config leer/fehlerhaft"; fi
# 2. llm-cascade baut aus ./llm-cascade (Build-Kontext gesetzt).
if printf '%s' "$CFG" | grep -qE 'context:.*/llm-cascade'; then ok "llm-cascade build.context gesetzt"; else no "build.context fehlt"; fi
# 3. Override fasst NUR llm-cascade an (keinen anderen Service).
#    Geprueft an der Override-Datei selbst: jeder 2-Space-Service-Key ausser llm-cascade waere ein Fehler.
OTHER=$(grep -E '^  [a-z][a-z0-9_-]*:' docker-compose.amd64.yml | grep -v 'llm-cascade:' || true)
if [ -z "$OTHER" ]; then ok "Override betrifft nur llm-cascade"; else no "Override fasst weitere Services an: $OTHER"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
