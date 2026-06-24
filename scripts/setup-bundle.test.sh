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

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
