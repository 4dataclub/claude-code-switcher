#!/usr/bin/env bash
# install.sh — Installiert claude-auto-Wrapper für Bash/Zsh (macOS, Linux)
# Pendant zu wrapper/install.ps1 (Windows)
#
# Was passiert:
#   1. Symlinkt claude-auto nach ~/.local/bin/claude-auto
#   2. Setzt einen Alias `claude` in ~/.zshrc (oder ~/.bashrc) → ruft den Wrapper
#      (Damit du wie gewohnt nur `claude` tippen musst)
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SRC_DIR/claude-auto"
SWITCHER_WATCH_SRC="$SRC_DIR/switcher-watch.sh"
DEST_DIR="$HOME/.local/bin"
DEST="$DEST_DIR/claude-auto"
SWITCHER_WATCH_DEST="$DEST_DIR/switcher-watch"

if [[ ! -f "$SRC" ]]; then
  echo "✗ Source nicht gefunden: $SRC" >&2
  exit 1
fi

# 1. Wrapper symlinken
mkdir -p "$DEST_DIR"
chmod +x "$SRC"
ln -sf "$SRC" "$DEST"
echo "✓ claude-auto installiert nach $DEST"

# 1b. switcher-watch symlinken (UI-Events + Router-Routing + Delegator in einem Fenster;
#     löst das alte router-watch ab)
if [[ -f "$SWITCHER_WATCH_SRC" ]]; then
  chmod +x "$SWITCHER_WATCH_SRC"
  ln -sf "$SWITCHER_WATCH_SRC" "$SWITCHER_WATCH_DEST"
  echo "✓ switcher-watch installiert nach $SWITCHER_WATCH_DEST"
fi

# PATH-Check
if ! echo ":$PATH:" | grep -q ":$DEST_DIR:"; then
  echo "⚠ $DEST_DIR ist nicht in deinem PATH."
  echo "  Füge folgende Zeile zu ~/.zshrc (oder ~/.bashrc) hinzu:"
  echo "    export PATH=\"\$HOME/.local/bin:\$PATH\""
fi

# 2. Alias `claude` → `claude-auto` in shell-rc
RC_FILE="$HOME/.zshrc"
if [[ "${SHELL:-}" == *"bash"* ]]; then
  RC_FILE="$HOME/.bashrc"
fi
[[ -f "$RC_FILE" ]] || touch "$RC_FILE"

MARKER="# === claude-switcher ==="
if grep -q "$MARKER" "$RC_FILE" 2>/dev/null; then
  echo "ℹ claude-Alias schon in $RC_FILE eingetragen"
else
  cat >> "$RC_FILE" <<EOF

$MARKER
# Original 'claude' bleibt erreichbar via 'command claude' oder 'claude-real'
alias claude='claude-auto'
alias claude-real='command claude'
# === /claude-switcher ===
EOF
  echo "✓ claude-Alias in $RC_FILE eingetragen"
  echo "  → ab jetzt ruft 'claude' den Wrapper auf, 'claude-real' das Original"
fi

echo ""
echo "Setup fertig. Bitte Terminal neu öffnen (oder: source $RC_FILE)."
echo ""
echo "Test:"
echo "  curl -s http://localhost:2000/api/status | jq   # → Switcher muss laufen"
echo "  claude                                          # → ruft jetzt claude-auto"
