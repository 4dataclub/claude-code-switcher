# Supermodell-Modus — Claude Code mit günstigen Kollegen-Modellen

> **Self-contained.** Diese Datei trägt das ganze Wissen im Repo — auf dem Ziel-Rechner
> gibt es kein Brain/Plan/Memory. Wer das liest (Mensch oder Claude), versteht + bedient
> den Supermodell-Modus ohne Vorwissen.

## Worum geht's? (Laien-Erklärung)

Claude Code läuft sonst für **jede** Aufgabe auf dem teuersten Modell (Opus). Das sprengt
schnell das Nutzungslimit (die rollierende ~5-Stunden-Sperre) — für „jeden Furz".

**Idee:** Opus bleibt der **Chef** (plant + prüft am Ende), delegiert die **Fleißarbeit**
aber an günstigere/lokale Modelle. So bleibt Opus geschont, und sensible Daten können
**lokal** bleiben (nichts geht raus).

```
   Du gibst eine Aufgabe
        │
        ▼
   ┌──────────────────────────────────────────────┐
   │  ① Opus PLANT  (teuer, aber nur kurz)         │
   └───────────────┬──────────────────────────────┘
                   │ delegiert Schritt für Schritt via @supermodel
        ┌──────────┼───────────┬────────────┐
        ▼          ▼           ▼            ▼
   implement    review     research     dispatch     ← Rollen
   (Bulk-Code) (Tests)   (Web/Docs)   (Triviales)
        │          │           │            │
        └──────────┴─────┬─────┴────────────┘
                         ▼
   ┌──────────────────────────────────────────────┐
   │  llm-cascade :8091 — wählt das Modell der     │
   │  Rolle IM AKTIVEN POOL, Failover bei Ausfall  │
   └───────────────┬──────────────────────────────┘
                   ▼
   ┌──────────────────────────────────────────────┐
   │  ③ Opus prüft + integriert + verbessert       │
   │     („was hätte ich besser gemacht?")          │
   └──────────────────────────────────────────────┘
```

**Beispiel:** „Implementiere Endpoint X, reviewe ihn, fasse zusammen." → Opus plant →
`implement` (DeepSeek) schreibt den Code → `review` (GPT) prüft → `dispatch` macht die
Commit-Message → **Opus liest alles, integriert, poliert** und liefert das Endergebnis.

## Die 2 Achsen

Gesteuert in der ki-models-ui (`http://localhost:2000`):

```
  Pool        ( ) Cloud      ( ) Free       (•) Lokal      ← WELCHE Modelle + Privacy
  Supermodell ( ) Aus        (•) An                        ← Opus orchestriert oben drauf?
```

| Achse | Werte | Bedeutung |
|---|---|---|
| **Pool** (`_switcher.pool`) | `cloud` / `free` / `local` | Welches Modellset + Datenschutz-Lane. |
| **Supermodell** (`_switcher.supermodel`) | `an` / `aus` | An = Opus delegiert an Rollen. Aus = klassisches Single-Model-/Auto-Failover-Routing im Pool. |

- **Cloud** — DeepSeek / GPT / Gemini. Beste Qualität, kostet Geld.
- **Free** — OpenRouter `:free`. €0, stark rate-limited, **nicht privat** (Daten ggf. fürs Training).
- **Lokal** — Ollama / eigene Infra. Privat, hardware-begrenzt, **fail-closed**.

**Supermodell AN** blendet die 4 **Rollen pro Pool** ein (Compound-Kategorien
`{rolle}-{pool}`, z. B. `implement-cloud`). **AUS** verschwinden sie wieder — dann ist es
normales Pool-Routing.

### Die 2D-Matrix (Rolle × Pool)

|  | **cloud** | **free** | **local** |
|---|---|---|---|
| **implement** | DeepSeek V3.1 + Gemini Flash | Qwen3-Coder + Qwen3-Next 80B | qwen2.5-coder:7b |
| **review** | GPT-4o-mini | GPT-OSS 120B | qwen2.5:7b |
| **research** | Gemini Pro (OR + nativ) | *(Gemini-MCP)* | *— Web=Cloud* |
| **dispatch** | Gemini Flash-Lite | Llama 3.3 + GPT-OSS 20B | gemma3:4b |

Jede Zelle ist eine **Failover-Kette** (mehrere Modelle, Cooldown). Fällt eins aus, rückt
das nächste nach — der Plan läuft weiter. Modelle/Reihenfolge jederzeit in der UI änderbar
(Rollen + Pools sind **Kategorien = Daten**, voll CRUD-bar, kein Code-Eingriff).

## 🔒 Lokal = fail-closed (die wichtigste Garantie)

Im **Local-Pool** verlässt **nichts** automatisch den Rechner — auch nicht „um die Funktion
am Leben zu halten". **Lieber STOPP als Leak.**

- Der **Orchestrator selbst ist lokal** (NICHT Opus — Opus = Anthropic = Cloud würde die
  Planung rausgeben). Das Backend pinnt im Local-Pool **niemals** Opus/Anthropic.
- Solange kein lokales Modell aktiv ist → `localOrchestratorPending` (Warnung im UI), aber
  **kein Cloud-Ausweich**.
- Der `@supermodel`-Agent delegiert im Local-Pool nur an `{rolle}-local`; schlägt das fehl,
  meldet er `Delegation nicht möglich (local fail-closed)` — **nie** ein Retry gegen Cloud.
- **Härteste Stufe (empfohlen für DSGVO):** lokale Modelle ohne Internet-Egress (Firewall /
  Container ohne Netz) → kann physisch nichts raus, selbst bei Bug.
- **Cloud/Free = fail-open:** schlägt ein Kollege fehl, macht Opus es selbst (Daten sind eh Cloud).

**Beweis-Test:** Local aktiv + Ollama killen → **0** OpenRouter/Gemini/Anthropic-Calls (Stats prüfen).

## Setup

### 1. Cascade-Matrix (Modelle)

Frisch (`docker compose down -v && up --build`): Die Matrix wird von
`java-backend/.../DataInitializer.java` **automatisch geseedet** (Rolle×Pool, `local`
disabled bis die Ollama-Modelle da sind). Keine manuellen Schritte.

API-Keys eintragen (ki-models-ui → „API-Keys" oder per curl):
```bash
curl -X POST localhost:2000/api/api-keys/setting/openrouterApiKey -H 'Content-Type: application/json' -d '{"value":"sk-or-..."}'
curl -X POST localhost:2000/api/api-keys/setting/geminiApiKey     -H 'Content-Type: application/json' -d '{"value":"AIza..."}'
```

### 2. `~/.claude` (maschinen-spezifisch → copy-paste)

**a) Gemini-MCP** (research-Rolle):
```bash
npm i -g @google/gemini-cli          # OAuth liegt danach in ~/.gemini/
claude mcp add -s user gemini-cli -- npx -y gemini-mcp-tool
claude mcp list                      # 'gemini-cli' muss auftauchen
```

**b) `@supermodel`-Agent** — `~/.claude/agents/supermodel.md` (ein Haiku-Relay, liest den
aktiven Pool via `curl :2000/api/supermodel` und delegiert an `category={kind}-{pool}`;
research → Gemini-MCP; local fail-closed). Die kanonische Version liegt im Repo unter
[`agents/supermodel.md`](agents/supermodel.md) — nach `~/.claude/agents/` kopieren.

**c) Policy-Block** in `~/.claude/CLAUDE.md` (nach dem Switcher-Block):
```markdown
# Supermodell-Modus — Rollen-Delegation, semantic Routing, finale Synthese
Aktiv wenn Switcher im Supermodell-Modus (`curl :2000/api/supermodel` → enabled:true).
Opus besitzt den PLAN, cascade die MODELL-WAHL. Delegiere kontextbasiert an EINEN
@supermodel-Agent mit der Art (kind): Planung/Architektur → Opus selbst; Bulk-Impl./
Backend → implement; Review/Tests → review; Web/Google → research; Triviales → dispatch.
Der Agent bildet category={kind}-{pool} aus dem aktiven Pool.
LOKAL FAIL-CLOSED: bei lokalem Ausfall NIEMALS auf Cloud/Opus ausweichen — STOPP + fragen.
FINALE SYNTHESE (Pflicht): nach Delegation übernimmt Opus IMMER wieder — sammelt Outputs,
prüft gegen Plan, integriert, verbessert; nie delegieren-und-vergessen.
```

### 3. Modus setzen

```bash
# Pool + Supermodell in einem Call (beide optional):
curl -X POST localhost:2000/api/mode -H 'Content-Type: application/json' -d '{"pool":"cloud","supermodel":true}'
curl localhost:2000/api/supermodel    # {enabled, pool, localOrchestratorPending}
```
Oder einfach die Toggles in der UI (`localhost:2000`).

## Plattform-Unterschiede (lokale Modelle)

- **macOS (Apple Silicon):** Docker reicht **kein** Metal/GPU durch → **natives** Ollama
  (`brew install ollama`) + provider-server `host` → `http://host.docker.internal:11434/v1`.
- **Ubuntu + NVIDIA:** das vorhandene `docker-compose.gpu.yml`-Override nutzen
  (`docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`) → der Compose-
  Ollama-Service läuft **auf GPU**, der geseedete `localhost`-provider-server
  (`http://ollama:11434/v1`) funktioniert direkt. Kein `host`-Workaround nötig.
- Modelle ziehen: `ollama pull qwen2.5-coder:7b qwen2.5:7b gemma3:4b`, dann die
  `*-local`-Zellen in der UI aktivieren + provider-server zuweisen.

## Hardware-Stufen (Local-Pool, alle 100 % lokal — Orchestrator inkl., nie Opus)

| | **A · Einstieg (16 GB)** | **B · echtes Coding (32–48 GB)** | **C · Voll-lokal (128 GB+)** |
|---|---|---|---|
| Orchestrator | kleines Modell (schwach) | qwq:32b / qwen2.5:32b | llama-3.3:70b / qwen2.5:72b |
| implement | qwen2.5-coder:7b | qwen2.5-coder:32b | qwen2.5-coder:32b |
| Qualität | leicht/begrenzt | gut | sehr gut (nur nicht ganz Opus) |

> **Ehrlich:** Opus-4.8-Frontier-Niveau ist voll-lokal auf einer Workstation **nicht**
> erreichbar (C kommt am nächsten). Willst du Opus' Hirn → bewusst die **Cloud-Lane** (das
> ist nicht „Local"). Bester P/L = **Hybrid** (Opus Cloud nur für Plan/Synthese + lokale Kollegen).

## Verteiltes Setup (ein Rechner = Server, anderer = Consumer)

Server exponiert Ollama im Netz (`OLLAMA_HOST=0.0.0.0`); der Consumer registriert einen
**provider-server → `http://<server-ip>:11434/v1`**. fail-closed/Privacy gilt weiter (eigene
Infra = privat). Die provider-servers-Abstraktion trägt den verteilten Fall ohne Code-Änderung.

## Verifikation

```bash
# Rollen-Routing (Cloud):
curl -X POST :8091/api/generate -d '{"category":"implement-cloud","prompt":"ping"}'   # → DeepSeek/Gemini
curl -X POST :8091/api/generate -d '{"category":"review-cloud","prompt":"ping"}'      # → GPT-4o-mini
# Fail-closed (Local): Local aktiv + Ollama killen → 0 Cloud-Calls (Stats prüfen).
```

## Architektur-Hinweis: EduPro bleibt unberührt

Switcher + EduPro teilen die **Software** (llm-cascade-Image), laufen aber als **getrennte
Instanzen mit eigener DB**. EduPro kennt die Rollen-Kategorien nicht → verhält sich exakt
wie bisher. Alle Erweiterungen hier sind additiv.

## Quellen

- Video: „Baue dein KI-Supermodell mit Claude Code" (Julian Karge / KIFlowState),
  `youtube.com/watch?v=5jfWQ3Y9qRg`.
- XDA: „Claude Code with Opus 4.8 is expensive but I made it efficient with my local AI workflow".
- Repos: `claude-switcher`, `llm-cascade`, `ki-models-ui`.
