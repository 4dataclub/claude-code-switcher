# Ollama Detect-or-Provision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beim Switcher-Setup ein vorhandenes Host-Ollama erkennen und adoptieren, sonst den in-stack Ollama hochziehen — inkl. der richtigen local-Modelle.

**Architecture:** Reine Setup-Logik auf der vorhandenen llm-cascade-0.8.1-Runtime. Eine gesourcte Bash-Lib (`scripts/lib/ollama-provision.sh`) kapselt Erkennung, Modell-Ableitung und das Setzen des Default-`provider_server` per `PUT /api/provider-servers/localhost`. Der in-stack Ollama wandert hinter ein Compose-Profil; das Setup startet ihn nur im Provision-Pfad. Windows bekommt die gleiche Logik als inline-PowerShell.

**Tech Stack:** Bash + PowerShell (Setup), Docker Compose, `curl`, `python3` (JSON-Parsing, bereits Setup-Abhängigkeit), llm-cascade REST (:8091).

## Global Constraints

- **Kein Java-Change.** `ProviderServerResolver`, `OllamaProvisioner`, `PUT /api/provider-servers/{name}` und `GET /api/models` existieren in llm-cascade 0.8.1 bereits.
- **Modell-Quelle:** `GET http://localhost:8091/api/models` → Einträge mit `provider=="ollama"` → distinct `modelId`. Fallback-Default exakt: `qwen2.5-coder:7b`, `qwen2.5:7b`, `llama3.2:3b` (in dieser Reihenfolge).
- **Adopt-Base-URL (cascade-Perspektive):** `http://host.docker.internal:11434/v1`.
- **Provision-Base-URL (cascade-Perspektive):** `http://ollama:11434/v1`.
- **Host-Probe (Setup-Perspektive):** `http://localhost:11434/api/tags`.
- **In-stack Container-Name:** `claude-switcher-ollama-1`. Compose-Profil: `local-llm`.
- **GPU-Override** `docker-compose.gpu.yml` nur auf Linux mit funktionierender `nvidia-smi`; macOS/Windows = Base/CPU.
- **Fail-closed bleibt:** beide Ziele sind intern (Host-Gateway bzw. Service-DNS), kein Cloud-Eintrag.
- **Generierte Artefakte** (`setup.sh`, `setup.ps1`) werden NUR via `scripts/build-setup.sh` regeneriert, nie handeditiert.
- **`set -euo pipefail`** gilt im Setup-Header — die Lib darf beim Sourcen keine Seiteneffekte/Exits auslösen, nur Funktionen + Variablen definieren.
- Spec: `docs/superpowers/specs/2026-06-24-ollama-detect-or-provision-design.md`.

---

## File Structure

- `scripts/lib/ollama-provision.sh` (NEU) — gesourcte Bash-Funktionen: Erkennung, Modell-Ableitung, Default-Server setzen, Modelle sicherstellen. Keine Top-Level-Seiteneffekte.
- `scripts/lib/ollama-provision.test.sh` (NEU) — eigenständiges Bash-Test-Skript (kein `bats`), mockt `curl`/`docker` per Shell-Funktion in Subshells. Wird NICHT gebundlet.
- `docker-compose.yml` (MODIFY) — `ollama`-Service hinter `profiles: ["local-llm"]`, Entrypoint nur `serve`, `extra_hosts` für `host.docker.internal` am `llm-cascade`-Service.
- `scripts/build-setup.sh` (MODIFY) — `scripts/lib/ollama-provision.sh` ins Manifest aufnehmen (Testfile ausgeschlossen).
- `scripts/setup-header.sh.tpl` (MODIFY) — Docker-Block ersetzen: GPU-Datei-Wahl, zweiphasiges `up`, Lib sourcen, `op_detect_mode` + `op_apply`.
- `scripts/setup-header.ps1.tpl` (MODIFY) — gleiche Logik als inline-PowerShell (keine `.sh`-Quelle möglich).
- `docker-compose.gpu.yml` — unverändert (existiert bereits).

---

## Task 1: Bash-Lib — Erkennung + Modell-Ableitung

**Files:**
- Create: `scripts/lib/ollama-provision.sh`
- Test: `scripts/lib/ollama-provision.test.sh`

**Interfaces:**
- Produces:
  - Env-Defaults (überschreibbar): `OP_HOST_PROBE_URL=http://localhost:11434`, `OP_ADOPT_BASEURL=http://host.docker.internal:11434/v1`, `OP_INSTACK_BASEURL=http://ollama:11434/v1`, `OP_CASCADE_URL=http://localhost:8091`, `OP_OLLAMA_CONTAINER=claude-switcher-ollama-1`, `OP_DEFAULT_MODELS="qwen2.5-coder:7b qwen2.5:7b llama3.2:3b"`.
  - `op_detect_mode` → echoes `adopt` (Host-Ollama antwortet auf `$OP_HOST_PROBE_URL/api/tags`) oder `provision`.
  - `op_model_ids` → echoes distinct ollama-`modelId`s (eine pro Zeile, sortiert) aus `$OP_CASCADE_URL/api/models`; bei leerer/fehlender Antwort die `OP_DEFAULT_MODELS`.

- [ ] **Step 1: Write the failing test**

Create `scripts/lib/ollama-provision.test.sh`:

```bash
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

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/lib/ollama-provision.test.sh`
Expected: FAIL — `ollama-provision.sh` existiert nicht (`No such file or directory` beim Sourcen).

- [ ] **Step 3: Write minimal implementation**

Create `scripts/lib/ollama-provision.sh`:

```bash
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/lib/ollama-provision.test.sh`
Expected: PASS — `passed=4 failed=0`, Exit 0.

- [ ] **Step 5: Commit**

```bash
chmod +x scripts/lib/ollama-provision.test.sh
git add scripts/lib/ollama-provision.sh scripts/lib/ollama-provision.test.sh
git commit -m "feat: Ollama detect-or-provision lib — Erkennung + Modell-Ableitung"
```

---

## Task 2: Bash-Lib — Default-Server setzen + Modelle sicherstellen

**Files:**
- Modify: `scripts/lib/ollama-provision.sh` (Funktionen anhängen)
- Test: `scripts/lib/ollama-provision.test.sh` (Tests anhängen)

**Interfaces:**
- Consumes: `op_model_ids`, Env-Defaults aus Task 1.
- Produces:
  - `op_set_default_server <baseUrl>` → `PUT $OP_CASCADE_URL/api/provider-servers/localhost` mit `{baseUrl,isDefault:true,description}`.
  - `op_host_has_model <modelId>` → Exit 0 wenn das Modell in `$OP_HOST_PROBE_URL/api/tags` gelistet ist, sonst 1.
  - `op_pull_host <modelId>` → `POST $OP_HOST_PROBE_URL/api/pull {name,stream:false}`.
  - `op_pull_instack <modelId>` → `docker exec $OP_OLLAMA_CONTAINER ollama pull <modelId>`.
  - `op_apply <adopt|provision>` → setzt Default-Server passend zum Modus und stellt jedes Modell aus `op_model_ids` sicher (adopt: nur fehlende auf Host pullen; provision: alle im Container pullen). Fortschritt nach stdout. Unbekannter Modus → Exit 1.

- [ ] **Step 1: Write the failing test**

An `scripts/lib/ollama-provision.test.sh` VOR der `echo "passed=…"`-Zeile anhängen:

```bash
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/lib/ollama-provision.test.sh`
Expected: FAIL — `op_apply`/`op_set_default_server` etc. nicht definiert (Tasks-1-Tests bleiben grün, die 4 neuen schlagen fehl).

- [ ] **Step 3: Write minimal implementation**

An `scripts/lib/ollama-provision.sh` anhängen:

```bash
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/lib/ollama-provision.test.sh`
Expected: PASS — `passed=8 failed=0`, Exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/lib/ollama-provision.sh scripts/lib/ollama-provision.test.sh
git commit -m "feat: Ollama detect-or-provision lib — Server setzen + Modelle sicherstellen"
```

---

## Task 3: docker-compose.yml — Ollama hinter Profil, Entrypoint, extra_hosts

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: nichts (statische Compose-Definition).
- Produces: `ollama`-Service nur bei `--profile local-llm` aktiv; `llm-cascade` kann `host.docker.internal` auflösen.

- [ ] **Step 1: Write the failing test**

Create `scripts/compose-shape.test.sh`:

```bash
#!/usr/bin/env bash
# Prüft die Compose-Form für detect-or-provision.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

# 1. Default-Profil startet KEIN ollama (Service hinter local-llm).
if docker compose config --services 2>/dev/null | grep -qx ollama; then
  no "ollama darf im Default-Profil nicht erscheinen"
else
  ok "ollama nur unter Profil local-llm"
fi
# 2. Mit Profil erscheint ollama.
if docker compose --profile local-llm config --services 2>/dev/null | grep -qx ollama; then
  ok "ollama erscheint mit --profile local-llm"
else
  no "ollama fehlt trotz --profile local-llm"
fi
# 3. llm-cascade hat host.docker.internal extra_host.
if docker compose config 2>/dev/null | grep -q 'host.docker.internal:host-gateway'; then
  ok "llm-cascade extra_hosts host.docker.internal gesetzt"
else
  no "extra_hosts host.docker.internal fehlt"
fi
# 4. Kein gemma3:4b-Pull mehr im ollama-Entrypoint.
if docker compose --profile local-llm config 2>/dev/null | grep -q 'gemma3:4b'; then
  no "alter gemma3:4b-Pull noch im Entrypoint"
else
  ok "kein gemma3:4b-Pull mehr"
fi
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/compose-shape.test.sh`
Expected: FAIL — aktuell ist `ollama` default-an (Test 1 schlägt fehl), kein `extra_hosts` (Test 3), `gemma3:4b` noch da (Test 4).

- [ ] **Step 3: Write minimal implementation**

In `docker-compose.yml` im `llm-cascade`-Service nach dem `environment:`-Block (vor `depends_on:`) einfügen:

```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

Den `ollama`-Service ersetzen. Vorher:

```yaml
  ollama:
    image: ollama/ollama:latest
    container_name: claude-switcher-ollama-1
    volumes:
      - ollama_data:/root/.ollama
    restart: unless-stopped
    # Beim Start: gemma3:4b pullen (idempotent, springt nur an wenn nicht
    # schon im Volume). Wer ein anderes Modell will: `docker exec
    # claude-switcher-ollama-1 ollama pull <model>` oder über das Switcher-UI
    # die Modell-ID in der ai_model_config-Tabelle aendern.
    entrypoint: ["/bin/sh", "-c", "ollama serve & sleep 5 && ollama pull gemma3:4b; wait"]
```

Nachher:

```yaml
  # Nur aktiv im Provision-Pfad (Setup startet via `--profile local-llm`, wenn
  # KEIN Host-Ollama gefunden wird). Modelle werden vom Setup nachgezogen
  # (scripts/lib/ollama-provision.sh), nicht hier hartkodiert. Default-CMD des
  # Images = `ollama serve`.
  ollama:
    image: ollama/ollama:latest
    container_name: claude-switcher-ollama-1
    profiles: ["local-llm"]
    volumes:
      - ollama_data:/root/.ollama
    restart: unless-stopped
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/compose-shape.test.sh`
Expected: PASS — `passed=4 failed=0`.

Zusätzlich: `docker compose config >/dev/null && echo OK` → `OK` (Compose bleibt valide).

- [ ] **Step 5: Commit**

```bash
chmod +x scripts/compose-shape.test.sh
git add docker-compose.yml scripts/compose-shape.test.sh
git commit -m "feat: in-stack Ollama hinter Profil local-llm + host.docker.internal für llm-cascade"
```

---

## Task 4: build-setup.sh-Manifest + setup-header.sh.tpl-Verdrahtung

**Files:**
- Modify: `scripts/build-setup.sh:32` (Manifest)
- Modify: `scripts/setup-header.sh.tpl:111-120` (Docker-Block)
- Regenerate: `setup.sh`

**Interfaces:**
- Consumes: `op_detect_mode`, `op_apply`, Env-Defaults (Task 1/2); Compose-Profil `local-llm` (Task 3).
- Produces: lauffähiges `setup.sh`, das nach `docker compose up` erkennt-oder-bereitstellt.

- [ ] **Step 1: Write the failing test**

Create `scripts/setup-bundle.test.sh`:

```bash
#!/usr/bin/env bash
# Prüft, dass build-setup.sh die Lib bundelt + der Header sie verdrahtet.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

bash scripts/build-setup.sh >/dev/null 2>&1 || { echo "build-setup.sh fehlgeschlagen"; exit 1; }

# 1. setup.sh ist syntaktisch valide.
if bash -n setup.sh 2>/dev/null; then ok "setup.sh bash -n sauber"; else no "setup.sh Syntaxfehler"; fi
# 2. Lib ist eingebettet (Marker vorhanden).
if grep -q '__BEGIN_scripts_lib_ollama_provision_sh__' setup.sh; then ok "Lib im Bundle"; else no "Lib NICHT gebundlet"; fi
# 3. Testfile ist NICHT eingebettet.
if grep -q 'ollama_provision_test_sh' setup.sh; then no "Testfile fälschlich gebundlet"; else ok "Testfile nicht gebundlet"; fi
# 4. Header sourct die Lib + ruft op_apply.
if grep -q 'scripts/lib/ollama-provision.sh' setup.sh && grep -q 'op_apply' setup.sh; then
  ok "Header sourct Lib + ruft op_apply"
else no "Header-Verdrahtung fehlt"; fi
# 5. Provision-Pfad nutzt das Profil.
if grep -q 'profile local-llm' setup.sh; then ok "Provision nutzt --profile local-llm"; else no "Profil-Aufruf fehlt"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/setup-bundle.test.sh`
Expected: FAIL — Lib weder gebundlet (Test 2) noch verdrahtet (Test 4/5).

- [ ] **Step 3a: build-setup.sh — Lib ins Manifest**

In `scripts/build-setup.sh`, in `build_manifest()` direkt nach `echo "docker-compose.yml"`:

```bash
  # Setup-Hilfsbibliothek (vom Header nach dem Entpacken gesourct).
  # Nur die Lib, NICHT das .test.sh-File.
  echo "scripts/lib/ollama-provision.sh"
```

- [ ] **Step 3b: setup-header.sh.tpl — Docker-Block ersetzen**

In `scripts/setup-header.sh.tpl` den Block (aktuell Zeilen 111-120):

```bash
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
```

ersetzen durch:

```bash
if command -v docker >/dev/null 2>&1; then
  DC="docker compose"
  docker compose version >/dev/null 2>&1 || { command -v docker-compose >/dev/null 2>&1 && DC="docker-compose"; }

  # GPU-Override nur auf Linux mit funktionierender NVIDIA-GPU.
  CF="-f docker-compose.yml"
  if [ "$(uname -s)" = "Linux" ] && command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
    CF="$CF -f docker-compose.gpu.yml"
    echo "  ▸ NVIDIA-GPU erkannt → GPU-Override aktiv"
  fi

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
```

- [ ] **Step 3c: setup.sh regenerieren**

Run: `bash scripts/build-setup.sh`
Expected: `✓ setup.sh (…)` ohne `⚠ Fehlt`-Warnung für die Lib.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/setup-bundle.test.sh`
Expected: PASS — `passed=5 failed=0`.

- [ ] **Step 5: Commit**

```bash
chmod +x scripts/setup-bundle.test.sh
git add scripts/build-setup.sh scripts/setup-header.sh.tpl scripts/setup-bundle.test.sh setup.sh
git commit -m "feat: setup.sh verdrahtet Ollama detect-or-provision (Lib gebundlet + zweiphasiges up)"
```

---

## Task 5: PowerShell-Parität (setup-header.ps1.tpl)

**Files:**
- Modify: `scripts/setup-header.ps1.tpl:126-133` (Docker-Block)
- Regenerate: `setup.ps1`

**Interfaces:**
- Consumes: Compose-Profil `local-llm` (Task 3).
- Produces: `setup.ps1` mit identischer Detect-or-Provision-Logik (inline-PowerShell — eine `.sh` lässt sich aus PowerShell nicht sourcen). Windows = Base/CPU (kein GPU-Override; Docker Desktop löst `host.docker.internal` nativ auf).

**Hinweis Verifikation:** Auf diesem Host ist `pwsh` NICHT installiert; eine automatisierte PowerShell-Ausführung ist hier nicht möglich. Verifikation = (a) Bundle-Strukturprüfung per Bash-Test unten, (b) Code-Review gegen den Bash-Header. Wo `pwsh` verfügbar ist (Windows-Box), zusätzlich `pwsh -NoProfile -Command "& { . ./setup.ps1 }"`-Syntaxcheck — optional, nicht Teil dieses Gates.

- [ ] **Step 1: Write the failing test**

Create `scripts/setup-bundle-ps1.test.sh`:

```bash
#!/usr/bin/env bash
# Strukturprüfung von setup.ps1 (pwsh hier nicht verfügbar → Grep-basiert).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

bash scripts/build-setup.sh >/dev/null 2>&1 || { echo "build-setup.sh fehlgeschlagen"; exit 1; }

# 1. Detect: probt das Host-Ollama auf /api/tags.
if grep -q '11434/api/tags' setup.ps1; then ok "ps1 probt Host-Ollama /api/tags"; else no "ps1 Detect fehlt"; fi
# 2. Adopt: setzt Default-Server auf host.docker.internal.
if grep -q 'host.docker.internal:11434/v1' setup.ps1; then ok "ps1 Adopt-Base-URL gesetzt"; else no "ps1 Adopt-URL fehlt"; fi
# 3. Provision: startet Profil local-llm.
if grep -q 'profile local-llm' setup.ps1; then ok "ps1 Provision via --profile local-llm"; else no "ps1 Profil-Aufruf fehlt"; fi
# 4. Provider-Server-PUT vorhanden.
if grep -q '/api/provider-servers/localhost' setup.ps1; then ok "ps1 PUT provider-servers/localhost"; else no "ps1 PUT fehlt"; fi
# 5. Kein gemma3:4b-Rest mehr.
if grep -q 'gemma3:4b' setup.ps1; then no "ps1 noch gemma3:4b-Referenz"; else ok "ps1 kein gemma3:4b"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/setup-bundle-ps1.test.sh`
Expected: FAIL — ps1-Header hat noch den alten `docker compose up`-Block (keine Detect-Logik).

- [ ] **Step 3a: setup-header.ps1.tpl — Docker-Block ersetzen**

In `scripts/setup-header.ps1.tpl` den Block (aktuell Zeilen 126-133):

```powershell
# Docker
$dockerOk = (Get-Command docker -ErrorAction SilentlyContinue) -ne $null
if ($dockerOk) {
    Write-Host "▸ Baue + starte Docker-Container" -ForegroundColor Cyan
    & docker compose up -d --build 2>&1 | Select-Object -Last 5
} else {
    Write-Host "  ⚠ docker nicht installiert (Docker Desktop für Windows benötigt)" -ForegroundColor Yellow
}
```

ersetzen durch:

```powershell
# Docker — detect-or-provision Ollama (Windows = Base/CPU, kein GPU-Override).
$CascadeUrl     = 'http://localhost:8091'
$HostProbeUrl   = 'http://localhost:11434'
$AdoptBaseUrl   = 'http://host.docker.internal:11434/v1'
$InstackBaseUrl = 'http://ollama:11434/v1'
$OllamaContainer = 'claude-switcher-ollama-1'
$DefaultModels  = @('qwen2.5-coder:7b','qwen2.5:7b','llama3.2:3b')

function Test-HostOllama {
    try { Invoke-RestMethod -Uri "$HostProbeUrl/api/tags" -TimeoutSec 5 | Out-Null; return $true }
    catch { return $false }
}
function Get-OllamaModelIds {
    try {
        $models = Invoke-RestMethod -Uri "$CascadeUrl/api/models" -TimeoutSec 10
        $ids = $models | Where-Object { $_.provider -eq 'ollama' -and $_.modelId } |
               ForEach-Object { $_.modelId } | Sort-Object -Unique
        if ($ids) { return $ids }
    } catch {}
    return $DefaultModels
}
function Set-DefaultServer {
    param([string]$BaseUrl)
    $body = @{ baseUrl = $BaseUrl; isDefault = $true; description = 'Auto: detect-or-provision' } | ConvertTo-Json
    Invoke-RestMethod -Uri "$CascadeUrl/api/provider-servers/localhost" -Method Put `
        -ContentType 'application/json' -Body $body -TimeoutSec 10 | Out-Null
}
function Test-HostHasModel {
    param([string]$Model)
    try {
        $tags = Invoke-RestMethod -Uri "$HostProbeUrl/api/tags" -TimeoutSec 5
        return @($tags.models | ForEach-Object { $_.name }) -contains $Model
    } catch { return $false }
}
function Invoke-PullHost {
    param([string]$Model)
    $body = @{ name = $Model; stream = $false } | ConvertTo-Json
    Invoke-RestMethod -Uri "$HostProbeUrl/api/pull" -Method Post `
        -ContentType 'application/json' -Body $body -TimeoutSec 1800 | Out-Null
}

$dockerOk = (Get-Command docker -ErrorAction SilentlyContinue) -ne $null
if ($dockerOk) {
    Write-Host "▸ Baue + starte Stack (ohne in-stack Ollama)" -ForegroundColor Cyan
    & docker compose -f docker-compose.yml up -d --build 2>&1 | Select-Object -Last 5

    Write-Host "▸ Warte auf llm-cascade (:8091) …" -ForegroundColor Cyan
    for ($i = 0; $i -lt 60; $i++) {
        try { Invoke-RestMethod -Uri "$CascadeUrl/api/health" -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep -Seconds 2 }
    }

    $models = Get-OllamaModelIds
    if (Test-HostOllama) {
        Write-Host "▸ Host-Ollama gefunden → adoptiere (kein eigener Container)" -ForegroundColor Cyan
        Set-DefaultServer $AdoptBaseUrl
        foreach ($m in $models) {
            if (Test-HostHasModel $m) { Write-Host "  ✓ $m bereits auf Host-Ollama" -ForegroundColor Green }
            else { Write-Host "  ▸ pulle $m auf Host-Ollama …"; try { Invoke-PullHost $m } catch { Write-Host "  ⚠ pull $m fehlgeschlagen" -ForegroundColor Yellow } }
        }
    } else {
        Write-Host "▸ Kein Host-Ollama gefunden → starte in-stack Ollama (Profil local-llm)" -ForegroundColor Cyan
        & docker compose -f docker-compose.yml --profile local-llm up -d 2>&1 | Select-Object -Last 3
        Write-Host "  ▸ warte auf Ollama-Container …"
        for ($i = 0; $i -lt 30; $i++) {
            & docker exec $OllamaContainer ollama list *> $null
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep -Seconds 2
        }
        Set-DefaultServer $InstackBaseUrl
        foreach ($m in $models) {
            Write-Host "  ▸ pulle $m in in-stack Ollama …"
            & docker exec $OllamaContainer ollama pull $m
        }
    }
} else {
    Write-Host "  ⚠ docker nicht installiert (Docker Desktop für Windows benötigt)" -ForegroundColor Yellow
}
```

- [ ] **Step 3b: setup.ps1 regenerieren**

Run: `bash scripts/build-setup.sh`
Expected: `✓ setup.ps1 (…)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/setup-bundle-ps1.test.sh`
Expected: PASS — `passed=5 failed=0`.

- [ ] **Step 5: Commit**

```bash
chmod +x scripts/setup-bundle-ps1.test.sh
git add scripts/setup-header.ps1.tpl scripts/setup-bundle-ps1.test.sh setup.ps1
git commit -m "feat: PowerShell-Parität für Ollama detect-or-provision"
```

---

## Manuelle Integrations-Verifikation (nach allen Tasks, durch den User)

Nicht autonom ausführbar (verändert den realen Stack / abhängig von laufendem Host-Ollama). Schritte:

1. **Adopt-Pfad** (vpn-stack/Host-Ollama läuft auf `:11434`):
   - `bash setup.sh test-adopt` → Ausgabe „Host-Ollama gefunden → adoptiere".
   - `docker ps --format '{{.Names}}' | grep ollama` → KEIN `claude-switcher-ollama-1`.
   - `curl :8091/api/provider-servers` → `localhost.baseUrl = http://host.docker.internal:11434/v1, isDefault=true`.
   - `curl :8091/api/models` → ollama-Modelle vorhanden; ein `*-local`-Modell-Test antwortet.
2. **Provision-Pfad** (Host-Ollama gestoppt):
   - `bash setup.sh test-prov` → „Kein Host-Ollama → starte in-stack Ollama".
   - `docker ps` → `claude-switcher-ollama-1` läuft; `docker exec … ollama list` zeigt die 3 Matrix-Modelle.
   - `curl :8091/api/provider-servers` → `localhost.baseUrl = http://ollama:11434/v1`.
3. **Fail-closed:** `curl :8091/api/provider-servers` enthält nur interne URLs (host.docker.internal / ollama), keinen Cloud-Host.
4. **GPU (Linux+NVIDIA):** im Provision-Pfad `docker exec claude-switcher-ollama-1 nvidia-smi` zeigt die GPU.
