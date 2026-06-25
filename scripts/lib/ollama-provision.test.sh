#!/usr/bin/env bash
# Tests für ollama-provision.sh — kein bats, reine Bash. Jeder Test läuft in
# einer Subshell, damit gemockte curl/docker-Funktionen nicht auslaufen.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/ollama-provision.sh"

PASS=0; FAIL=0
check() { # $1=name ; läuft Funktion $2 in Subshell, 0=ok
  if ( "$2" ); then echo "  ok: $1"; PASS=$((PASS+1));
  else echo "  FAIL: $1"; FAIL=$((FAIL+1)); fi
}
expect_eq() { [ "$1" = "$2" ] || { echo "    got [$1] want [$2]" >&2; return 1; }; }

t_detect_adopt() {
  curl() { return 0; }            # /api/tags antwortet
  expect_eq "$(op_detect_mode)" "adopt"
}
t_detect_provision() {
  curl() { return 7; }            # nichts da
  expect_eq "$(op_detect_mode)" "provision"
}
t_model_ids_parse() {
  curl() { cat <<'JSON'
[{"provider":"ollama","modelId":"qwen2.5:7b"},
 {"provider":"anthropic","modelId":"claude-opus-4-7"},
 {"provider":"ollama","modelId":"llama3.2:3b"},
 {"provider":"ollama","modelId":"qwen2.5:7b"}]
JSON
  }
  expect_eq "$(op_model_ids | tr '\n' ',')" "llama3.2:3b,qwen2.5:7b,"
}
t_model_ids_fallback() {
  curl() { return 7; }            # cascade nicht erreichbar
  expect_eq "$(op_model_ids | tr '\n' ',')" "qwen2.5-coder:7b,qwen2.5:7b,llama3.2:3b,"
}

check "detect_mode: Host-Ollama da -> adopt"        t_detect_adopt
check "detect_mode: kein Host-Ollama -> provision"  t_detect_provision
check "model_ids: distinct ollama, sortiert"        t_model_ids_parse
check "model_ids: Fallback-Defaults wenn cascade down" t_model_ids_fallback

t_apply_adopt() {
  CALLS=""
  op_model_ids()          { printf 'qwen2.5:7b\nllama3.2:3b\n'; }
  op_set_default_server() { CALLS="$CALLS set:$1;"; }
  op_host_has_model()     { return 1; }            # keins vorhanden -> alle pullen
  op_pull_host()          { CALLS="$CALLS host:$1;"; }
  op_pull_instack()       { CALLS="$CALLS stack:$1;"; }
  op_apply adopt >/dev/null
  expect_eq "$CALLS" " set:http://host.docker.internal:11434/v1; host:qwen2.5:7b; host:llama3.2:3b;"
}
t_apply_adopt_skips_present() {
  CALLS=""
  op_model_ids()          { printf 'qwen2.5:7b\nllama3.2:3b\n'; }
  op_set_default_server() { CALLS="$CALLS set:$1;"; }
  op_host_has_model()     { [ "$1" = "qwen2.5:7b" ]; }   # qwen da, llama nicht
  op_pull_host()          { CALLS="$CALLS host:$1;"; }
  op_apply adopt >/dev/null
  expect_eq "$CALLS" " set:http://host.docker.internal:11434/v1; host:llama3.2:3b;"
}
t_apply_provision() {
  CALLS=""
  op_model_ids()          { printf 'qwen2.5:7b\nllama3.2:3b\n'; }
  op_set_default_server() { CALLS="$CALLS set:$1;"; }
  op_pull_instack()       { CALLS="$CALLS stack:$1;"; }
  op_apply provision >/dev/null
  expect_eq "$CALLS" " set:http://ollama:11434/v1; stack:qwen2.5:7b; stack:llama3.2:3b;"
}
t_apply_bad_mode() {
  op_model_ids() { printf 'x\n'; }
  ! op_apply bogus 2>/dev/null      # Exit != 0 erwartet
}

check "apply adopt: Host-Server + pullt fehlende auf Host" t_apply_adopt
check "apply adopt: überspringt vorhandene Modelle"        t_apply_adopt_skips_present
check "apply provision: in-stack-Server + pullt im Container" t_apply_provision
check "apply: unbekannter Modus -> Fehler"                 t_apply_bad_mode

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
