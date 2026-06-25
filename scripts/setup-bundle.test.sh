#!/usr/bin/env bash
# Prüft, dass build-setup.sh die Lib bundelt + der Header sie verdrahtet.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

bash scripts/build-setup.sh >/dev/null 2>&1 || { echo "build-setup.sh fehlgeschlagen"; exit 1; }

# 1. setup.sh ist syntaktisch valide.
if bash -n setup.sh 2>/dev/null; then ok "setup.sh bash -n sauber"; else no "setup.sh Syntaxfehler"; fi
# 2. Lib ist eingebettet (Marker vorhanden).
if grep -q '__BEGIN_scripts_lib_ollama_provision_sh__' setup.sh; then ok "Lib im Bundle"; else no "Lib NICHT gebundlet"; fi
# 3. Testfile ist NICHT eingebettet.
if grep -q 'ollama_provision_test_sh' setup.sh; then no "Testfile fälschlich gebundlet"; else ok "Testfile nicht gebundlet"; fi
# 4. Header sourct die Lib + ruft op_apply.
if grep -q 'scripts/lib/ollama-provision.sh' setup.sh && grep -q 'op_apply' setup.sh; then
  ok "Header sourct Lib + ruft op_apply"
else no "Header-Verdrahtung fehlt"; fi
# 5. Provision-Pfad nutzt das Profil.
if grep -q 'profile local-llm' setup.sh; then ok "Provision nutzt --profile local-llm"; else no "Profil-Aufruf fehlt"; fi
# Manifest/Payload: amd64-Override ist mitgebundelt.
if grep -q '^docker-compose.amd64.yml$' setup.sh; then ok "amd64-Override im Manifest"; else no "amd64-Override fehlt im Bundle"; fi

# Arch-Block: amd64 klont llm-cascade aus Source.
if grep -q 'git clone --depth 1 --branch' setup.sh; then ok "amd64 klont llm-cascade-Source"; else no "amd64-Clone fehlt"; fi
# Arch-Block: amd64 layert den Build-Override.
if grep -q '\-f docker-compose.amd64.yml' setup.sh; then ok "amd64 layert Build-Override"; else no "amd64-Override-Layer fehlt"; fi
# Clone-Fehler auf amd64 ist fatal.
if grep -q 'git clone .* fehlgeschlagen' setup.sh; then ok "amd64-Clone-Fehler bricht ab"; else no "amd64-Clone-Abbruch fehlt"; fi
# GPU-Seam: SWITCHER_GPU-Override vorhanden.
if grep -q 'SWITCHER_GPU' setup.sh; then ok "GPU-Override SWITCHER_GPU"; else no "SWITCHER_GPU-Seam fehlt"; fi
# GPU-Seam: dokumentierter Unsupported-Vendor-Zweig (AMD/Intel YAGNI).
if grep -q 'nicht unterstützt' setup.sh; then ok "GPU unsupported-vendor Seam"; else no "GPU-Vendor-Seam fehlt"; fi

# Mac-GPU: provision bevorzugt natives Metal-Ollama vor CPU-Container.
if grep -q 'macOS.*natives Ollama' setup.sh; then ok "Mac startet natives Ollama (Metal)"; else no "Mac-Metal-Block fehlt"; fi
# Mac-GPU: TTY-geschuetzte brew-Abfrage (Linux/CI blockt nie).
if grep -q 'brew install ollama' setup.sh && grep -q '\[ -t 0 \]' setup.sh; then ok "Mac brew-Prompt TTY-guarded"; else no "Mac brew-Prompt/TTY-Guard fehlt"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
