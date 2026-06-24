#!/usr/bin/env bash
# Strukturprüfung von setup.ps1 (pwsh hier nicht verfügbar → Grep-basiert).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

bash scripts/build-setup.sh >/dev/null 2>&1 || { echo "build-setup.sh fehlgeschlagen"; exit 1; }

# 1. Detect: probt das Host-Ollama auf /api/tags.
if grep -q '11434/api/tags' setup.ps1; then ok "ps1 probt Host-Ollama /api/tags"; else no "ps1 Detect fehlt"; fi
# 2. Adopt: setzt Default-Server auf host.docker.internal.
if grep -q 'host.docker.internal:11434/v1' setup.ps1; then ok "ps1 Adopt-Base-URL gesetzt"; else no "ps1 Adopt-URL fehlt"; fi
# 3. Provision: startet Profil local-llm.
if grep -q 'profile local-llm' setup.ps1; then ok "ps1 Provision via --profile local-llm"; else no "ps1 Profil-Aufruf fehlt"; fi
# 4. Provider-Server-PUT vorhanden.
if grep -q '/api/provider-servers/localhost' setup.ps1; then ok "ps1 PUT provider-servers/localhost"; else no "ps1 PUT fehlt"; fi
# 5. Kein gemma3:4b-Rest mehr.
if grep -q 'gemma3:4b' setup.ps1; then no "ps1 noch gemma3:4b-Referenz"; else ok "ps1 kein gemma3:4b"; fi
# Manifest: amd64-Override ist auch im ps1-Bundle.
if grep -q '^docker-compose.amd64.yml$' setup.ps1; then ok "ps1 amd64-Override im Manifest"; else no "ps1 amd64-Override fehlt"; fi

# Windows amd64: klont llm-cascade aus Source.
if grep -q 'git clone --depth 1 --branch' setup.ps1; then ok "ps1 klont llm-cascade-Source"; else no "ps1-Clone fehlt"; fi
# Windows amd64: layert den Build-Override.
if grep -q 'docker-compose.amd64.yml' setup.ps1; then ok "ps1 layert amd64-Override"; else no "ps1-amd64-Override fehlt"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
