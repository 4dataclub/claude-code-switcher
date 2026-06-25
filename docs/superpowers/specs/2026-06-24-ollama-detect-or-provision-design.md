# Ollama Detect-or-Provision — Design

**Datum:** 2026-06-24
**Status:** Entwurf (zur User-Review)
**Branch (geplant):** `feat/ollama-detect-or-provision` (neu — PR #85 bleibt unberührt)

## Ziel

Beim Switcher-Setup soll der lokale Inferenz-Server **erkannt-oder-bereitgestellt**
werden: Läuft auf dem Host bereits ein interner Ollama (z.B. der vpn-stack /
Midoco-GPU-Ollama mit den Modellen), wird er **adoptiert** — kein eigener Container.
Läuft keiner, zieht das Setup den **in-stack Ollama** hoch und pullt die benötigten
Modelle. Vorbild: `MidocoVPNConnector/install.sh` (Port-Probe) und
`MidocoLLMHistoryAnalyse/setup_midoco.sh` (Modell-Presence-Check + Pull).

## Ausgangslage (verifiziert am laufenden Stack, 2026-06-24)

llm-cascade ist auf **0.8.1** und implementiert die Server-Wahl + Auto-Pull bereits
serverseitig. Der Feature-Bau setzt **auf dieser Runtime-Mechanik auf** statt eine
parallele Detection ins Setup-Skript zu bauen (vom User so entschieden).

Belegte Fakten aus dem Source:

- **`ProviderServerResolver.resolveEffectiveBaseUrl`** (Präzedenz): explizit
  benannter Server → direkte `providerBaseUrl` → **Default-`ProviderServer`
  (`isDefault=true`) nur für `provider=ollama`** → Bean-Default. Alle fünf
  `*-local`-Modelle haben leeren `provider_server_name` ⇒ sie folgen **dem Default-Server**.
- **`DefaultProviderServerInit`** seedet `localhost = ${ollama.base-url:-http://ollama:11434/v1}`
  **nur wenn die Tabelle leer ist** (kein Überschreiben). Der Live-Wert
  `http://172.22.0.1:11434/v1` ("Host-Ollama via docker gateway") ist also eine
  **manuelle Adopt-Änderung**, nicht der Seed. Seed = in-stack `ollama:11434`.
- **`OllamaProvisioner.pullModelAsync`** pullt ein Modell via `POST /api/pull`
  fire-and-forget auf der Server-Base-URL. Es **startet keinen Daemon** — es
  *setzt voraus*, dass dort schon ein Ollama läuft, und pullt nur das Modell.
- **Schreib-Endpoint:** `PUT /api/provider-servers/{name}` (llm-cascade :8091)
  upsertet `baseUrl` / `description` / `isDefault`; `isDefault=true` entfernt
  andere Defaults (genau 1 Default). **Das ist der Hebel für Adopt vs. Provision:**
  ein einziger Call repointet sämtliche local-Modelle.
- **Modell-Quelle (autoritativ, ungefiltert):** `GET :8091/api/models` →
  `provider=="ollama"` → distinct `modelId`. Aktuell:
  `qwen2.5-coder:7b`, `qwen2.5:7b`, `llama3.2:3b`. (Das pool-gefilterte
  `:2000/api/ai-models` taugt **nicht** — zeigt zur Setup-Zeit nur `cloud`.)
- **GPU-Override existiert:** `docker-compose.gpu.yml` (NVIDIA-Reservierung,
  nur Linux; macOS = Base/CPU).
- **in-stack `ollama`-Service** (Base-Compose): default-an, **kein veröffentlichter
  Host-Port** (nur Service-DNS `ollama:11434`), Entrypoint pullt aktuell `gemma3:4b`
  (falsch — soll die Matrix-Modelle ziehen).

## Architektur-Entscheidung

**„Adopt vs. Provision" = welche `baseUrl` der Default-`provider_server` `localhost`
hält + ob der in-stack `ollama`-Container läuft.** Beide Ziele sind für llm-cascade
erreichbare URLs:

| Pfad | Default-Server `baseUrl` | in-stack `ollama` | Modelle |
|---|---|---|---|
| **Adopt** (Host-Ollama da) | `http://<host-gateway>:11434/v1` | **aus** | per `/api/pull` auf Host-Ollama nachziehen |
| **Provision** (keiner da) | `http://ollama:11434/v1` (= Seed) | **an** (GPU-Override je Host) | in-stack pullen |

Die Detection bleibt zur **Setup-Zeit**, das Routing/Pull-Verhalten lebt in 0.8.1.

## Entscheidungen (mit User bestätigt)

1. **Modell-Liste:** aus Cascade-Config ableiten (`GET :8091/api/models`,
   `provider=ollama`, distinct `modelId`). Fallback-Default beim allerersten Lauf
   (DB noch nicht geseedet / :8091 nicht erreichbar): die drei Werks-Modelle
   `qwen2.5-coder:7b` / `qwen2.5:7b` / `llama3.2:3b`.
2. **Adopt + fehlende Modelle dort pullen** (Host-Ollama-Volume mutieren ist ok;
   fail-closed bleibt gewahrt, da intern).
3. **Per-Host-GPU:** Linux → `docker-compose.gpu.yml` (NVIDIA); macOS → Base/CPU.
4. **Neuer Branch** `feat/ollama-detect-or-provision`.

## Ablauf (Sequenz)

Die Detect-or-Provision-Logik läuft im `scripts/setup-header.sh.tpl`
(+ `.ps1.tpl`), **nach** `docker compose up` (sobald :8091 `/api/health` ok):

1. **Stack hochfahren.** GPU-Override nur auf Linux-mit-NVIDIA dazunehmen
   (`-f docker-compose.yml -f docker-compose.gpu.yml`). macOS/sonst: Base.
2. **`:8091/api/health` pollen** (Timeout, z.B. 60 s) bis llm-cascade lebt
   (DB geseedet, `localhost`-Server existiert).
3. **Host-Ollama erkennen:** `curl -fsS http://localhost:11434/api/tags`
   (Setup läuft auf dem Host; was hier antwortet, erreicht llm-cascade via
   Gateway). Erreichbar ⇒ **Adopt**, sonst ⇒ **Provision**.
4. **Modell-Liste holen:** `GET :8091/api/models` → distinct ollama-`modelId`
   (Fallback: die drei Werks-Modelle).
5. **Adopt-Pfad:**
   a. `PUT :8091/api/provider-servers/localhost`
      `{baseUrl:"http://<host-gateway>:11434/v1", isDefault:true}`.
   b. Für jedes fehlende Modell: `POST http://localhost:11434/api/pull
      {name:"<modelId>", stream:false}` (oder 0.8.1-Auto-Pull greifen lassen).
   c. in-stack `ollama` **nicht** starten (Profil aus).
6. **Provision-Pfad:**
   a. in-stack `ollama`-Service läuft (Base-Compose).
   b. `PUT :8091/api/provider-servers/localhost`
      `{baseUrl:"http://ollama:11434/v1", isDefault:true}` (idempotent = Seed).
   c. in-stack Entrypoint pullt die Matrix-Modelle (statt `gemma3:4b`).
7. **Transparenz:** kurze Setup-Ausgabe „Adoptiere Host-Ollama unter X" bzw.
   „Starte in-stack Ollama + pulle <modelle>".

## Offene Detail-Punkte (für die Plan-Phase)

- **Host-Gateway-Adresse:** Live ist `172.22.0.1` (Bridge-Gateway-IP, kann pro
  Docker-Netz variieren). Portabler: `host.docker.internal` — auf Linux braucht
  llm-cascade dafür `extra_hosts: ["host.docker.internal:host-gateway"]` im
  Compose. Im Plan festlegen: feste IP-Erkennung (`docker network inspect` /
  `ip route`) vs. `host.docker.internal`-Eintrag.
- **in-stack `ollama` ein-/ausschaltbar:** sauber via `profiles:` (z.B.
  `local-llm`) — Provision-Pfad gibt `--profile local-llm`, Adopt-Pfad lässt es
  weg. (Früher war der Service genau so hinter einem Profil; jetzt default-an.)
- **Entrypoint-Modelle dynamisch:** der in-stack Entrypoint kennt die Matrix
  nicht. Optionen: (a) feste 3 Werks-Modelle im Entrypoint, (b) Pull komplett
  dem Setup-Skript / 0.8.1-Provisioner überlassen und der Container macht nur
  `ollama serve`. (b) ist DRY-er und folgt „Modell-Liste aus Config".
- **`build-setup.sh` Regenerierung:** nach Template-Edits `setup.sh`/`.ps1` neu
  bauen (generierte Artefakte nicht handeditieren).
- **PowerShell-Parität:** dieselbe Logik in `setup-header.ps1.tpl`.

## Kritische Dateien

- `scripts/setup-header.sh.tpl` — Detect-or-Provision (Bash/Linux/macOS/WSL).
- `scripts/setup-header.ps1.tpl` — dito (Windows).
- `docker-compose.yml` — in-stack `ollama` hinter Profil; Entrypoint von
  `gemma3:4b` auf „nur serve" bzw. Matrix-Modelle; ggf. `host.docker.internal`.
- `docker-compose.gpu.yml` — unverändert (nur Linux-NVIDIA-Override; schon da).
- `scripts/build-setup.sh` / `.ps1` — regeneriert `setup.sh`/`.ps1` (kein Code-Change nötig).

**Kein Java-Change nötig:** `ProviderServerResolver`, `OllamaProvisioner`,
`PUT /api/provider-servers` und `GET /api/models` existieren bereits in 0.8.1.

## Verifikation

- **Adopt:** Host-Ollama läuft → Setup → `:8091/api/provider-servers` zeigt
  `localhost.baseUrl = http://<gateway>:11434/v1, isDefault=true`; in-stack
  `ollama`-Container **nicht** gestartet; `:8091/api/models` ollama-Modelle
  vorhanden; ein `*-local`-`/api/models/{id}/test` antwortet.
- **Provision:** kein Host-Ollama → Setup → `localhost.baseUrl =
  http://ollama:11434/v1`; in-stack Container läuft; Matrix-Modelle gepullt;
  `*-local`-Test antwortet.
- **Fail-closed unberührt:** beide Ziele sind intern (Host-Gateway bzw.
  Service-DNS), kein Cloud-Eintrag — local läuft ohne öffentliches Internet.
- **Idempotenz:** zweiter Setup-Lauf ändert nichts (PUT idempotent, Pull
  springt nur bei fehlendem Modell an).
- **GPU:** auf Linux-NVIDIA mit Override rechnet der in-stack Ollama auf GPU
  (`nvidia-smi` im Container); macOS-Base startet ohne GPU-Reservation sauber.
