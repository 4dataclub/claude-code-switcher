#!/usr/bin/env bash
# ollama-provision.sh — vom Setup-Header nach dem Entpacken gesourct.
# Definiert NUR Funktionen + Variablen, keine Top-Level-Seiteneffekte
# (läuft unter `set -euo pipefail`).

: "${OP_HOST_PROBE_URL:=http://localhost:11434}"
: "${OP_ADOPT_BASEURL:=http://host.docker.internal:11434/v1}"
: "${OP_INSTACK_BASEURL:=http://ollama:11434/v1}"
: "${OP_CASCADE_URL:=http://localhost:8091}"
: "${OP_OLLAMA_CONTAINER:=claude-switcher-ollama-1}"
: "${OP_DEFAULT_MODELS:=qwen2.5-coder:7b qwen2.5:7b llama3.2:3b}"

# Echoes "adopt" wenn ein Host-Ollama auf /api/tags antwortet, sonst "provision".
op_detect_mode() {
  if curl -fsS --max-time 5 "${OP_HOST_PROBE_URL}/api/tags" >/dev/null 2>&1; then
    echo adopt
  else
    echo provision
  fi
}

# Echoes distinct ollama-modelIds (sortiert, eine pro Zeile) aus der Cascade;
# Fallback: OP_DEFAULT_MODELS (Original-Reihenfolge), wenn cascade leer/down.
op_model_ids() {
  local out
  out=$(curl -fsS --max-time 10 "${OP_CASCADE_URL}/api/models" 2>/dev/null | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(1)
ids = sorted({m["modelId"] for m in data
              if m.get("provider") == "ollama" and m.get("modelId")})
print("\n".join(ids))
' 2>/dev/null)
  if [ -n "$out" ]; then
    printf '%s\n' "$out"
  else
    local m
    for m in $OP_DEFAULT_MODELS; do printf '%s\n' "$m"; done
  fi
}
