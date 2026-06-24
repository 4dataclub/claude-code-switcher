# Multi-System GPU-Install — Design

**Datum:** 2026-06-24
**Status:** Entwurf (zur User-Review)
**Branch (geplant):** Aufsatz auf `feat/ollama-detect-or-provision` (eigener Commit/PR)

## Ziel

Eine einzige `setup.sh` / `setup.ps1` soll den Switcher **ohne Hand-Config auf
mehreren Systemen sauber installierbar** machen — verbindlich getestet auf
**Linux x86_64 + NVIDIA** und **macOS arm64** —, dabei die lokale Inferenz nach
Möglichkeit **über die GPU** laufen lassen. Der heute vorhandene Detect-or-Provision
für Ollama bleibt; ergänzt werden eine **CPU-Arch-Achse** (llm-cascade-Image ist
arm64-only → x86_64 baut aus Source) und ein **Mac-GPU-Pfad** (native Metal-Ollama
statt Container).

## Ausgangslage (verifiziert, 2026-06-24)

- **llm-cascade `:0.8.1` ist arm64-only.** Auf x86_64 startet das Image nicht; die
  Source wird vom Setup **nicht** gebündelt (Manifest = backend/frontend/router/
  wrapper/lib, kein llm-cascade). Heute kein Arch-Handling im
  `setup-header.sh.tpl` (`grep` auf `uname -m` leer). Auf Linux-x86_64 deshalb
  manueller amd64-Workaround nötig (`build: ./llm-cascade`, brain-Note).
- **GPU-Override existiert:** `docker-compose.gpu.yml` reserviert eine **NVIDIA**-GPU
  (`devices: nvidia`). Aus einem Docker-Container auf dem Mac ist die GPU **nicht**
  erreichbar (Docker Desktop = Linux-VM, kein Metal-Passthrough) — der Override ist
  also Linux/NVIDIA-only.
- **Detect-or-Provision (Ollama) ist gebaut** (Branch `feat/ollama-detect-or-provision`):
  `scripts/lib/ollama-provision.sh` (`op_detect_mode`, `op_apply`, …), Profil
  `local-llm` für den in-stack Ollama, `extra_hosts: host.docker.internal:host-gateway`
  am llm-cascade. Adopt-Flow am 2026-06-24 live + reversibel gegen den laufenden
  Cascade bewiesen (`adopt` → Default-Server `host.docker.internal:11434/v1`,
  Modelle present → 0 Pulls).
- **Mac nutzt seine GPU nur via nativem Host-Ollama** (Ollama-App / `brew install
  ollama` → Metal). Den adoptiert der Switcher via `host.docker.internal:11434`.

## Architektur — drei orthogonale Auto-Detect-Achsen

Eine `setup.sh`/`.ps1`, keine Hand-Config. Vor `docker compose up` werden drei Achsen
unabhängig erkannt und als komponierbare `-f`-Overrides bzw. Klon-/Start-Schritte
aufgelöst. Die Achsen sind multiplikativ kombinierbar.

| Achse | Erkennung | Linux x86_64 | macOS arm64 |
|---|---|---|---|
| **CPU-Arch** (llm-cascade) | `uname -m` / `uname -s` | x86_64 → `git clone` 0.8.1 + `-f docker-compose.amd64.yml` (`build:`) | arm64 → Base `image:` (kein Klon) |
| **GPU** (in-stack Ollama) | Linux + `nvidia-smi` ok | NVIDIA → `-f docker-compose.gpu.yml`; sonst Warnung + CPU | nie GPU-Override (Container erreicht Metal nicht) |
| **Ollama** (adopt/provision) | Probe `:11434/api/tags` | da → adopt; sonst provision (in-stack Container) | da → adopt (native Metal-GPU); sonst Mac-Provision-Pfad (siehe unten) |

Beispiele der resultierenden `-f`-Kette (`CF`):
- Linux + NVIDIA + kein Host-Ollama: `-f docker-compose.yml -f docker-compose.amd64.yml -f docker-compose.gpu.yml`, dann provision.
- Linux + NVIDIA + Host-Ollama: `-f docker-compose.yml -f docker-compose.amd64.yml`, dann adopt.
- Mac + Host-Ollama: `-f docker-compose.yml` (Base, `image:`), dann adopt.

## Architektur-Entscheidung: Arch via Compose-Override

`docker-compose.amd64.yml` (neu) setzt **nur** für `llm-cascade` `build: ./llm-cascade`.
Die Base behält `image: …:0.8.1` (passt für arm64/Mac). Auf x86_64 hängt das Setup den
Override per `-f` dazu — **exakt das Muster von `docker-compose.gpu.yml`**. Kein
In-place-`sed`, kein dupliziertes Compose, der Base bleibt für Mac unverändert.
(Compose mergt `image` + `build` → es **baut** und taggt als Image-Name; kein Pull.)

## Komponenten / Dateien

- **`docker-compose.amd64.yml`** *(neu)* — Override: `llm-cascade.build: ./llm-cascade`,
  sonst nichts.
- **`scripts/setup-header.sh.tpl`** — neuer **Arch-Block** vor dem Compose-Up
  (x86_64 & nicht-Darwin → `git clone --branch <0.8.1-ref> --depth 1
  https://github.com/4dataclub/llm-cascade.git ./llm-cascade`, nur falls Ordner
  fehlt → idempotent; `CF="$CF -f docker-compose.amd64.yml"`). GPU-Block bleibt;
  Warn-Text bei fehlender NVIDIA präzisieren. Neuer **Mac-GPU-Block** im
  Provision-Zweig (siehe Flow Schritt 5b).
- **`scripts/setup-header.ps1.tpl`** — Windows ist x86_64: gleicher Klon + amd64-Override.
  Kein Mac-Block. GPU vorerst wie Linux (NVIDIA via WSL2-Backend) behandeln.
- **`scripts/build-setup.sh`** — `docker-compose.amd64.yml` ins Manifest aufnehmen
  (wird mitentpackt). llm-cascade-Source **nicht** bündeln (Klon zur Laufzeit).
- **`docker-compose.yml`** — unverändert aus dem ollama-Branch (`image:`,
  `extra_hosts`, ollama hinter `local-llm`-Profil).

**Kein Java-Change.** Alle nötige Runtime-Mechanik liegt in llm-cascade 0.8.1.

## Deploy-Flow (Sequenz in setup.sh, nach dem Entpacken)

1. **Achsen auflösen:** `CF="-f docker-compose.yml"`.
   Arch-Check → ggf. `git clone` llm-cascade@`<0.8.1-ref>` nach `./llm-cascade`
   (Skip wenn vorhanden) + `CF+=" -f docker-compose.amd64.yml"`.
   GPU-Check (Linux + `nvidia-smi` ok) → `CF+=" -f docker-compose.gpu.yml"`.
2. **Stack hoch (ohne in-stack Ollama):** `$DC $CF up -d --build`. x86_64 baut
   llm-cascade aus `./llm-cascade`; Mac zieht das arm64-Image.
3. **`:8091/api/health` pollen** (Timeout 120 s).
4. **lib sourcen**, `MODE=$(op_detect_mode)`.
5. **Pfad ausführen:**
   - **adopt:** nichts starten.
   - **provision (Linux):** `$DC $CF --profile local-llm up -d`, auf Ollama-Container
     warten.
   - **provision (Mac) — GPU-Eskalation:**
     a. `command -v ollama` vorhanden, aber nicht am Laufen → `ollama serve`
        (Hintergrund), auf `:11434` warten → **MODE=adopt** (native Metal-GPU).
     b. nicht installiert **und TTY vorhanden** → Abfrage „Per `brew install ollama`
        installieren, um die Mac-GPU zu nutzen? [j/N]". **ja** → `brew install ollama`
        + `ollama serve` + warten → **MODE=adopt**. **nein** → in-stack CPU-Container
        (`--profile local-llm up -d`) + Warnung.
     c. nicht installiert **und kein TTY** (nicht-interaktiv) → keine Abfrage,
        in-stack CPU-Container + Warnung.
     d. „ja", aber kein `brew` → klare Meldung + CPU-Container-Fallback.
6. **`op_apply "$MODE"`** — Default-Server setzen + Modelle sicherstellen.
7. **Transparenz-Ausgabe:** erkannte Arch / GPU / Modus.

## Fehlerbehandlung / Edge-Cases

- **Kein NVIDIA (Linux) / Mac-CPU-Container** → Warnung „läuft auf CPU, GPU empfohlen",
  **kein Abbruch** (bewusste Vorgabe: robust statt strikt).
- **`git clone` schlägt fehl** (kein Netz/Git) auf x86_64 → **laut abbrechen** mit
  klarer Meldung (ohne Source kann llm-cascade auf x86_64 nicht laufen — kein stiller
  Fehlstart mit arm64-Image).
- **`./llm-cascade` existiert schon** → Klon überspringen (idempotent); **kein**
  Auto-`git pull` (kein eigenmächtiges Update eines fremden Checkouts).
- **Mac: `ollama serve` startet nicht / `:11434` kommt nicht hoch** → Warnung +
  CPU-Container-Fallback (kein Hängen).
- **Health-Poll Timeout** → Warnung, weiter zu detect (`op_apply` ist tolerant:
  Cascade down → Fallback-Modellliste in der lib).
- **`<0.8.1-ref>` existiert nicht** als Tag → in der Plan-Phase verifizieren; sonst auf
  passenden Commit pinnen. Arch-Override und Image-Tag müssen denselben Stand meinen.

## Tests / Verifikation

- **Compose-Shape (bash, wie Task 3):** `docker-compose.amd64.yml` setzt `build` auf
  llm-cascade und nichts anderes; `compose -f base -f amd64 config` valide; Base allein
  behält `image:`.
- **Header-Bundle (wie Task 4/5):** generiertes `setup.sh`/`.ps1` grept den Arch-Block
  (uname-Gate, `git clone`, amd64-Override-Add, idempotenter Skip) und den Mac-GPU-Block
  (`command -v ollama`, `ollama serve`, brew-Abfrage, TTY-Guard).
- **Manifest:** `docker-compose.amd64.yml` gebündelt; llm-cascade-Source **nicht**.
- **Achsen-Matrix (Trockenlauf, kein echtes Deploy):** erkannte `CF`-Kette + Modus für
  die Fälle Linux±GPU × adopt/provision und Mac × adopt/provision gegen Erwartung.
- **Live x86_64 (manuell):** Frischlauf in Wegwerf-Ordner auf dieser Kiste →
  llm-cascade baut amd64, adopt greift, `*-local`-Test antwortet.
- **Live Mac (manuell, Mac-Kollege):** mit nativem Ollama → adopt + GPU; ohne →
  Install-Abfrage → nach „ja" GPU, nach „nein" CPU-Container.

## Nicht im Scope (separat)

- **Multi-Arch-Image bei 4dataclub** (CI baut `:0.8.1` für arm64+amd64). Sobald es
  existiert, fällt der x86_64-Klon+Build-Zweig ersatzlos weg (`image:` überall). Bleibt
  als offener Upstream-Punkt; dieses Design ist die in-Repo-Brücke bis dahin.
- **Windows-GPU** (NVIDIA via WSL2) jenseits des „wie Linux"-Defaults.
