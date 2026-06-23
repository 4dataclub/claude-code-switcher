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
| **orchestrator** *(Hirn, gepinnt)* | Opus 4.8 → Sonnet 4.6 | *(leer — editierbar)* | qwen2.5-coder:7b (aus bis Ollama) |
| **implement** | DeepSeek V3.1 + Gemini Flash | Qwen3-Coder + Qwen3-Next 80B | qwen2.5-coder:7b |
| **review** | GPT-4o-mini | GPT-OSS 120B | qwen2.5:7b |
| **research** | Gemini Pro (OR + nativ) | *(Gemini-MCP)* | qwen2.5:7b *(intern/Intranet, offline, nichts raus)* |
| **dispatch** | Gemini Flash-Lite | Llama 3.3 + GPT-OSS 20B | llama3.2:3b |

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
Beschreibung oder ein Modell, folgt das Routing, **kein Code-Eingriff**. `research` nutzt in
**cloud/free** Gemini-MCP/Grounding (öffentliches Web); im **Local-Pool** läuft `research-local`
(lokales Modell, offline) und darf lokale Docs + interne/VPN-erreichbare Ressourcen verarbeiten —
**nichts verlässt das interne Netz** (kein öffentliches Web, kein Cloud); reine Public-Web-Research
verweigert der Agent. Delegation-Fehler:
**cloud/free = fail-open** (Opus macht's selbst), **local = fail-closed** (Stopp, nie Cloud).

## Ohne Supermodell — der klassische Lauf (Gegenstück)

Häufigste Verwechslung, deshalb direkt daneben: **Supermodell AUS** nutzt dieselben
Cascade-Bereiche, aber komplett anders. Die Bereiche sind dann **Kosten-Tier-Lanes**
(`cloud` / `free-only`), keine Rollen — jede eine eigene Failover-Kette mit eigenem Cooldown.

**Wie es in cloud läuft:** Claude Code hängt an **genau einem** Modell und steigt bei Quota-Tod
die Kette runter — jeder Schritt = Wrapper-Restart (`--resume` hält den Kontext):

```
 Supermodell AUS · Pool cloud · Auto-Failover
 ─────────────────────────────────────────────
 Stufe 0  Anthropic (Opus/Sonnet, OAuth)   ← Start, im Abo
    │  Quota leer → runterschalten (Restart)
    ▼
 Stufe 1  Gemini 2.5 Pro
    │  leer / Cooldown ▼
 Stufe 2  Gemini 2.5 Flash
    │  ▼
 Stufe 3  DeepSeek free   ← aus free-only, der Notnagel

 ⟲ alle 30 min: Anthropic frei? → Auto-Promote zurück auf Stufe 0
```

- **Manuell:** du pinnst ein Modell, es bleibt — kein Auto-Switch.
- **`preferred-category`:** eine Lane festpinnen, oder leer = *Semantic Routing* (Cascade wählt
  die Lane nach Zweck). Trotzdem ist **immer nur eine** live.
- Mehrere Bereiche = **Reserve-Lanes + Tiefen-Stufen** derselben einen Kette, **nicht** Mitspieler.
  Genau eins arbeitet zu jedem Zeitpunkt an allem; Wechsel = Provider umschalten = Restart.

**Der Unterschied in einem Bild:**

```
 OHNE Supermodell = Reservetank        MIT Supermodell = Fließband
 ─────────────────────────────         ─────────────────────────────
 ein Modell macht alles                Opus dirigiert via @supermodel:
 leer → nächstes (Restart)             ├─► implement-cloud  ┐
 nacheinander, sequenziell             ├─► review-cloud     │ gleichzeitig
 (Bereiche = Notnägel)                 └─► dispatch-cloud   ┘ (Seitenkanal :8091)
```

Kurz: **ohne** Supermodell sind die Bereiche ein **Reservetank-System** (einer leer → nächster,
sequenziell, ein Modell macht alles). **Mit** Supermodell werden dieselben Bereiche ein
**gleichzeitiges Team** an einem Stück Arbeit — siehe Routing-Sektion oben.

## 🔒 Lokal = fail-closed (die wichtigste Garantie)

Im **Local-Pool** verlässt **nichts** automatisch das **interne Netz** — auch nicht „um die
Funktion am Leben zu halten". Die Grenze ist das interne Perimeter, nicht der einzelne Rechner:
lokale Modelle + lokale Docs + interne/VPN-erreichbare Ressourcen sind erlaubt, **öffentliches
Web und Cloud-LLM nicht**. Local läuft dabei garantiert **ohne Internet** (kein Cloud-Eintrag in
einer `*-local`-Zelle). **Lieber STOPP als Leak.**

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

- **Diese Kette IST die `orchestrator-{pool}`-Zelle** (Rollen-Panel/Matrix) — editierbar wie jede
  andere Rolle: Modelle hinzufügen/entfernen/umsortieren ändert das Opus-Failover direkt
  (Reihenfolge der Zelle = Failover-Reihenfolge, `orderIdx`). `setMode` liest sie beim Scharfstellen,
  der Cooldown-AutoPromote (30 min) holt Opus zurück. Default **cloud**: Sonnet 4.6 → Gemini 2.5 Flash;
  **free**: Hermes-3 405B. Leere Zelle → internes Sicherheitsnetz (Sonnet → Gemini-Pro → Flash), damit
  Opus nie ganz ohne Fallback ist. (Ollama/lokal in der Zelle wird übersprungen — kein Cloud-Leak.)
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
research → cloud/free via Gemini-MCP, local via `research-local` intern/offline). Die kanonische Version liegt im Repo unter
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

## Hardware-Stufen (Local-Pool)

Autoritatives Config-Framework (A/B/C). Der **Werks-Default ist die Stufe „jetzt" (8 GB,
unter A)** — das verdrahtet der Seeder (`DataInitializer.seedDefaultChain`), damit jeder
Kollege out-of-the-box dieselbe local-Matrix bekommt. **B+ = Mac Studio M4 Max 64 GB (eBay)**,
unsere geplante Upgrade-Stufe (zwischen B und C); dort greift das XDA-Hybrid-Muster.

| Rolle | **jetzt · 8 GB** (RTX 4060 Laptop, Werks-Default) | **A · Hybrid-Einstieg · 16 GB** | **B · echtes Coding · 32–48 GB** | **B+ · Mac Studio M4 Max 64 GB (eBay)** | **C · Voll-lokal · 128 GB+** |
|---|---|---|---|---|---|
| **Orchestrator** | `qwen2.5-coder:7b` (aus bis Ollama) | Opus (Cloud) — lokal zu schwach | Opus (hybrid, empf.) *oder* `qwq:32b` lokal | Opus Cloud (Plan/Synthese, XDA-Hybrid) *oder* `qwq:32b`/`qwen2.5:32b` lokal | `llama-3.3:70b` → `qwen2.5:72b` |
| **implement** | `qwen2.5-coder:7b` | `qwen2.5-coder:7b` | `qwen2.5-coder:32b` | `qwen2.5-coder:32b` | `qwen2.5-coder:32b` |
| **review** | `qwen2.5:7b` | `qwen2.5-coder:7b` → `gemma3:4b` | `qwen2.5-coder:32b` | `qwen2.5-coder:32b` | `qwen2.5-coder:32b` |
| **dispatch** | `gemma3:4b` | `gemma3:4b` | `qwen2.5:7b` → `qwen2.5-coder:32b` | `qwen2.5:7b` | `gemma3:4b` → `qwen2.5:7b` |
| **research** | `qwen2.5:7b` (intern/offline, nichts raus) | Gemini-MCP (Cloud, hybrid) | Gemini-MCP (Cloud, hybrid) | Gemini-MCP (Cloud, hybrid) *oder* lokale Docs | nur lokal/intern — nichts raus |
| **Qualität** | Einstieg/begrenzt — nur **EIN** 7b resident (~4.9 GB), 7b+4b ko-resident, zwei 7b ✗ | leicht/begrenzt — Bulk lokal, Frontier=Opus | gut — ~80 % lokal, Orchestrierung grenzwertig-lokal/hybrid | sehr gut — 32b-Orch + 32b-Coder ko-resident im Unified Memory | sehr gut (nur nicht ganz Opus) — nichts verlässt die Infra |

Der **Werks-Default (8 GB)** liegt bewusst **unter A**: bei 8 GB VRAM passt nur ein heißes
7b-Q4 (~4.9 GB) resident; 7b + kleines Dispatch-Modell (3–4b) ko-residieren (~6.9 GB), zwei
7b (9.8 GB) **nicht** → Swap/Thrashing. Darum ein gepinntes `qwen2.5-coder:7b` für
orchestrator/implement/review + `gemma3:4b` für dispatch. Alle `*-local` starten
`enabled=false` bis `ollama pull` lief.

> **B+ (Mac Studio M4 Max 64 GB, eBay) — Upgrade-Pfad, NUR auf explizite Anweisung umstellen.**
> 64 GB Unified liegt zwischen B (32–48) und C (128+) → trägt einen 32b-Orchestrator **plus**
> 32b-Coder gleichzeitig im Memory. Hier greift das **XDA-Muster** (cloud-Opus nur für
> Plan/Synthese, lokale Großmodelle für Bulk) — genau das, was unser Supermodell automatisiert.
> Umstellung: nur die Modell-Einträge der `*-local`-Kategorien tauschen (Bereichs-Struktur
> bleibt fest). Vor Tausch `ollama list` + `nvidia-smi`/Activity-Monitor prüfen (muss real ins
> Memory passen). Nicht eigenmächtig switchen.

> **Ehrlich:** Opus-4.8-Frontier-Niveau ist voll-lokal **nicht ganz** erreichbar. C (bzw. ein
> Studio Ultra mit 256–512 GB für 235B/671B-MoE-Orchestratoren) schließt die Lücke am weitesten —
> MoE ist der Apple-Silicon-Sweet-Spot (riesige Kapazität, nur Bruchteil aktiv → brauchbarer
> Speed). **Der eigentliche Mac-Engpass ist nicht RAM, sondern Prompt-Processing /
> Time-to-First-Token** bei langen Kontexten (zäher als NVIDIA); Token-Generierung ist ok. Willst
> du das letzte Quäntchen Opus-Hirn → bewusst die **Cloud-Lane**. Bester P/L bleibt **Hybrid**
> (Opus Cloud nur für Plan/Synthese + lokale Kollegen).

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
