#!/bin/bash
# ============================================================================
#  Claude-Switcher Deploy via Git (Branch-basiert).
#
#  Loest die alte Node.js-Variante auf :2000 ab durch den neuen Spring-Boot-
#  Stack (Supermodell-Modus). Idempotent: zweimal aufrufen ist OK.
#
#  Aufruf:
#    bash scripts/deploy.sh                    # aktueller lokaler Branch
#    bash scripts/deploy.sh main               # main-Branch
#    bash scripts/deploy.sh feat/foo dev       # Branch feat/foo, Dev-Variante :2010
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
set -e

REMOTE_USER="dataclub"
REMOTE_HOST="187.127.77.111"
GIT_REPO="git@github.com:4dataclub/claude-code-switcher.git"
LLM_CASCADE_REPO="git@github.com:4dataclub/llm-cascade.git"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BRANCH="${1:-$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)}"
VARIANT="${2:-prod}"

case "$VARIANT" in
  prod) REMOTE_DIR="/home/dataclub/claude-switcher-new"; PORT=2000; PROJECT="claude-switcher-sm" ;;
  dev)  REMOTE_DIR="/home/dataclub/claude-switcher-dev"; PORT=2010; PROJECT="claude-switcher-sm-dev" ;;
  *)    echo "Unbekannte Variante: $VARIANT (erwartet: prod|dev)"; exit 2 ;;
esac

echo "==> Deploye Branch '$BRANCH' ($VARIANT, Port $PORT) nach $REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR"
if [ -n "$(git -C "$ROOT_DIR" status --porcelain 2>/dev/null)" ]; then
  echo "    Hinweis: lokale uncommittete Aenderungen werden NICHT deployt."
fi

ssh "$REMOTE_USER@$REMOTE_HOST" \
  "BRANCH='$BRANCH' DIR='$REMOTE_DIR' GIT_REPO='$GIT_REPO' \
   LLM_CASCADE_REPO='$LLM_CASCADE_REPO' PORT='$PORT' PROJECT='$PROJECT' VARIANT='$VARIANT' bash -s" <<'REMOTE'
set -e

# 1) Repo-Klon / Update
if [ ! -d "$DIR/.git" ]; then
  echo "==> Erstmaliges Clone von $GIT_REPO nach $DIR"
  rm -rf "$DIR"
  git clone --quiet "$GIT_REPO" "$DIR"
fi
cd "$DIR"
git fetch --all --quiet
git checkout -B "$BRANCH" "origin/$BRANCH"
git reset --hard "origin/$BRANCH"
# Stale untracked files weg (gleiche Regel wie EduPro deploy-dev.sh)
git clean -fd java-backend/src angular-frontend/src 2>/dev/null || true
echo "==> Branch checked out: $(git log --oneline -1)"

# 2) llm-cascade-Image sicherstellen — pinned auf 0.7.6.
# ghcr.io/4dataclub/llm-cascade:0.7.6 ist nicht public (per 22.06.2026).
# Wenn das Image lokal noch nicht da ist: Source-Repo klonen + bauen.
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

# 3) Override-Compose: Port + Container-Suffix, plus Image-Pin
cat > "$DIR/docker-compose.override.yml" <<OVERRIDE
# Auto-generiert von scripts/deploy.sh (Variante: $VARIANT).
# - Frontend host-port auf $PORT
# - Container-Namen mit -sm bzw. -sm-dev Suffix (anti-Kollision)
# - llm-cascade Image gepinnt auf 0.7.6 (lokal gebaut)
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
    # Kein Host-Port-Mapping — der Switcher-Backend ruft Cascade nur intern
    # ueber http://llm-cascade:8090 im docker-Netz. Bei Mehrfach-Installationen
    # auf dem gleichen Host (z.B. EduPro + Switcher) verhindert das Port-Bind-
    # Kollisionen auf :8091.
    ports: !override []
  db:
    container_name: ${PROJECT}-db
OVERRIDE

# 4) Alten Node.js Switcher abraeumen — nur bei prod-Variante.
# Containern claude-switcher-claude-switcher-1 + claude-switcher-router-1 sind
# aus dem alten setup.sh-Self-Extractor und blockieren sonst Port 2000.
if [ "$VARIANT" = "prod" ]; then
  for OLD in claude-switcher-claude-switcher-1 claude-switcher-router-1; do
    if docker inspect "$OLD" >/dev/null 2>&1; then
      echo "==> Stoppe + entferne alten Container $OLD"
      docker stop "$OLD" >/dev/null 2>&1 || true
      docker rm   "$OLD" >/dev/null 2>&1 || true
    fi
  done
fi

# 5) Up
docker compose -p "$PROJECT" up -d --build
docker compose -p "$PROJECT" ps
REMOTE

cat <<EOF

=================================================================
 Switcher Deploy fertig (Branch: $BRANCH, Variante: $VARIANT)
   Frontend     http://$REMOTE_HOST:$PORT
   Router       http://$REMOTE_HOST:3456  (host-port unveraendert)

 Auf Server-Container: $PROJECT-*
   *-backend, *-frontend, *-router, *-llm-cascade, *-db
=================================================================
EOF
