#!/usr/bin/env bash
# build-setup.sh — Regeneriert setup.sh + setup.ps1 aus aktuellem Source-Stand.
#
# Single Source of Truth: alle Source-Files unter ../, Header-Templates unter
# ./setup-header.{sh,ps1}.tpl, CLAUDE.md-Template unter ./templates/CLAUDE.md.tpl.
#
# Aufruf:  bash scripts/build-setup.sh
# Output:  setup.sh + setup.ps1 im Repo-Root, ersetzt vorhandene.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$ROOT/scripts"
HEADER_SH="$SCRIPT_DIR/setup-header.sh.tpl"
HEADER_PS1="$SCRIPT_DIR/setup-header.ps1.tpl"
CLAUDE_TPL="$SCRIPT_DIR/templates/CLAUDE.md.tpl"

[[ -f "$HEADER_SH" ]]  || { echo "✗ Fehlt: $HEADER_SH"  >&2; exit 1; }
[[ -f "$HEADER_PS1" ]] || { echo "✗ Fehlt: $HEADER_PS1" >&2; exit 1; }
[[ -f "$CLAUDE_TPL" ]] || { echo "✗ Fehlt: $CLAUDE_TPL" >&2; exit 1; }

# (relativer Pfad ab ROOT, Marker-Name) — Reihenfolge wie in setup.sh-Header dokumentiert.
MANIFEST=(
  "Dockerfile|Dockerfile"
  "package.json|package_json"
  "server.js|server_js"
  "docker-compose.yml|docker_compose_yml"
  "public/index.html|public_index_html"
  "router/Dockerfile|router_Dockerfile"
  "router/config.json|router_config_json"
  "wrapper/claude-auto|wrapper_claude_auto"
  "wrapper/claude-auto.ps1|wrapper_claude_auto_ps1"
  "wrapper/install.sh|wrapper_install_sh"
  "wrapper/install.ps1|wrapper_install_ps1"
  "wrapper/router-watch.sh|wrapper_router_watch_sh"
  "wrapper/router-watch.ps1|wrapper_router_watch_ps1"
  "wrapper/switcher-banner.sh|wrapper_switcher_banner_sh"
  "wrapper/switcher-banner.ps1|wrapper_switcher_banner_ps1"
  "docs/screenshots/01-overview.png|docs_screenshots_01_overview"
  "docs/screenshots/02-auto-mode.png|docs_screenshots_02_auto_mode"
  "docs/screenshots/03-provider-anthropic.png|docs_screenshots_03_provider_anthropic"
  "docs/screenshots/04-provider-google.png|docs_screenshots_04_provider_google"
  "docs/screenshots/05-provider-openrouter.png|docs_screenshots_05_provider_openrouter"
  "docs/screenshots/06-90-percent-banner.png|docs_screenshots_06_90_percent_banner"
  "docs/screenshots/07-status-gemini-active.png|docs_screenshots_07_status_gemini_active"
  "docs/screenshots/08-banner-and-keys.png|docs_screenshots_08_banner_and_keys"
)

emit_payload_block() {
  # $1 = Datei-Pfad (absolut), $2 = Marker-Name
  local path="$1" marker="$2"
  if [[ ! -f "$path" ]]; then
    echo "  ⚠ Fehlt: $path — überspringe Marker $marker" >&2
    return
  fi
  printf '\n__BEGIN_%s__\n' "$marker"
  base64 < "$path" | tr -d '\n'
  printf '\n__END_%s__\n' "$marker"
}

emit_claude_md_block() {
  printf '\n__BEGIN_claude_md__\n'
  base64 < "$CLAUDE_TPL" | tr -d '\n'
  printf '\n__END_claude_md__\n'
}

build_one() {
  # $1 = Output-Datei (setup.sh oder setup.ps1), $2 = Header-Template
  local out="$1" header="$2"
  echo "▸ Baue $out aus $header + ${#MANIFEST[@]} Dateien + CLAUDE.md.tpl"
  {
    cat "$header"
    emit_claude_md_block
    for entry in "${MANIFEST[@]}"; do
      local path="${entry%%|*}" marker="${entry##*|}"
      emit_payload_block "$ROOT/$path" "$marker"
    done
  } > "$out.new"
  mv "$out.new" "$out"
  echo "  ✓ $out ($(wc -l < "$out") Zeilen, $(wc -c < "$out" | awk '{printf "%.1f KB", $1/1024}'))"
}

build_one "$ROOT/setup.sh"  "$HEADER_SH"
build_one "$ROOT/setup.ps1" "$HEADER_PS1"

chmod +x "$ROOT/setup.sh"

echo ""
echo "✓ Fertig. Diff zum letzten Commit:"
cd "$ROOT" && git diff --stat setup.sh setup.ps1 2>/dev/null || true
