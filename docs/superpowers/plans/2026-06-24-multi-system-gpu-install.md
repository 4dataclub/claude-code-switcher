# Multi-System GPU Install Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the switcher install cleanly and fully-automatically across Linux x86_64+NVIDIA, arm Linux, macOS arm64 (incl. Mac Studio M4 Max), and Windows x86_64 — auto-detecting CPU-arch, GPU, and Ollama-mode without any manual pre-start config.

**Architecture:** Three orthogonal auto-detect axes layered as Compose `-f` overrides. (1) **CPU-arch** decides image-vs-source-build: `x86_64` clones llm-cascade and builds via a new `docker-compose.amd64.yml`; `arm64`/`aarch64` uses the published arm64 image. (2) **GPU** is orthogonal "on top": Linux+NVIDIA layers `docker-compose.gpu.yml`; macOS uses native Metal Ollama via adopt; everything else runs CPU with a warning (never a hard fail). (3) **Ollama-mode** (adopt vs provision) is unchanged from the prior feature, except macOS provision prefers starting native Metal Ollama over a CPU container.

**Tech Stack:** Bash (`setup-header.sh.tpl`), PowerShell (`setup-header.ps1.tpl`), Docker Compose v2 override merging, base64 self-extracting bundle (`build-setup.sh`), grep-based bundle tests, `docker compose config` shape tests.

## Global Constraints

- **llm-cascade clone ref:** repo `https://github.com/4dataclub/llm-cascade.git`, ref `main` (overridable via `LLM_CASCADE_REF`, default `main`; repo overridable via `LLM_CASCADE_REPO`). **⚠ FLAG FOR USER REVIEW:** no `0.8.1` git tag exists upstream — the image tag is *not* a git tag. `main` HEAD at planning time = `c4aaf10`. The published `:0.8.1` image is arm64-only, which is why x86_64 must build from source.
- **GPU vendor:** NVIDIA-only is pre-built. `SWITCHER_GPU` env escape-hatch overrides auto-detection (`auto` | `nvidia` | `none`). AMD/ROCm and Intel are YAGNI (not pre-built) but the detection code must leave an explicit, documented seam (an unsupported-vendor `case` branch).
- **CPU fallback = warn + continue, never hard fail.** Absent/unsupported GPU prints a warning and proceeds on CPU.
- **Linux is fully automatic — zero prompts.** The only interactive prompt anywhere is the macOS `brew install ollama` question, and it must be TTY-guarded (`[ -t 0 ]`) so non-interactive Linux/CI runs never block.
- **amd64 lane (`uname -m` = `x86_64`)** = clone + build override; applies to Linux x86_64, Intel Mac, and Windows. **arm lane (`arm64`/`aarch64`)** = published image; applies to Apple Silicon + arm Linux.
- **Clone is idempotent:** skip if `./llm-cascade` already exists. Clone failure on amd64 is fatal (`exit 1`) — the image cannot run there.
- **Reseed/runtime safety unchanged:** never touch `app_settings`/API-keys; never call `/api/switch` during dev.
- Exact env-var names from `scripts/lib/ollama-provision.sh` (do not rename): `OP_HOST_PROBE_URL` (`http://localhost:11434`), `OP_ADOPT_BASEURL` (`http://host.docker.internal:11434/v1`), `OP_INSTACK_BASEURL`, `OP_CASCADE_URL` (`http://localhost:8091`), `OP_OLLAMA_CONTAINER` (`claude-switcher-ollama-1`), `OP_DEFAULT_MODELS`.

---

## File Structure

- `docker-compose.amd64.yml` (**new**) — single-responsibility build override: layers `llm-cascade.build: ./llm-cascade` on top of the base `image:`. Mirrors `docker-compose.gpu.yml`'s minimal-override shape.
- `scripts/setup-header.sh.tpl` (**modify**) — add Arch-Block (clone + amd64 override) and GPU-vendor seam before compose-up; add Mac-GPU-Block in the provision path. macOS + Linux + arm all flow through this one header.
- `scripts/setup-header.ps1.tpl` (**modify**) — add the same amd64 clone + override (Windows is always x86_64). No Mac block, no NVIDIA block (Windows Docker Desktop = Base/CPU, out of GPU scope).
- `scripts/build-setup.sh` (**modify**) — add `docker-compose.amd64.yml` to the bundle manifest so both setup.sh and setup.ps1 ship it.
- `scripts/compose-amd64-shape.test.sh` (**new**) — `docker compose config` shape test for the build override.
- `scripts/setup-bundle.test.sh` (**modify**) — grep assertions for the new Arch/GPU/Mac logic in regenerated `setup.sh`.
- `scripts/setup-bundle-ps1.test.sh` (**modify**) — grep assertions for the amd64 clone+override in regenerated `setup.ps1`.

---

### Task 1: Build-override compose file (`docker-compose.amd64.yml`)

**Files:**
- Create: `docker-compose.amd64.yml`
- Test: `scripts/compose-amd64-shape.test.sh`

**Interfaces:**
- Consumes: base `docker-compose.yml` service name `llm-cascade` with `image: ghcr.io/4dataclub/llm-cascade:0.8.1`.
- Produces: a layerable override that, when merged with `-f docker-compose.yml -f docker-compose.amd64.yml`, makes `llm-cascade` build from `./llm-cascade` instead of pulling the arm64-only image. Consumed by Tasks 3 (sh) and 6 (ps1) via the `CF` override chain.

- [ ] **Step 1: Write the failing shape test**

Create `scripts/compose-amd64-shape.test.sh`:

```bash
#!/usr/bin/env bash
# Shape-Check des amd64-Build-Overrides (mirror von compose-shape.test.sh).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
ok() { echo "  ok: $1"; PASS=$((PASS+1)); }
no() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

# Build-Kontext muss existieren, sonst lehnt `docker compose config` u.U. ab.
mkdir -p llm-cascade
CFG="$(docker compose -f docker-compose.yml -f docker-compose.amd64.yml config 2>/dev/null)"

# 1. Override rendert ohne Fehler.
if [ -n "$CFG" ]; then ok "amd64-Override rendert"; else no "compose config leer/fehlerhaft"; fi
# 2. llm-cascade baut aus ./llm-cascade (Build-Kontext gesetzt).
if printf '%s' "$CFG" | grep -qE 'context:.*/llm-cascade'; then ok "llm-cascade build.context gesetzt"; else no "build.context fehlt"; fi
# 3. Override fasst NUR llm-cascade an (kein ollama/postgres-Build).
if printf '%s' "$CFG" | grep -A30 'ollama:' | grep -q 'build:'; then no "ollama faelschlich mit build:"; else ok "ollama unveraendert (kein build:)"; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/compose-amd64-shape.test.sh`
Expected: FAIL — `docker-compose.amd64.yml` does not exist yet, so `docker compose config` errors and `$CFG` is empty (`compose config leer/fehlerhaft` + `build.context fehlt`).

- [ ] **Step 3: Create the override file**

Create `docker-compose.amd64.yml`:

```yaml
# amd64 (x86_64): das veroeffentlichte llm-cascade-Image ist arm64-only.
# Dieser Override baut llm-cascade stattdessen aus geklontem Source (./llm-cascade).
# Layern:  docker compose -f docker-compose.yml -f docker-compose.amd64.yml up -d --build
services:
  llm-cascade:
    build: ./llm-cascade
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash scripts/compose-amd64-shape.test.sh`
Expected: PASS — `passed=3 failed=0`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.amd64.yml scripts/compose-amd64-shape.test.sh
git commit -m "feat: add amd64 build-override compose file for source-built llm-cascade"
```

---

### Task 2: Bundle the override (`build-setup.sh` manifest)

**Files:**
- Modify: `scripts/build-setup.sh` (manifest function, the root-file `echo` block near `echo "docker-compose.yml"`)
- Test: `scripts/setup-bundle.test.sh` (add one assertion), `scripts/setup-bundle-ps1.test.sh` (add one assertion)

**Interfaces:**
- Consumes: `docker-compose.amd64.yml` from Task 1.
- Produces: the file embedded as a base64 block in both `setup.sh` and `setup.ps1`, and listed in the `__BEGIN_manifest__` block so the self-extractor writes it on disk. Consumed by Tasks 3 & 6 (the extracted scripts reference it via `-f docker-compose.amd64.yml`).

- [ ] **Step 1: Add the failing bundle assertion (sh)**

In `scripts/setup-bundle.test.sh`, add a new numbered check (after the existing checks, before the `echo "passed=..."` line):

```bash
# Manifest/Payload: amd64-Override ist mitgebundelt.
if grep -q '^docker-compose.amd64.yml$' setup.sh; then ok "amd64-Override im Manifest"; else no "amd64-Override fehlt im Bundle"; fi
```

And in `scripts/setup-bundle-ps1.test.sh`, add (before its `echo "passed=..."`):

```bash
# Manifest: amd64-Override ist auch im ps1-Bundle.
if grep -q '^docker-compose.amd64.yml$' setup.ps1; then ok "ps1 amd64-Override im Manifest"; else no "ps1 amd64-Override fehlt"; fi
```

- [ ] **Step 2: Run both tests to verify they fail**

Run: `bash scripts/setup-bundle.test.sh; bash scripts/setup-bundle-ps1.test.sh`
Expected: FAIL — each new assertion reports `amd64-Override fehlt …` because the manifest does not yet list the file. (Both scripts run `bash scripts/build-setup.sh` first, so they regenerate setup.sh/.ps1 against the current manifest.)

- [ ] **Step 3: Add the file to the manifest**

In `scripts/build-setup.sh`, inside `build_manifest()`, add the override right after the existing root-file echo:

```bash
build_manifest() {
  # Root-Files
  echo "docker-compose.yml"
  echo "docker-compose.amd64.yml"
```

- [ ] **Step 4: Run both tests to verify they pass**

Run: `bash scripts/setup-bundle.test.sh; bash scripts/setup-bundle-ps1.test.sh`
Expected: PASS — both report `failed=0`, including the new `amd64-Override im Manifest` / `ps1 amd64-Override im Manifest` lines.

- [ ] **Step 5: Commit**

```bash
git add scripts/build-setup.sh scripts/setup-bundle.test.sh scripts/setup-bundle-ps1.test.sh
git commit -m "feat: bundle docker-compose.amd64.yml into self-extracting setup"
```

---

### Task 3: Arch-Block + GPU-vendor seam in `setup-header.sh.tpl`

**Files:**
- Modify: `scripts/setup-header.sh.tpl` (replace the GPU block at lines ~115-120, before `$DC $CF up -d --build`)
- Test: `scripts/setup-bundle.test.sh` (add assertions)

**Interfaces:**
- Consumes: `docker-compose.amd64.yml` (bundled, Task 2), `docker-compose.gpu.yml` (existing), Global-Constraint env vars (`LLM_CASCADE_REF`, `LLM_CASCADE_REPO`, `SWITCHER_GPU`).
- Produces: a populated `CF` Compose-override chain (`-f docker-compose.yml` plus optional `-f docker-compose.amd64.yml` and `-f docker-compose.gpu.yml`) used by the existing `$DC $CF up -d --build` and the provision `$DC $CF --profile local-llm up -d`. The clone lands source at `./llm-cascade` (relative to the already-`cd`'d `$TARGET`).

- [ ] **Step 1: Add failing bundle assertions (sh)**

In `scripts/setup-bundle.test.sh`, add these checks:

```bash
# Arch-Block: amd64 klont llm-cascade aus Source.
if grep -q 'git clone --depth 1 --branch' setup.sh; then ok "amd64 klont llm-cascade-Source"; else no "amd64-Clone fehlt"; fi
# Arch-Block: amd64 layert den Build-Override.
if grep -q '\-f docker-compose.amd64.yml' setup.sh; then ok "amd64 layert Build-Override"; else no "amd64-Override-Layer fehlt"; fi
# Clone-Fehler auf amd64 ist fatal.
if grep -q 'git clone .* fehlgeschlagen' setup.sh; then ok "amd64-Clone-Fehler bricht ab"; else no "amd64-Clone-Abbruch fehlt"; fi
# GPU-Seam: SWITCHER_GPU-Override vorhanden.
if grep -q 'SWITCHER_GPU' setup.sh; then ok "GPU-Override SWITCHER_GPU"; else no "SWITCHER_GPU-Seam fehlt"; fi
# GPU-Seam: dokumentierter Unsupported-Vendor-Zweig (AMD/Intel YAGNI).
if grep -q 'nicht unterstützt' setup.sh; then ok "GPU unsupported-vendor Seam"; else no "GPU-Vendor-Seam fehlt"; fi
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/setup-bundle.test.sh`
Expected: FAIL — all five new assertions fail; the template still has only the old NVIDIA-only block (`-f docker-compose.gpu.yml` exists, but `git clone`, `-f docker-compose.amd64.yml`, `SWITCHER_GPU`, and the unsupported-vendor text do not).

- [ ] **Step 3: Replace the GPU block with Arch-Block + GPU-vendor seam**

In `scripts/setup-header.sh.tpl`, replace these lines:

```bash
  # GPU-Override nur auf Linux mit funktionierender NVIDIA-GPU.
  CF="-f docker-compose.yml"
  if [ "$(uname -s)" = "Linux" ] && command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
    CF="$CF -f docker-compose.gpu.yml"
    echo "  ▸ NVIDIA-GPU erkannt → GPU-Override aktiv"
  fi
```

with:

```bash
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
```

- [ ] **Step 4: Run bundle test + syntax check**

Run: `bash scripts/setup-bundle.test.sh && bash scripts/build-setup.sh >/dev/null && bash -n setup.sh && echo "bash -n OK"`
Expected: PASS — all assertions `ok`, `failed=0`, and `bash -n OK` (regenerated setup.sh is syntactically valid).

- [ ] **Step 5: Commit**

```bash
git add scripts/setup-header.sh.tpl scripts/setup-bundle.test.sh setup.sh
git commit -m "feat: arch-aware llm-cascade source-build + SWITCHER_GPU vendor seam (bash)"
```

---

### Task 4: Mac-GPU-Block in `setup-header.sh.tpl` provision path

**Files:**
- Modify: `scripts/setup-header.sh.tpl` (the `if [ "$MODE" = provision ]; then …` block, lines ~135-146)
- Test: `scripts/setup-bundle.test.sh` (add assertions)

**Interfaces:**
- Consumes: `MODE` from `op_detect_mode` (already computed above this block), `OP_HOST_PROBE_URL`, `OP_OLLAMA_CONTAINER`, `op_apply` (from `scripts/lib/ollama-provision.sh`).
- Produces: on macOS, when no host Ollama is running, it prefers native Metal Ollama (start it, then flip `MODE=adopt`) over the CPU container; falls through to the existing in-stack CPU container otherwise. `op_apply "$MODE"` is called exactly once afterward (unchanged).

- [ ] **Step 1: Add failing bundle assertions (sh)**

In `scripts/setup-bundle.test.sh`, add:

```bash
# Mac-GPU: provision bevorzugt natives Metal-Ollama vor CPU-Container.
if grep -q 'macOS.*natives Ollama' setup.sh; then ok "Mac startet natives Ollama (Metal)"; else no "Mac-Metal-Block fehlt"; fi
# Mac-GPU: TTY-geschuetzte brew-Abfrage (Linux/CI blockt nie).
if grep -q 'brew install ollama' setup.sh && grep -q '\[ -t 0 \]' setup.sh; then ok "Mac brew-Prompt TTY-guarded"; else no "Mac brew-Prompt/TTY-Guard fehlt"; fi
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/setup-bundle.test.sh`
Expected: FAIL — `Mac-Metal-Block fehlt` and `Mac brew-Prompt/TTY-Guard fehlt`; the provision path is still Linux-only in-stack.

- [ ] **Step 3: Insert the Mac-GPU-Block**

In `scripts/setup-header.sh.tpl`, replace this block:

```bash
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
```

with:

```bash
    MODE=$(op_detect_mode)
    if [ "$MODE" = provision ] && [ "$(uname -s)" = "Darwin" ]; then
      # macOS: GPU (Metal) ist aus Containern nicht erreichbar → natives Ollama bevorzugen.
      if command -v ollama >/dev/null 2>&1; then
        echo "▸ macOS: starte natives Ollama (Metal-GPU) statt CPU-Container"
        ollama serve >/dev/null 2>&1 &
      elif [ -t 0 ] && command -v brew >/dev/null 2>&1; then
        printf "  ? Ollama nicht installiert. Für GPU (Metal) per Homebrew installieren? [y/N] "
        read -r ans
        case "$ans" in
          y|Y) brew install ollama && ollama serve >/dev/null 2>&1 & ;;
          *)   echo "  ⚠ übersprungen → CPU-Container-Fallback" ;;
        esac
      fi
      if command -v ollama >/dev/null 2>&1; then
        echo "  ▸ warte auf natives Ollama (:11434) …"
        for _ in $(seq 1 30); do
          curl -fsS --max-time 2 "${OP_HOST_PROBE_URL}/api/tags" >/dev/null 2>&1 && { MODE=adopt; break; }
          sleep 1
        done
      fi
    fi
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
```

- [ ] **Step 4: Run bundle test + syntax check**

Run: `bash scripts/setup-bundle.test.sh && bash scripts/build-setup.sh >/dev/null && bash -n setup.sh && echo "bash -n OK"`
Expected: PASS — new assertions `ok`, `failed=0`, `bash -n OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/setup-header.sh.tpl scripts/setup-bundle.test.sh setup.sh
git commit -m "feat: macOS provision prefers native Metal Ollama with TTY-guarded brew prompt"
```

---

### Task 5: amd64 clone + override in `setup-header.ps1.tpl` (Windows)

**Files:**
- Modify: `scripts/setup-header.ps1.tpl` (the docker block, around `& docker compose -f docker-compose.yml up -d --build`)
- Test: `scripts/setup-bundle-ps1.test.sh` (add assertions)

**Interfaces:**
- Consumes: `docker-compose.amd64.yml` (bundled, Task 2). Windows is always x86_64 → always the amd64 lane.
- Produces: a `$ComposeFiles` argument array (`-f docker-compose.yml -f docker-compose.amd64.yml`) used by every `docker compose` invocation in the ps1 docker block. No Mac block, no NVIDIA block (Windows Docker Desktop = Base/CPU, out of GPU scope).

- [ ] **Step 1: Add failing bundle assertions (ps1)**

In `scripts/setup-bundle-ps1.test.sh`, add:

```bash
# Windows amd64: klont llm-cascade aus Source.
if grep -q 'git clone --depth 1 --branch' setup.ps1; then ok "ps1 klont llm-cascade-Source"; else no "ps1-Clone fehlt"; fi
# Windows amd64: layert den Build-Override.
if grep -q 'docker-compose.amd64.yml' setup.ps1; then ok "ps1 layert amd64-Override"; else no "ps1-amd64-Override fehlt"; fi
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/setup-bundle-ps1.test.sh`
Expected: FAIL — `ps1-Clone fehlt` and `ps1-amd64-Override fehlt`; the ps1 docker block still uses only `-f docker-compose.yml`.

- [ ] **Step 3: Add clone + override to the ps1 docker block**

In `scripts/setup-header.ps1.tpl`, replace:

```powershell
$dockerOk = (Get-Command docker -ErrorAction SilentlyContinue) -ne $null
if ($dockerOk) {
    Write-Host "▸ Baue + starte Stack (ohne in-stack Ollama)" -ForegroundColor Cyan
    & docker compose -f docker-compose.yml up -d --build 2>&1 | Select-Object -Last 5
```

with:

```powershell
$dockerOk = (Get-Command docker -ErrorAction SilentlyContinue) -ne $null
if ($dockerOk) {
    # Windows ist immer x86_64 → llm-cascade-Image (arm64-only) nicht nutzbar, also aus Source bauen.
    $LlmCascadeRef  = if ($env:LLM_CASCADE_REF)  { $env:LLM_CASCADE_REF }  else { 'main' }
    $LlmCascadeRepo = if ($env:LLM_CASCADE_REPO) { $env:LLM_CASCADE_REPO } else { 'https://github.com/4dataclub/llm-cascade.git' }
    if (-not (Test-Path 'llm-cascade')) {
        Write-Host "  ▸ amd64 (Windows) → klone llm-cascade ($LlmCascadeRef) für Source-Build" -ForegroundColor Cyan
        & git clone --depth 1 --branch $LlmCascadeRef $LlmCascadeRepo llm-cascade
        if ($LASTEXITCODE -ne 0) {
            Write-Host "✗ git clone llm-cascade fehlgeschlagen — Image auf amd64 nicht nutzbar. Abbruch." -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "  ✓ llm-cascade-Source bereits vorhanden → kein erneuter Clone" -ForegroundColor Green
    }
    $ComposeFiles = @('-f','docker-compose.yml','-f','docker-compose.amd64.yml')
    Write-Host "▸ Baue + starte Stack (ohne in-stack Ollama)" -ForegroundColor Cyan
    & docker compose @ComposeFiles up -d --build 2>&1 | Select-Object -Last 5
```

Then update the provision-path compose call later in the same block. Replace:

```powershell
        & docker compose -f docker-compose.yml --profile local-llm up -d 2>&1 | Select-Object -Last 3
```

with:

```powershell
        & docker compose @ComposeFiles --profile local-llm up -d 2>&1 | Select-Object -Last 3
```

- [ ] **Step 4: Run bundle test to verify it passes**

Run: `bash scripts/setup-bundle-ps1.test.sh`
Expected: PASS — `ps1 klont llm-cascade-Source` and `ps1 layert amd64-Override` both `ok`, `failed=0`. (pwsh is unavailable here; grep-based structural check only, consistent with the existing ps1 test.)

- [ ] **Step 5: Commit**

```bash
git add scripts/setup-header.ps1.tpl scripts/setup-bundle-ps1.test.sh setup.ps1
git commit -m "feat: arch-aware llm-cascade source-build for Windows amd64 (PowerShell)"
```

---

## Verification (whole-branch)

After all tasks:

- **All shape + bundle tests green:** `bash scripts/compose-amd64-shape.test.sh && bash scripts/compose-shape.test.sh && bash scripts/setup-bundle.test.sh && bash scripts/setup-bundle-ps1.test.sh`
- **Regenerated scripts valid:** `bash scripts/build-setup.sh >/dev/null && bash -n setup.sh && echo OK` (ps1 syntax not checkable without pwsh — note in ledger).
- **Compose override merges cleanly:** `mkdir -p /tmp/cc && docker compose -f docker-compose.yml -f docker-compose.amd64.yml -f docker-compose.gpu.yml config >/dev/null && echo "3-way merge OK"` (amd64 + gpu stack on Linux x86_64+NVIDIA, the named primary platform).
- **Axis independence sanity:** the arm lane (no amd64 override) still renders: `docker compose -f docker-compose.yml config >/dev/null`.
- **No prompts on Linux:** confirm the only `read -r` in `setup-header.sh.tpl` is inside the `[ "$(uname -s)" = "Darwin" ]` branch and TTY-guarded.

## Plattform-Status (after this branch)

| Platform | Arch lane | GPU | Ollama | Prompts |
|---|---|---|---|---|
| Linux x86_64 + NVIDIA | amd64 (build) | gpu.yml | adopt/provision | none |
| Linux x86_64, no NVIDIA | amd64 (build) | CPU (warn) | adopt/provision | none |
| arm Linux | arm (image) | CPU (warn) | adopt/provision | none |
| macOS arm64 (incl. Mac Studio M4 Max) | arm (image) | native Metal Ollama | adopt / native-provision | brew prompt only |
| Intel Mac | amd64 (build) | native Metal Ollama | adopt / native-provision | brew prompt only |
| Windows x86_64 | amd64 (build) | CPU (Base) | adopt/provision | none |

## Nicht im Scope (per spec)

- Multi-arch upstream llm-cascade image (would remove the amd64 clone entirely) — upstream concern.
- AMD/ROCm + Intel GPU pre-built support — YAGNI; only the `SWITCHER_GPU` seam + unsupported-vendor branch are provided.
- Windows GPU passthrough hardening — out of scope; Windows stays Base/CPU.

## ⚠ Decision flagged for user review

The clone targets **`main`** (overridable via `LLM_CASCADE_REF`) because **no `0.8.1` git tag exists** on the llm-cascade repo — the Docker image tag is not a git tag. If you want amd64 builds pinned to the same source as the `:0.8.1` image, we need the exact commit/tag from whoever publishes that image; until then `main` is the only reproducible ref. Confirm `main` is acceptable or supply a pin.
