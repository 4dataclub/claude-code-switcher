# ============================================================================
#  Claude-Switcher Deploy via Git - Windows / PowerShell-Pendant zu deploy.sh.
#
#  Aufruf:
#    .\scripts\deploy.ps1                    # aktueller lokaler Branch (prod)
#    .\scripts\deploy.ps1 main               # main-Branch (prod)
#    .\scripts\deploy.ps1 feat/foo dev       # Branch feat/foo, Dev-Variante :2010
#
#  Voraussetzung:
#    - Branch muss zu origin gepusht sein
#    - SSH-Key auf dem Server hinterlegt
#    - Auf dem Server: docker, docker compose verfuegbar
#
#  Ergebnis:
#    Prod-Variante:  http://187.127.77.111:2000   (Default)
#    Dev-Variante:   http://187.127.77.111:2010   (mit zweitem Arg "dev")
# ============================================================================
$ErrorActionPreference = "Stop"

$REMOTE_USER       = "dataclub"
$REMOTE_HOST       = "187.127.77.111"
$GIT_REPO          = "git@github.com:4dataclub/claude-code-switcher.git"
$LLM_CASCADE_REPO  = "git@github.com:4dataclub/llm-cascade.git"

$Root = (Get-Item -Path "$PSScriptRoot\..").FullName

if ($args.Count -gt 0) { $Branch = $args[0] }
else { $Branch = (& git -C $Root rev-parse --abbrev-ref HEAD).Trim() }

if ($args.Count -gt 1) { $Variant = $args[1] } else { $Variant = "prod" }

switch ($Variant) {
    "prod" { $RemoteDir = "/home/dataclub/claude-switcher-new"; $Port = 2000; $Project = "claude-switcher-sm" }
    "dev"  { $RemoteDir = "/home/dataclub/claude-switcher-dev"; $Port = 2010; $Project = "claude-switcher-sm-dev" }
    default { Write-Host "Unbekannte Variante: $Variant (erwartet: prod|dev)" -ForegroundColor Red; exit 2 }
}

Write-Host "==> Deploye Branch '$Branch' ($Variant, Port $Port) nach ${REMOTE_USER}@${REMOTE_HOST}:${RemoteDir}" -ForegroundColor Cyan
$dirty = & git -C $Root status --porcelain 2>$null
if ($dirty) {
    Write-Host "    Hinweis: lokale uncommittete Aenderungen werden NICHT deployt." -ForegroundColor Yellow
}

# Heredoc identisch zur Bash-Variante. Variablen werden ueber Env-Vars
# in den Remote-Shell-Aufruf injiziert (kein PowerShell-Variable-Expansion).
$remoteScript = @'
set -e

if [ ! -d "$DIR/.git" ]; then
  echo "==> Erstmaliges Clone von $GIT_REPO nach $DIR"
  rm -rf "$DIR"
  git clone --quiet "$GIT_REPO" "$DIR"
fi
cd "$DIR"
git fetch --all --quiet
git checkout -B "$BRANCH" "origin/$BRANCH"
git reset --hard "origin/$BRANCH"
git clean -fd java-backend/src angular-frontend/src 2>/dev/null || true
echo "==> Branch checked out: $(git log --oneline -1)"

TARGET_IMG="ghcr.io/4dataclub/llm-cascade:0.7.6"
if ! docker image inspect "$TARGET_IMG" >/dev/null 2>&1; then
  echo "==> llm-cascade-Image fehlt lokal, baue aus Source"
  CASCADE_DIR="/home/dataclub/llm-cascade"
  if [ ! -d "$CASCADE_DIR/.git" ]; then
    git clone --quiet "$LLM_CASCADE_REPO" "$CASCADE_DIR"
  else
    git -C "$CASCADE_DIR" fetch --all --quiet
    git -C "$CASCADE_DIR" reset --hard origin/main
  fi
  docker build -t "$TARGET_IMG" "$CASCADE_DIR" >/dev/null
  echo "    Image gebaut: $TARGET_IMG"
fi

cat > "$DIR/docker-compose.override.yml" <<OVERRIDE
# Auto-generiert von scripts/deploy.ps1 (Variante: $VARIANT).
services:
  switcher-backend:
    container_name: ${PROJECT}-backend
  switcher-frontend:
    container_name: ${PROJECT}-frontend
    ports: !override
      - "${PORT}:80"
  router:
    container_name: ${PROJECT}-router
  llm-cascade:
    image: ${TARGET_IMG}
    container_name: ${PROJECT}-llm-cascade
  db:
    container_name: ${PROJECT}-db
OVERRIDE

if [ "$VARIANT" = "prod" ]; then
  for OLD in claude-switcher-claude-switcher-1 claude-switcher-router-1; do
    if docker inspect "$OLD" >/dev/null 2>&1; then
      echo "==> Stoppe + entferne alten Container $OLD"
      docker stop "$OLD" >/dev/null 2>&1 || true
      docker rm   "$OLD" >/dev/null 2>&1 || true
    fi
  done
fi

docker compose -p "$PROJECT" up -d --build
docker compose -p "$PROJECT" ps
'@

$envPrefix = "BRANCH='$Branch' DIR='$RemoteDir' GIT_REPO='$GIT_REPO' " +
             "LLM_CASCADE_REPO='$LLM_CASCADE_REPO' PORT='$Port' " +
             "PROJECT='$Project' VARIANT='$Variant' "

$remoteScript | & ssh "${REMOTE_USER}@${REMOTE_HOST}" "$envPrefix bash -s"

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Green
Write-Host " Switcher Deploy fertig (Branch: $Branch, Variante: $Variant)"   -ForegroundColor Green
Write-Host "   Frontend     http://${REMOTE_HOST}:${Port}"                   -ForegroundColor Green
Write-Host "   Router       http://${REMOTE_HOST}:3456"                      -ForegroundColor Green
Write-Host "================================================================="  -ForegroundColor Green
