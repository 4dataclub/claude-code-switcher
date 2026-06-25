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

# Setzt den Default-provider_server "localhost" auf $1 (OpenAI-kompatible Base-URL).
op_set_default_server() {
  local base_url="$1"
  curl -fsS --max-time 10 -X PUT "${OP_CASCADE_URL}/api/provider-servers/localhost" \
    -H 'Content-Type: application/json' \
    -d "{\"baseUrl\":\"${base_url}\",\"isDefault\":true,\"description\":\"Auto: detect-or-provision\"}" \
    >/dev/null
}

# Exit 0 wenn das Modell auf dem Host-Ollama bereits vorhanden ist.
op_host_has_model() {
  local model="$1"
  curl -fsS --max-time 5 "${OP_HOST_PROBE_URL}/api/tags" 2>/dev/null | python3 -c '
import sys, json
want = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
names = {m.get("name") for m in d.get("models", [])}
sys.exit(0 if want in names else 1)
' "$model"
}

# Pullt $1 auf dem Host-Ollama via Native-API (großzügiger Timeout für große Modelle).
op_pull_host() {
  local model="$1"
  curl -fsS --max-time 1800 -X POST "${OP_HOST_PROBE_URL}/api/pull" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"${model}\",\"stream\":false}" >/dev/null
}

# Pullt $1 im in-stack Ollama-Container.
op_pull_instack() {
  local model="$1"
  docker exec "${OP_OLLAMA_CONTAINER}" ollama pull "$model" >/dev/null
}

# Wendet den Modus an: Default-Server setzen + Modelle sicherstellen.
op_apply() {
  local mode="$1" m models
  models=$(op_model_ids)
  case "$mode" in
    adopt)
      op_set_default_server "$OP_ADOPT_BASEURL"
      while IFS= read -r m; do
        [ -z "$m" ] && continue
        if op_host_has_model "$m"; then
          echo "  ✓ $m bereits auf Host-Ollama"
        else
          echo "  ▸ pulle $m auf Host-Ollama …"
          op_pull_host "$m" || echo "  ⚠ pull $m fehlgeschlagen"
        fi
      done <<EOF
$models
EOF
      ;;
    provision)
      op_set_default_server "$OP_INSTACK_BASEURL"
      while IFS= read -r m; do
        [ -z "$m" ] && continue
        echo "  ▸ pulle $m in in-stack Ollama …"
        op_pull_instack "$m" || echo "  ⚠ pull $m fehlgeschlagen"
      done <<EOF
$models
EOF
      ;;
    *)
      echo "op_apply: unbekannter Modus '$mode'" >&2
      return 1
      ;;
  esac
}
