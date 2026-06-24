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

mkdir -p "$TARGET"; cd "$TARGET"

# Marker-Name aus Pfad: a/b/c.txt → a_b_c_txt
path_to_marker() { echo "$1" | tr '/.-' '___'; }

extract() {
  local path="$1" marker
  marker=$(path_to_marker "$path")
  mkdir -p "$(dirname "$path")"
  awk -v m="__BEGIN_${marker}__" -v e="__END_${marker}__" '
    $0 == m { capture=1; next }
    $0 == e { capture=0; exit }
    capture { print }
  ' "$SCRIPT" | base64 --decode > "$path"
}

echo "▸ Entpacke Switcher-Source nach $(pwd)/"
# Manifest aus dem Bundle ziehen, dann jeden Eintrag extrahieren.
MANIFEST=$(awk '/^__BEGIN_manifest__$/{c=1;next} /^__END_manifest__$/{exit} c' "$SCRIPT")
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  extract "$path"
done <<< "$MANIFEST"
chmod +x wrapper/claude-auto wrapper/install.sh wrapper/router-watch.sh wrapper/switcher-banner.sh 2>/dev/null || true
echo "  ✓ Source entpackt ($(echo "$MANIFEST" | grep -c '^.' || true) Files)"

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
  DC="docker compose"
  docker compose version >/dev/null 2>&1 || { command -v docker-compose >/dev/null 2>&1 && DC="docker-compose"; }

  # --- Arch-Lane: amd64 (x86_64) baut llm-cascade aus Source; arm64 nutzt das Image. ---
  # Grund: das veroeffentlichte Image ist arm64-only.
  CF="-f docker-compose.yml"
  ARCH="$(uname -m)"
  if [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "amd64" ]; then
    LLM_CASCADE_REF="${LLM_CASCADE_REF:-main}"
    LLM_CASCADE_REPO="${LLM_CASCADE_REPO:-https://github.com/4dataclub/llm-cascade.git}"
    if [ ! -d llm-cascade ]; then
      echo "  ▸ amd64 ($ARCH) erkannt → klone llm-cascade ($LLM_CASCADE_REF) für Source-Build"
      if ! git clone --depth 1 --branch "$LLM_CASCADE_REF" "$LLM_CASCADE_REPO" llm-cascade; then
        echo "✗ git clone llm-cascade fehlgeschlagen — auf amd64 ist das Image nicht nutzbar. Abbruch." >&2
        exit 1
      fi
    else
      echo "  ✓ llm-cascade-Source bereits vorhanden → kein erneuter Clone"
    fi
    CF="$CF -f docker-compose.amd64.yml"
  fi

  # --- GPU-Lane (orthogonal): NVIDIA auf Linux layert gpu.yml. CPU = Warnung + weiterlaufen. ---
  # Override-Escape-Hatch: SWITCHER_GPU=auto|nvidia|none. AMD/Intel sind YAGNI → expliziter Seam.
  GPU_VENDOR="${SWITCHER_GPU:-auto}"
  if [ "$GPU_VENDOR" = "auto" ]; then
    if [ "$(uname -s)" = "Linux" ] && command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
      GPU_VENDOR="nvidia"
    else
      GPU_VENDOR="none"
    fi
  fi
  case "$GPU_VENDOR" in
    nvidia)
      CF="$CF -f docker-compose.gpu.yml"
      echo "  ▸ NVIDIA-GPU aktiv → GPU-Override (docker-compose.gpu.yml)" ;;
    none)
      echo "  ⚠ Keine NVIDIA-GPU → CPU-Modus (läuft weiter)" ;;
    *)
      echo "  ⚠ SWITCHER_GPU=$GPU_VENDOR nicht unterstützt (nur 'nvidia' vorgebaut) → CPU-Modus" ;;
  esac

  echo "▸ Baue + starte Stack (ohne in-stack Ollama)"
  $DC $CF up -d --build 2>&1 | tail -5

  if [ -f "$(pwd)/scripts/lib/ollama-provision.sh" ]; then
    . "$(pwd)/scripts/lib/ollama-provision.sh"

    echo "▸ Warte auf llm-cascade (:8091) …"
    for _ in $(seq 1 60); do
      curl -fsS --max-time 2 "${OP_CASCADE_URL}/api/health" >/dev/null 2>&1 && break
      sleep 2
    done

    MODE=$(op_detect_mode)
    if [ "$MODE" = provision ]; then
      echo "▸ Kein Host-Ollama gefunden → starte in-stack Ollama (Profil local-llm)"
      $DC $CF --profile local-llm up -d 2>&1 | tail -3
      echo "  ▸ warte auf Ollama-Container …"
      for _ in $(seq 1 30); do
        docker exec "$OP_OLLAMA_CONTAINER" ollama list >/dev/null 2>&1 && break
        sleep 2
      done
    else
      echo "▸ Host-Ollama gefunden → adoptiere (kein eigener Container)"
    fi
    op_apply "$MODE"
  else
    echo "  ⚠ scripts/lib/ollama-provision.sh fehlt — überspringe Ollama-Setup"
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
