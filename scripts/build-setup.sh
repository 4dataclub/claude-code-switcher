#!/usr/bin/env bash
# build-setup.sh — Regeneriert setup.sh + setup.ps1 aus aktuellem Source-Stand.
#
# Single Source of Truth: alle Source-Files unter ../ werden rekursiv eingesammelt
# (java-backend/, frontend/, router/, wrapper/, docs/, plus Root-Files wie
# docker-compose.yml). Header-Templates unter ./setup-header.{sh,ps1}.tpl,
# CLAUDE.md-Template unter ./templates/CLAUDE.md.tpl.
#
# Aufruf:  bash scripts/build-setup.sh
# Output:  setup.sh + setup.ps1 im Repo-Root, ersetzt vorhandene.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$ROOT/scripts"
HEADER_SH="$SCRIPT_DIR/setup-header.sh.tpl"
HEADER_PS1="$SCRIPT_DIR/setup-header.ps1.tpl"
CLAUDE_TPL="$SCRIPT_DIR/templates/CLAUDE.md.tpl"
SUPERMODEL_TPL="$SCRIPT_DIR/templates/supermodel-policy.md.tpl"

[[ -f "$HEADER_SH" ]]  || { echo "✗ Fehlt: $HEADER_SH"  >&2; exit 1; }
[[ -f "$HEADER_PS1" ]] || { echo "✗ Fehlt: $HEADER_PS1" >&2; exit 1; }
[[ -f "$CLAUDE_TPL" ]] || { echo "✗ Fehlt: $CLAUDE_TPL" >&2; exit 1; }
[[ -f "$SUPERMODEL_TPL" ]] || { echo "✗ Fehlt: $SUPERMODEL_TPL" >&2; exit 1; }

# Source-Files einsammeln. Reihenfolge:
#   1) docker-compose.yml (Root)
#   2) java-backend/ (alles ausser target/, .gitignore wird mitgenommen)
#   3) frontend/
#   4) router/
#   5) wrapper/
#   6) docs/screenshots/*.png
build_manifest() {
  # Root-Files
  echo "docker-compose.yml"
  # java-backend recursive
  if [[ -d "$ROOT/java-backend" ]]; then
    (cd "$ROOT" && find java-backend -type f \
      -not -path 'java-backend/target/*' \
      -not -path 'java-backend/.idea/*' \
      | sort)
  fi
  # frontend recursive
  if [[ -d "$ROOT/frontend" ]]; then
    (cd "$ROOT" && find frontend -type f | sort)
  fi
  # router recursive
  if [[ -d "$ROOT/router" ]]; then
    (cd "$ROOT" && find router -type f | sort)
  fi
  # wrapper recursive
  if [[ -d "$ROOT/wrapper" ]]; then
    (cd "$ROOT" && find wrapper -type f | sort)
  fi
  # agents recursive (Supermodell-Opt-in: @supermodel-Agent)
  if [[ -d "$ROOT/agents" ]]; then
    (cd "$ROOT" && find agents -type f | sort)
  fi
  # docs/screenshots
  if [[ -d "$ROOT/docs/screenshots" ]]; then
    (cd "$ROOT" && find docs/screenshots -type f -name '*.png' | sort)
  fi
}

# Marker-Name aus Pfad: a/b/c.txt → a_b_c_txt (Pfad-Trenner + Punkt → Underscore)
path_to_marker() {
  echo "$1" | tr '/.-' '___'
}

emit_payload_block() {
  local path="$1" marker
  marker=$(path_to_marker "$path")
  if [[ ! -f "$ROOT/$path" ]]; then
    echo "  ⚠ Fehlt: $path — überspringe" >&2
    return
  fi
  printf '\n__BEGIN_%s__\n' "$marker"
  base64 < "$ROOT/$path" | tr -d '\n'
  printf '\n__END_%s__\n' "$marker"
}

emit_claude_md_block() {
  printf '\n__BEGIN_claude_md__\n'
  base64 < "$CLAUDE_TPL" | tr -d '\n'
  printf '\n__END_claude_md__\n'
}

emit_supermodel_policy_block() {
  printf '\n__BEGIN_supermodel_policy__\n'
  base64 < "$SUPERMODEL_TPL" | tr -d '\n'
  printf '\n__END_supermodel_policy__\n'
}

# Manifest-Liste fuer die Header (eingebettet als Bash-Array bzw. PowerShell-Array)
emit_manifest_block_sh() {
  printf '\n__BEGIN_manifest__\n'
  build_manifest
  printf '\n__END_manifest__\n'
}

build_one() {
  local out="$1" header="$2"
  local mf
  mf=$(build_manifest)
  local n_files
  n_files=$(echo "$mf" | wc -l | tr -d ' ')
  echo "▸ Baue $out aus $header + $n_files Source-Files + CLAUDE.md.tpl"
  {
    cat "$header"
    emit_claude_md_block
    emit_supermodel_policy_block
    emit_manifest_block_sh
    while IFS= read -r path; do
      [[ -z "$path" ]] && continue
      emit_payload_block "$path"
    done <<< "$mf"
  } > "$out.new"
  mv "$out.new" "$out"
  echo "  ✓ $out ($(wc -l < "$out") Zeilen, $(wc -c < "$out" | awk '{printf "%.1f KB", $1/1024}'))"
}

build_one "$ROOT/setup.sh"  "$HEADER_SH"
build_one "$ROOT/setup.ps1" "$HEADER_PS1"

chmod +x "$ROOT/setup.sh"

echo ""
echo "✓ Fertig. Manifest hatte $(build_manifest | wc -l | tr -d ' ') Files."
