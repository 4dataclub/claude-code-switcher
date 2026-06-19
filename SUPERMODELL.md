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
| **orchestrator** *(Hirn, gepinnt)* | Opus 4.8 → Sonnet 4.6 | *(leer — editierbar)* | qwen2.5:14b (aus bis Ollama) |
| **implement** | DeepSeek V3.1 + Gemini Flash | Qwen3-Coder + Qwen3-Next 80B | qwen2.5-coder:7b |
| **review** | GPT-4o-mini | GPT-OSS 120B | qwen2.5:7b |
| **research** | Gemini Pro (OR + nativ) | *(Gemini-MCP)* | *— Web=Cloud* |
| **dispatch** | Gemini Flash-Lite | Llama 3.3 + GPT-OSS 20B | gemma3:4b |

Jede Zelle ist eine **Failover-Kette** (mehrere Modelle, Cooldown). Fällt eins aus, rückt
das nächste nach — der Plan läuft weiter. Modelle/Reihenfolge jederzeit in der UI änderbar
(Rollen + Pools sind **Kategorien = Daten**, voll CRUD-bar, kein Code-Eingriff).

> **Sonderrolle `orchestrator`:** Anders als die 4 Worker ist der Orchestrator **Claude Code
> selbst** (der laufende Main-Loop) — **kein** `@supermodel`-Delegationsziel (der Agent delegiert
> nur implement/review/research/dispatch). Die `orchestrator-{pool}`-Zelle *pinnt* das Hirn:
> **cloud/free** → Opus, mit `orchestrator-cloud` (Sonnet 4.6) + Failover-Kette (Sonnet → Gemini
> Pro → Flash) via `pinOrchestratorForPool`. **local** → fail-closed, **nie Cloud**: die
> `orchestrator-local`-Zelle ist, wo du dein lokales Hirn wählst; ohne aktives lokales Modell →
> `localOrchestratorPending` (gelbe Warnung). Das Live-Routing des Main-Loops aufs lokale Modell
> (ccr → Ollama) ist **Phase E**.

## Routing — woher weiß er, welche Aufgabe in welchen Bereich?

**Keine fest verdrahtete Regel** — die Zuordnung ist eine *semantische* Entscheidung anhand der
**Beschreibungen** (Single Source of Truth). Drei Schichten, ein Prinzip (wie in EduPro):

1. **Semantic — Opus wählt das `kind`.** Opus liest die Teilaufgabe + die Rollen-Beschreibungen
   und ordnet sie der best-passenden Rolle zu. Kein Keyword-Match: „implementier", „bau",
   „fix den Endpoint" landen alle bei `implement`. Opus gibt das `kind` an `@supermodel` weiter
   (oder der Agent leitet es ab — *„or you infer it"*).
2. **Lane — der aktive Pool.** `@supermodel` liest `/api/supermodel` → Pool (= Spalte der Matrix)
   und baut die Compound-Kategorie `{kind}-{pool}` (z.B. `implement-cloud`).
3. **Cooldown/Failover — die Cascade wählt das Modell.** `:8091` nimmt das live Modell aus der
   Failover-Kette der Kategorie; fällt eins aus, rückt das nächste nach.

| `kind` | Opus wählt es bei … | cloud-Modell |
|---|---|---|
| `implement` | bulk code, backend, boilerplate, CRUD | DeepSeek V3.1 → Gemini Flash |
| `review` | Korrektheit, Sicherheit, Tests | GPT-4o-mini |
| `research` | Web/Google, große externe Docs | **Gemini-MCP** (kein Cascade-Modell) |
| `dispatch` | Commit-Msgs, Summaries, Triviales | Gemini Flash-Lite |
| `orchestrator` | Planung / Architektur | Opus selbst (nicht delegiert) |

```
 Aufgabe ("implementier den /login-Endpoint")
        │
        ▼   Schicht 1 — SEMANTIC (Opus)
 ┌──────────────────────────────────────────────┐
 │ Opus liest Aufgabe + Rollen-Beschreibungen   │
 │  → wählt kind = implement                     │
 └───────────────────┬──────────────────────────┘
        ▼   Schicht 2 — LANE (@supermodel)
 ┌──────────────────────────────────────────────┐
 │ aktiver Pool = Spalte  →  {kind}-{pool}       │
 │  = implement-cloud                            │
 └───────────────────┬──────────────────────────┘
        ▼   Schicht 3 — COOLDOWN/FAILOVER (Cascade :8091)
 ┌──────────────────────────────────────────────┐
 │ live Modell der Kategorie                     │
 │  implement-cloud → DeepSeek V3.1 (→ Flash)    │
 └───────────────────┬──────────────────────────┘
        ▼
 Ergebnis → zurück an Opus → prüft + integriert (finale Synthese)
```

**Daraus folgt:** Rollen + Pools sind `Kategorien = Daten` (voll CRUD-bar) — änderst du eine
Beschreibung oder ein Modell, folgt das Routing, **kein Code-Eingriff**. `research` verlässt die
Cascade (Gemini-MCP/Grounding; im Local-Pool verweigert = Web=Cloud, fail-closed). Delegation-Fehler:
**cloud/free = fail-open** (Opus macht's selbst), **local = fail-closed** (Stopp, nie Cloud).

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

## Was passiert, wenn Opus selbst am Limit ist?

Es gibt **zwei** Failover-Ebenen — eine läuft schon immer, eine fängt das Opus-Limit ab:

- **Ebene ① Rollen-Failover (immer automatisch):** Innerhalb jeder Rolle (`implement-cloud` …)
  rückt die Cascade bei Cooldown/Limit automatisch aufs nächste Modell ① → ② vor. Braucht
  keinen Schalter — der häufige Fall (ein Kollegen-Modell limitiert) ist damit abgedeckt.
- **Ebene ② Orchestrator-Failover (Opus am Limit):** Opus plant nur + synthetisiert → verbrennt
  sein Limit langsam, aber wenn die rollierende ~5-Std-Sperre doch greift, schaltet der Switcher
  (in **cloud/free**) automatisch weiter — **pool-bewusst**:

```
 Opus 4.8 (Orchestrator) ── Limit erreicht ──┐
                                             ▼
  ① Sonnet 4.6  (Anthropic-DIREKT, ohne ccr) ──► Supermodell bleibt INTAKT:
     └─ Subagents + @supermodel laufen weiter     nur ein schwächerer Planer
                                             │
           Sonnet auch leer / Anthropic ganz aus
                                             ▼
  ② Cloud-Lane via ccr  (Gemini → OpenRouter) ─► DEGRADIERT: ein Modell,
     └─ keine Subagents/MCP, aber LÄUFT WEITER     kein Rollen-Plan (statt 5h Stillstand)
                                             │
           Anthropic-Fenster resettet (~5 h)
                                             ▼
  Auto-Promote → zurück auf Opus + volles Supermodell   (alle 30 min geprüft)
```

- **Warum Sonnet zuerst?** Sonnet läuft **Anthropic-direkt (kein ccr)** → die nativen Claude-Code-
  Subagents + die `@supermodel`-Delegation bleiben funktionsfähig. Erst wenn *alle* Anthropic-
  Modelle aus sind, fällt es auf die Cloud-Lane (ccr = degradiert, aber kein Stillstand).
- **Pool = Lokal → STOPP, kein Failover.** Sonnet/Gemini sind Cloud — im Local-Pool wäre das ein
  Leak. Stattdessen Banner/Notiz, du entscheidest bewusst (siehe fail-closed oben).
- **Ehrlich:** Opus & Sonnet teilen sich grob das Anthropic-Budget, aber Opus verbrennt es schneller
  → Sonnet hat meist noch Spielraum, wenn Opus gekappt ist. **Garantiert** ist nur die Cloud-Lane
  (nicht-Anthropic). Und: das Failover feuert nur, wenn der Wrapper **`claude-auto`** das Limit per
  stderr-Parsing erkennt (fragil — keine offizielle Pre-Quota-API für Max/Pro). Nacktes `claude` → kein Auto-Switch.

## Zusammenspiel mit superpowers (Claude-Code-Arbeitsmodus)

[superpowers](https://github.com/obra/superpowers) ist die Standard-**Arbeitsmethodik** von
Claude Code (brainstorm→plan→TDD→verify→review→finish). Sie und der Supermodell-Modus
**kollidieren nicht — sie sitzen auf verschiedenen Schichten:**

- **superpowers = Playbook** — *welche* Schritte, *welcher* Qualitäts-Standard.
- **Supermodell = Staffing** — *wer* macht jeden Schritt, auf *welchem* Modell, zu welchen Kosten.

Der Orchestrator führt das superpowers-Playbook aus und verteilt die Fleißarbeit über `@supermodel`:

```
 superpowers = WAS:   brainstorm → plan → TDD → verify → review → finish
 ─────────────────────────────────────────────────────────────────────
 Supermodell = WER:
   ┌──────────────────────────┬───────────────────────────────────┐
   │ Orchestrator selbst       │ @supermodel → cascade → billiger   │
   │ (Opus; Local-Pool:        │ Worker (implement/review/research) │
   │  ein lokales Modell)      │                                    │
   ├──────────────────────────┼───────────────────────────────────┤
   │ brainstorm (nur unklar)   │ implement / TDD-Code               │
   │ Plan / Architektur        │ review · research · boilerplate    │
   │ finale Verify + Synthese  │                                    │
   └───────────┬──────────────┴────────────────┬──────────────────┘
               │      Ergebnisse ◄──────────────┘
               ▼
   Orchestrator: sammelt → prüft gg. Plan → integriert → verify-before-done
               ▼  GATES: kein Deploy/Merge/Publish ohne GO · Local = fail-closed
```

**Vorrang-Regel (Tie-Breaker):** Supermodell **AN** → Delegation läuft **nur** über `@supermodel`
(nicht über superpowers' eigene `dispatching-parallel-agents` / `subagent-driven-development`);
superpowers regelt weiter das *Wie* (Plan/TDD/Verify/Review). Supermodell **AUS** → superpowers'
Subagents sind der Delegationsweg. Ohne die Regel würde der Orchestrator doppelt delegieren.

**Gewinn:** Die Kombi senkt genau die Opus-Quota-Last, die superpowers *allein* auf Opus
erzeugen würde — Opus denkt nur, die Worker machen die Masse billig.

### Sonderfall: beide voll lokal (Local-Pool + Supermodell + superpowers)

Läuft mechanisch komplett, **100 % privat & fail-closed** — mit drei Konsequenzen:

1. **Der Orchestrator ist ein lokales Modell, nicht Opus** (Opus = Cloud = Leak). superpowers'
   Playbook läuft also im Kopf eines lokalen Modells → Qualität = hardware-gebunden (siehe
   Hardware-Stufen unten; Opus-Niveau ist lokal nicht erreichbar).
2. **superpowers wird zum Sicherheitsnetz, nicht zum Ballast:** weil lokale Worker schwächer
   sind, fangen TDD + `verification-before-completion` + Review deren Fehler ab — die
   Qualitäts-Gates machen lokale Ergebnisse überhaupt erst brauchbar.
3. **Native Claude-Subagents können nur Anthropic/Cloud sein** → im Local-Pool läuft Delegation
   ausschließlich über `@supermodel` → cascade → `{rolle}-local`. Der Tie-Breaker greift hier
   doppelt. Fällt ein lokales Modell aus → **STOPP**, nie Cloud.

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

| | **A · Einstieg (16 GB)** | **B · echtes Coding (32–48 GB)** | **C · Voll-lokal (128 GB+)** | **D · Mac Studio Ultra (256–512 GB)** |
|---|---|---|---|---|
| Orchestrator | kleines Modell (schwach) | qwq:32b / qwen2.5:32b | llama-3.3:70b / qwen2.5:72b | großes MoE: Qwen3-235B-A22B (~130 GB) / DeepSeek-V3.1 671B (~380 GB @4-bit, 37B aktiv) |
| implement | qwen2.5-coder:7b | qwen2.5-coder:32b | qwen2.5-coder:32b | Qwen3-Coder / qwen2.5-coder:32b — **parallel resident** zum Orchestrator |
| Qualität | leicht/begrenzt | gut | sehr gut (nur nicht ganz Opus) | **nahe Frontier** (MoE-Brain + Coder + Reviewer gleichzeitig im RAM, kein Nachladen) |

> **Ehrlich:** Opus-4.8-Frontier-Niveau ist voll-lokal **nicht ganz** erreichbar — aber **D
> (Studio Ultra) schließt die Lücke am weitesten**: ein 235B/671B-MoE-Orchestrator + dedizierte
> Coder/Reviewer liegen gleichzeitig im Unified Memory. MoE ist der Apple-Silicon-Sweet-Spot
> (riesige Kapazität, nur Bruchteil aktiv → brauchbarer Speed). **Der eigentliche Mac-Engpass ist
> nicht RAM, sondern Prompt-Processing / Time-to-First-Token** bei langen Kontexten (zäher als
> NVIDIA); Token-Generierung ist ok. Willst du das letzte Quäntchen Opus-Hirn → bewusst die
> **Cloud-Lane**. Bester P/L bleibt **Hybrid** (Opus Cloud nur für Plan/Synthese + lokale Kollegen).

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
