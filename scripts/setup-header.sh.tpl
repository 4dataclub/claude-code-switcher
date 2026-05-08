#!/usr/bin/env bash
# Claude Code Switcher — Self-Extracting Setup (macOS / Linux / WSL2)
# Erzeugt:  ./<TARGET>/   (Switcher-Source-Code, baut Docker-Container)
# Und:      ~/.claude/hooks/switcher-banner.sh + Hook-Eintrag in settings.json
# Und:      ~/.claude/CLAUDE.md (Switcher-Anweisungen für claude im Chat)
#
# Aufruf:   ./setup.sh                       # entpackt nach ./claude-switcher/
#           ./setup.sh my-switcher           # eigener Zielordner
#           ./setup.sh --no-user-config      # nur Source, keine ~/.claude-Änderungen

set -euo pipefail

TARGET="claude-switcher"
WITH_USER_CONFIG=1
for arg in "$@"; do
  case "$arg" in
    --no-user-config) WITH_USER_CONFIG=0 ;;
    --*) echo "Unbekannte Option: $arg" >&2; exit 1 ;;
    *)   TARGET="$arg" ;;
  esac
done

[[ -e "$TARGET" && ! -d "$TARGET" ]] && { echo "✗ $TARGET ist kein Verzeichnis." >&2; exit 1; }
SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

mkdir -p "$TARGET"; cd "$TARGET"; mkdir -p public router wrapper docs/screenshots

extract() {
  local path="$1" marker="$2"
  mkdir -p "$(dirname "$path")"
  awk -v m="__BEGIN_${marker}__" -v e="__END_${marker}__" '
    $0 == m { capture=1; next }
    $0 == e { capture=0; exit }
    capture { print }
  ' "$SCRIPT" | base64 --decode > "$path"
}

echo "▸ Entpacke Switcher-Source nach $(pwd)/"
extract "Dockerfile" "Dockerfile"
extract "package.json" "package_json"
extract "server.js" "server_js"
extract "docker-compose.yml" "docker_compose_yml"
extract "public/index.html" "public_index_html"
extract "router/Dockerfile" "router_Dockerfile"
extract "router/config.json" "router_config_json"
extract "wrapper/claude-auto" "wrapper_claude_auto"
extract "wrapper/claude-auto.ps1" "wrapper_claude_auto_ps1"
extract "wrapper/install.sh" "wrapper_install_sh"
extract "wrapper/install.ps1" "wrapper_install_ps1"
extract "wrapper/router-watch.sh" "wrapper_router_watch_sh"
extract "wrapper/router-watch.ps1" "wrapper_router_watch_ps1"
extract "wrapper/switcher-banner.sh" "wrapper_switcher_banner_sh"
extract "wrapper/switcher-banner.ps1" "wrapper_switcher_banner_ps1"
extract "docs/screenshots/01-overview.png"             "docs_screenshots_01_overview"
extract "docs/screenshots/02-auto-mode.png"            "docs_screenshots_02_auto_mode"
extract "docs/screenshots/03-provider-anthropic.png"   "docs_screenshots_03_provider_anthropic"
extract "docs/screenshots/04-provider-google.png"      "docs_screenshots_04_provider_google"
extract "docs/screenshots/05-provider-openrouter.png"  "docs_screenshots_05_provider_openrouter"
extract "docs/screenshots/06-90-percent-banner.png"    "docs_screenshots_06_90_percent_banner"
extract "docs/screenshots/07-status-gemini-active.png" "docs_screenshots_07_status_gemini_active"
extract "docs/screenshots/08-banner-and-keys.png"      "docs_screenshots_08_banner_and_keys"
chmod +x wrapper/claude-auto wrapper/install.sh wrapper/router-watch.sh wrapper/switcher-banner.sh 2>/dev/null || true
echo "  ✓ Source entpackt"

if (( WITH_USER_CONFIG )); then
  CLAUDE_DIR="${HOME}/.claude"
  mkdir -p "$CLAUDE_DIR/hooks"

  echo "▸ Installiere Banner-Hook nach $CLAUDE_DIR/hooks/switcher-banner.sh"
  cp wrapper/switcher-banner.sh "$CLAUDE_DIR/hooks/switcher-banner.sh"
  chmod +x "$CLAUDE_DIR/hooks/switcher-banner.sh"

  CLAUDE_MD="$CLAUDE_DIR/CLAUDE.md"
  MARK_BEG="<!-- BEGIN claude-switcher -->"
  MARK_END="<!-- END claude-switcher -->"
  TMP_BLOCK=$(mktemp)
  awk -v m="__BEGIN_claude_md__" -v e="__END_claude_md__" '
    $0==m{c=1;next} $0==e{exit} c
  ' "$SCRIPT" | base64 --decode > "$TMP_BLOCK"

  echo "▸ Schreibe Switcher-Anweisungen in $CLAUDE_MD"
  if [[ -f "$CLAUDE_MD" ]] && grep -qF "$MARK_BEG" "$CLAUDE_MD"; then
    python3 - "$CLAUDE_MD" "$TMP_BLOCK" "$MARK_BEG" "$MARK_END" <<'PYEOF'
import sys, re
md_path, block_path, beg, end = sys.argv[1:5]
with open(md_path) as f: md = f.read()
with open(block_path) as f: block = f.read().rstrip() + "\n"
new_block = f"{beg}\n{block}{end}\n"
md = re.sub(re.escape(beg) + r".*?" + re.escape(end) + r"\n?", new_block, md, flags=re.DOTALL)
with open(md_path, "w") as f: f.write(md)
PYEOF
    echo "  ✓ Switcher-Block aktualisiert"
  else
    {
      [[ -f "$CLAUDE_MD" ]] && cat "$CLAUDE_MD" && echo
      echo "$MARK_BEG"; cat "$TMP_BLOCK"; echo "$MARK_END"
    } > "$CLAUDE_MD.tmp" && mv "$CLAUDE_MD.tmp" "$CLAUDE_MD"
    echo "  ✓ CLAUDE.md erstellt"
  fi
  rm -f "$TMP_BLOCK"

  echo "▸ Registriere UserPromptSubmit-Hook in $CLAUDE_DIR/settings.json"
  python3 - "$CLAUDE_DIR/settings.json" "$CLAUDE_DIR/hooks/switcher-banner.sh" <<'PYEOF'
import json, sys
from pathlib import Path
sp, hp = Path(sys.argv[1]), sys.argv[2]
data = {}
if sp.exists():
    try: data = json.loads(sp.read_text())
    except: data = {}
data.setdefault("hooks", {})
existing = data["hooks"].get("UserPromptSubmit", [])
if not any(any(h.get("command","").endswith("switcher-banner.sh") for h in e.get("hooks",[])) for e in existing):
    existing.append({"matcher":".*","hooks":[{"type":"command","command":hp,"shell":"bash","async":False}]})
    data["hooks"]["UserPromptSubmit"] = existing
    sp.parent.mkdir(parents=True, exist_ok=True)
    sp.write_text(json.dumps(data, indent=2))
    print("  ✓ Hook registriert")
else:
    print("  ✓ Hook war schon registriert")
PYEOF
fi

if command -v docker >/dev/null 2>&1; then
  echo "▸ Baue + starte Docker-Container"
  if docker compose version >/dev/null 2>&1; then
    docker compose up -d --build 2>&1 | tail -5
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose up -d --build 2>&1 | tail -5
  fi
else
  echo "  ⚠ docker nicht installiert"
fi

# Wrapper-Alias automatisch installieren (Bash/Zsh)
# Damit ist NUR setup.sh nötig — kein zweiter Schritt mehr für den User.
echo ""
echo "▸ Installiere claude-Wrapper-Alias"
bash "$(pwd)/wrapper/install.sh"

# Welches Shell-rc-File hat install.sh angefasst?
RC_FILE="$HOME/.zshrc"
[[ "${SHELL:-}" == *"bash"* ]] && RC_FILE="$HOME/.bashrc"

echo ""
echo "✓ Komplett fertig. Eine letzte Aktion:"
echo "  → Terminal neu öffnen  (oder:  source $RC_FILE )"
echo ""
echo "Dann:"
echo "  claude                  # läuft jetzt durch den Wrapper mit Auto-Failover"
echo "  http://localhost:2000   # UI zum Provider/Modell wählen"
echo "  $(pwd)/wrapper/router-watch.sh   # live anschauen welches Modell antwortet"
exit 0
