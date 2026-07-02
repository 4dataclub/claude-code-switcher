# Architektur — Tool-Calling-Pfade (Switcher / CCR / llm-cascade)

> Stand: 2026-06-30. Ergebnis einer Debugging-Session zum Befund
> "Supermodell-Delegation laeuft nur mit Opus, nicht mit free/local".
> Quelle der Fakten: Live-Tests gegen die laufenden Container (siehe "Evidenz").

---

## 1. Kernbefund in einem Satz

**Claude Code ist ein Tool-Calling-Agent. Die llm-cascade ist ein
Text-Completion-Dienst (Text rein, Text raus). Sobald der Agent durch die
cascade geroutet wird, werden die Tool-Calls abgeschnitten — und damit jede
Agentik (Subagenten spawnen, Dateien lesen/schreiben, Bash, Delegation).**

Opus "funktioniert" heute nicht, weil Opus besonders ist, sondern weil der
Wrapper fuer Provider `anthropic` `ANTHROPIC_BASE_URL` *unsetzt* und Opus damit
die cascade **umgeht** (direkt an die Anthropic-API via OAuth). Jeder andere
Pfad (free, local, sowie ein Nicht-Anthropic-Orchestrator) laeuft durch das
Text-Rohr und verliert die Tools.

Das war **nicht** das Konzept — gewollt war provider-agnostisch.

---

## 2. Die zwei Pfade (Ist-Zustand)

```
 CLOUD + Anthropic   wrapper: unset ANTHROPIC_BASE_URL
   Claude Code ───────────────────────────────► api.anthropic.com (Opus)
                    direkt, OAuth, cascade UMGANGEN
   => tool_use funktioniert  => Orchestrierung + Delegation moeglich   ✓

 FREE / LOCAL / Nicht-Anthropic   wrapper: export ANTHROPIC_BASE_URL=:3456
   Claude Code ──► CCR :3456 ──► llm-cascade ──► Modell
                                  │
                                  ✂ extractPrompt(): nur letzter USER-TEXT
                                  ✂ Response: nur content, nie tool_calls
   => Tools gestrippt  => reiner Chatbot, keine Agentik                 ✗
```

Die wahre Trennlinie heisst **nicht** "cloud vs free vs local", sondern:

| | Anthropic-DIREKT (BASE_URL unset) | Router-PFAD (CCR -> cascade) |
|---|---|---|
| Pools | cloud + Claude | free (gemini/openrouter), local (ollama) |
| Tool-Calling | ✓ nativ | ✗ gestrippt |

---

## 3. Der Denkfehler in der Verdrahtung

Die cascade wurde als Text-Completion-Fallback gebaut (Beleg: TODOS-Beispiel
`"Uebersetze: Speichern -> AZ"`). Sie kann Failover, Cooldown, Pool-Routing —
alles fuer Text. Dann wurde sie zum **Backend fuer Claude Code** gemacht, also
unter einen Tool-Agenten gehaengt. Das ist der Impedanz-Bruch:

```
   FALSCH (heute):  alles — auch der Orchestrator — durch die Text-cascade.
   RICHTIG:         cascade gehoert HINTER den Orchestrator (Worker-Schicht),
                    nicht UNTER ihn.
```

---

## 4. Die zwei Schichten sauber getrennt

```
   ORCHESTRATOR  (muss Tools koennen: Subagenten, Files, Bash)
        │  braucht einen TOOL-ERHALTENDEN Pfad
        │  heute: nur Anthropic-direkt
        ▼
   WORKER  (implement / review / dispatch — Blatt-Aufgaben, Text rein/raus)
        │  Text-only-cascade ist hier OK — SOFERN der Worker keine
        │  eigenen Tools braucht (kein File-I/O, kein Bash)
        └─ braucht nur: enabled + API-Key
```

- **Orchestrator-Slot**: tool-faehiger Pfad zwingend. Das ist die echte Luecke
  gegenueber dem "any provider"-Anspruch.
- **Worker-Slot**: text-only cascade vertretbar — aktuell aber tot, weil
  disabled und/oder keyless (siehe Evidenz).

---

## 5. Loesungswege

Es gibt zwei Routen. Sie schliessen sich nicht aus; B ist die guenstigere
Teilmenge, A die vollstaendige.

### Route A — cascade tool-faehig machen (das "Tool-Passthrough"-Refactor)
Der String-Vertrag der cascade wird auf ein strukturiertes `Message`-Objekt
umgestellt; Tools werden durchgereicht, `tool_calls` zurueckgegeben — fuer ALLE
Provider.
- Wirkung: **alles** durch die cascade (Orchestrator UND tool-nutzende Worker,
  cloud/free/local) bekommt Tool-Calling. Voll provider-agnostisch.
- Betroffen (~13 Dateien): `GenerateResult`, `GenerateOptions`, `LlmProvider` +
  alle 4 Provider-Impls, `LlmCascadeService.dispatch*`, beide Controller, neuer
  `ToolCallNormalizer` (Anthropic `tool_use` <-> OpenAI `tool_calls` <-> Gemini
  `functionCalls`). Zusatzproblem: Failover mitten in einer Tool-Konversation
  braucht Message-History statt Einzel-Prompt.
- Ollama: Aufruf muss von `/api/generate` (text) auf `/api/chat` (tools) wechseln.

### Route B — Orchestrator an der cascade vorbei (CCR-direkt, tool-erhaltend)
CCR (`@musistudio/claude-code-router`) ist GEBAUT, um Tool-Calls zwischen
Anthropic- und OpenAI/Gemini-Format zu uebersetzen (Transformer). Heute zeigt
CCR aber auf die Text-cascade als einzigen Provider -> Tools werden NACH CCR von
der cascade verworfen. Loesung: fuer die ORCHESTRATOR-Route einen *direkten*
tool-faehigen Provider in CCR konfigurieren (Gemini/Ollama mit Transformer),
cascade nur noch fuer die Text-Worker.
- Wirkung: jeder Provider kann Orchestrator sein (cloud/free/local).
- Kosten: kleiner als A. Aber der Orchestrator-Call verliert das
  cascade-Failover/Cooldown; und tool-NUTZENDE Worker werden damit nicht geloest
  (nur A loest die).

### Empfehlung
- Ziel "jeder Provider als Orchestrator auf allen Pools": **Route B** ist der
  schlanke Weg.
- Ziel "auch Worker duerfen Tools nutzen (File-I/O, Bash)": dann zusaetzlich
  **Route A**.
- Habe ich meine Meinung geaendert? Nein — **das Tool-Passthrough bleibt die
  Loesung**. Praezisiert wurde nur der ORT: Orchestrator-Tool-Faehigkeit ist am
  CCR-Layer (B) guenstiger zu haben; die cascade (A) braucht es nur fuer
  tool-nutzende Worker.

---

## 6. Verhaeltnis zu Supermodell AN/AUS (wichtig)

**Die Tool-Faehigkeit haengt am PFAD/PROVIDER, NICHT an Supermodell.**

- Supermodell AUS = keine Rollen-Matrix, ein Catch-All-Modell pro Pool
  (cloud->opus, free->deepseek, local->qwen). CCR-Default-Route wird
  `llm-cascade,{pool}` statt `llm-cascade,orchestrator-{pool}`.
- Das Tool-Stripping trifft free/local **in beiden Modi gleich** — denn auch das
  einzelne Catch-All-Modell ist ein Agent, der Read/Edit/Bash braucht.

Konsequenz fuer das Design: Der Tool-Fix (egal ob A oder B) **darf NICHT an
Supermodell gekoppelt werden** und darf beim Ausschalten von Supermodell **nicht
deaktiviert werden**. Sonst waere free/local auch im Normalbetrieb (ohne
Rollen-Fan-out) weiter kaputt. Richtig: der Fix ist immer aktiv, sobald der
aktive Pfad ein Nicht-Anthropic-Pfad ist. Supermodell schaltet nur den
Rollen-Fan-out OBEN DRAUF — es ist orthogonal zur Tool-Faehigkeit.

---

## 7. Evidenz (Live-Tests dieser Session)

1. **Streaming-Bug (behoben):** `OpenAiCompatController` gab bei `stream:true`
   einen `StreamingResponseBody` unter Rueckgabetyp `ResponseEntity<?>` zurueck
   -> `HttpMessageNotWritableException` -> HTTP 500 bei JEDEM Streaming-Request.
   Da Claude Code immer streamt, crashte jeder local/free-Call NACH erfolgreicher
   Modellantwort -> "API error · Retrying"-Storm. Fix: `streamSse` gibt jetzt die
   komplette SSE-Payload als `ResponseEntity<String>` zurueck. Verifiziert: 200.

2. **Tool-Stripping (bewiesen):** `curl` an cascade mit `tools`-Array +
   `tool_choice:auto` -> Antwort enthaelt nur `content`, kein `tool_calls`.

3. **4-Pfade-Test (gleiche Aufgabe "liste /tmp", bash-Tool):**
   - A cascade/qwen2.5-coder:7b: Prosa ueber `ls`, KEIN Call-Versuch (Tool nie gesehen)
   - B direkt/qwen2.5-coder:7b: Call als TEXT `{"name":"bash",...}`, Feld `tool_calls`=null
   - C direkt/llama3.1:8b: Call als TEXT + MALFORMED (name=Beschreibung)
   - Cloud/Opus: echter `tool_use` FEUERTE, `ls` lief, echte Ausgabe
   => Lehre: cascade strippt (A). Lokale 7-8B-Modelle liefern selbst direkt
      keine sauberen `tool_calls` (B/C) — local braucht ZUSAETZLICH ein staerkeres
      Modell / mehr Hardware (RTX 4060, 8 GB).

4. **@supermodel-Delegation (cloud):** Handoff `implement-cloud` ->
   `cascade_exhausted: keine enabled Modelle fuer category=implement-cloud`.
   Kein Modell dispatcht -> Watcher zeigt NICHTS (er sieht nur [ROUTER]=CCR und
   [DELEG]=Modell-Dispatch). Der Orchestrator (Opus) selbst ist fuer den Watcher
   ohnehin unsichtbar, weil Anthropic-direkt (cascade umgangen).

5. **DB-Zustand (cascade `/api/models`):**
   - `orchestrator-cloud`: opus-4-8 + sonnet-4-6 ENABLED
   - `implement/review/research/dispatch-cloud`: alle ENABLED=FALSE
   - free-Worker teils enabled, aber Modelle sind keyless OpenRouter/Google
   - local-Worker enabled (ollama), aber via cascade tool-gestrippt
   - **Keine API-Keys konfiguriert** (anthropic/google/openrouter alle leer).
     Cloud laeuft rein ueber claude.ai-OAuth. => Jeder cascade-geroutete
     Cloud/Free-Call ist mangels Key tot; nur Ollama (keyless) wird bedient.

6. **CCR-Config (live):** ein einziger Provider `llm-cascade`, Transformer
   `openrouter`, alle Routen -> `llm-cascade,orchestrator-cloud`.

---

## 8. Offene Entscheidungen

- [ ] Route waehlen: B (CCR-direkt, schlank) und/oder A (cascade-Refactor, voll).
- [ ] API-Keys hinterlegen (OpenRouter und/oder Google), damit free/cloud-Worker
      ueberhaupt bedient werden koennen — ODER bewusst nur lokal fahren.
- [ ] Worker-Kategorien enablen, sobald Keys/Modelle stehen.
- [ ] local: klaeren, ob `tool_calls`=null an Ollama-Template/Version oder am
      Modell liegt; realistisch erst mit staerkerem Modell brauchbar.
- [ ] Fallback-Policy: bleibt strikt (kein Worker -> STOPP) oder Self-Fallback
      (Orchestrator macht es selbst)? Beide bleiben fuer den Watcher unsichtbar.
- [ ] Sicherstellen: Tool-Fix ist provider-/pfadgebunden, NICHT an Supermodell
      gekoppelt (Abschnitt 6).

---

## 9. Ziel-Architektur (Entscheidung 2026-06-30)

**Ein Mechanismus statt zwei: die cascade wird tool-faehig (Route A) und das gilt
fuer ALLEN cascade-Traffic — Supermodell AN und AUS, alle Pools.** Damit entfaellt
das separate Route B (CCR-direkt am cascade vorbei): wenn die cascade selbst Tools
traegt, kann der Orchestrator DURCH sie gehen und bekommt Failover/Cooldown gratis.

```
   cascade tool-faehig (Route A, in BEIDEN Modi)
        │
        ├─ SUPERMODELL AN  = Orchestrator-getriebene Delegation
        │    Orchestrator (jeder Provider) durch cascade → Tools → orchestriert
        │    → delegiert an Role-Area-Worker (implement/review/research/dispatch)
        │
        └─ SUPERMODELL AUS = edupro-Semantik (orchestrator-los)
             SemanticCategoryRouter.resolve(purpose, POOL) waehlt per category_meta-
             Beschreibung die passende AREA — aber NUR innerhalb des vom User
             gewaehlten Pools (pool-scoped). Jetzt zusaetzlich tool-faehig.
```

> **HARTE REGEL (gilt fuer BEIDE Clients — Switcher UND edupro):**
> Zwei Schichten:
>   1. BEREICH (Pool): der User waehlt ihn EINMAL. Er wird danach NIEMALS
>      automatisch gewechselt. `local` ist fail-closed (kein Cloud-Ausweich).
>   2. AREA: liegt "oben drauf" — wird semantisch IM fixen Pool selbst gewaehlt.
> Daher nutzen BEIDE die pool-scoped `resolve(purpose, pool)` (waehlt Area IM
> Pool, Fallback = Pool-Catch-All, verlaesst den Pool konstruktiv nie). Die
> flache `resolve(purpose)` (pool-uebergreifend) ist NICHT das Ziel — auch
> edupro fixiert seinen Pool und routet semantisch nur die Area darin.
> (edupro nutzt heute noch die flache Variante → ggf. auf pool-scoped migrieren.)

Die zwei Modi sind zwei Dispatch-Arten auf dieselbe Infrastruktur:
AN = smart orchestriert (Modell entscheidet per Tool-Call),
AUS = automatisch semantisch (cascade entscheidet per Mini-LLM, wie edupro).

## 10. Roadmap — vier unabhaengige Achsen

Der Tool-Passthrough ist das FUNDAMENT, loest aber nur Achse 1. Drei weitere
Achsen sind davon unabhaengig und muessen separat erledigt werden.

```
   ① TOOL-PASSTHROUGH (Route A, beide Modi)            ← Fundament, das grosse Stueck
      String-Vertrag → Message-Objekt; tools rein, tool_calls raus;
      Ollama auf /api/chat; ToolCallNormalizer (Anthropic/OpenAI/Gemini);
      HARTER TEIL: Failover mitten in Tool-Konversation braucht Message-History.

   ② PROVISIONING (enable + key)                        ← sonst cascade_exhausted
      Worker-Kategorien enablen UND Keys hinterlegen. Siehe Abschnitt 11:
      enabled-aber-keylos wird zur Laufzeit UEBERSPRUNGEN.

   ③ SEMANTIK-WIRING off-Mode ............ ✓ UMGESETZT & bewiesen (2026-06-30)
      OpenAiCompatController erkennt bare-Pool (cloud/free/local) → purpose aus
      Messages → SemanticCategoryRouter.resolve(purpose, POOL) → Area IM Pool,
      Fallback auf Pool-Catch-All wenn Area-Cascade leer. pool-scoped → Pool wird
      NIE gewechselt (fail-closed). BEWIESEN: model="local" + "implementiere" →
      implement-local (qwen-coder); + "Commit-Message" → dispatch-local (llama3.2).
      Config dafuer (erledigt): (a) "utility"-Modell fuer den Router-Decision-Call;
      (b) category_meta-Beschreibungen pro BARE area (implement/review/research/
      dispatch) — umgeht den frueher gefundenen Komposit-vs-bare Namens-Mismatch.

   ④ LOCAL-QUALITAET                                    ← Hardware/Modell
      7-8B liefern selbst mit Tools schlechte tool_calls. Staerkeres lokales
      Modell / mehr VRAM noetig, damit local-Orchestrator brauchbar delegiert.
```

Was Achse ① (Tool-Fix) NICHT mitloest: ②, ③, ④ bleiben offen.

## 11. Key/Enable-Verhalten der cascade (verifiziert)

Drei Stufen, wie ein Delegations-Ziel unerreichbar wird (LlmCascadeService.java):

```
   enabled=false            → gar nicht in der Liste (Query filtert: enabled=true)
   enabled=true, KEIN Key    → in Liste, aber Dispatch UEBERSPRINGT (Zeile 287-290:
                               "uebersprungen — Key nicht gesetzt", continue)
   enabled=true, Key, Fehler → wird probiert, scheitert, Failover zum naechsten
```

- Ollama ist `keyless=true` → besteht den Key-Check → wird NICHT uebersprungen
  (deshalb laeuft local trotz "keiner Keys konfiguriert").
- Sind alle Modelle einer Kategorie disabled/keylos → `cascade_exhausted`
  ("LLM-Cascade ist leer — keine enabled Modelle fuer category=...").
- **Konsequenz: `enable` allein reicht nicht.** Ein enabled-aber-keyloses
  Cloud/Free-Modell wird uebersprungen, als waere es nicht da. Erst `enable`
  (in die Liste) + `key` (laeuft tatsaechlich) macht ein Worker-Ziel erreichbar.
- mode=fixed wirft statt zu ueberspringen einen Fehler (Zeile 432-433).

---

## 12. Soll-Design — autoritatives Bild (ersetzt fruehere Panels)

> Diese Sicht ist die korrigierte, abgestimmte Wahrheit. Frueher hatte ich
> faelschlich (a) im AUS-Modus die Rollen implement/review/research/dispatch
> gezeigt — die sind AN-only — und (b) edupro gar keinen AN-Modus zugestanden.
> Beides ist hier richtiggestellt: AN = fixe Rollen, AUS = frei/Catch-All;
> beide Projekte strukturell symmetrisch.

```
                           ┌──────────────────────────────────────────────┐
                           │  User waehlt POOL  →  cloud | free | local     │
                           │  FIX. nie auto-gewechselt. local = fail-closed │
                           │  (gilt fuer BEIDE Projekte)                    │
                           └───────────────────────┬────────────────────────┘
                                                   │
                  ┌────────────────────────────────┴────────────────────────────────┐
                  ▼                                                                   ▼
     ╔════════════════════════════════════╗                      ╔════════════════════════════════════╗
     ║         supermodel = AN             ║                      ║         supermodel = AUS            ║
     ║   AREA = FIXE ROLLEN                ║                      ║   AREA = FREI definierbar           ║
     ║   Orchestrator delegiert per        ║                      ║   Semantic-Router resolve(purpose,  ║
     ║   Tool-Call                         ║                      ║   pool) waehlt — ODER Catch-All     ║
     ╚════════════════════════════════════╝                      ╚════════════════════════════════════╝
                  │                                                                   │
     ┌────────────┴────────────┐                                       ┌──────────────┴──────────────┐
     ▼                         ▼                                       ▼                             ▼
   SWITCHER                  EDUPRO                                  SWITCHER                      EDUPRO
   (= gleich)               (= gleich)                              (Default: Catch-All)          (freier Satz)
```

### supermodel = AN  —  identisch fuer beide Projekte

```
     orchestrator-{pool}  delegiert an die FIXEN Rollen:
     ┌─────────┬──────────────┬───────────────────────────────────────────────┐
     │ POOL    │ orchestrator │ implement / review / research / dispatch        │
     ├─────────┼──────────────┼─────────────────────────────────────────────────┤
     │ cloud   │ opus-4-8 ●   │ deepseek✗ · gpt4o-mini✗ · gemini-pro✗ · flash✗  │
     │ free    │ hermes ✗     │ qwen3-coder● · oss-120b● · gemma4● · llama3.3●  │
     │ local   │ qwen-coder●  │ qwen-coder● · qwen2.5● · qwen2.5● · llama3.2●   │
     └─────────┴──────────────┴─────────────────────────────────────────────────┘
     SWITCHER + EDUPRO: gleicher Mechanismus, gleiche fixe Rollen.
     (● enabled  ✗ disabled — cloud-Worker brauchen Provisioning)
```

### supermodel = AUS  —  freie Area / Catch-All, semantisch

```
     resolve(purpose, pool)  waehlt unter FREI definierten Areas (KEINE fixen Rollen!)

     ┌─ SWITCHER (Default = nur Catch-All) ─────────────────────────────────────┐
     │   pool=cloud  →  opus-4-7 ●                                               │
     │   pool=free   →  deepseek ● → qwen3-coder ●                               │
     │   pool=local  →  qwen-coder ● → qwen2.5 ●                                 │
     │   (optional eigener Area-Satz definierbar → dann semantisch aufgeloest)   │
     └──────────────────────────────────────────────────────────────────────────┘

     ┌─ EDUPRO (eigener freier Area-Satz) ──────────────────────────────────────┐
     │   resolve(purpose, pool) unter:                                          │
     │     content  →  deepseek-chat → gpt-oss-120b                              │
     │     dev      →  deepseek-reasoner → gpt-oss-120b                          │
     │     utility  →  deepseek-v3.1 → gemma3:4b → gemini-lite                   │
     │     general  →  gpt-oss-120b / gemini-lite   (Fallback)                   │
     └──────────────────────────────────────────────────────────────────────────┘
```

### Die ganze Logik auf einen Blick

```
                     │  supermodel = AN              │  supermodel = AUS
     ────────────────┼───────────────────────────────┼────────────────────────────────
     AREA ist...     │  FIXE Rollen                  │  FREI definierbar
                     │  orch/impl/review/             │  Default = Pool-Catch-All
                     │  research/dispatch             │  ODER eigener Satz (semantisch)
     ────────────────┼───────────────────────────────┼────────────────────────────────
     Wer waehlt?     │  Orchestrator (Tool-Call)     │  Semantic-Router (purpose)
     ────────────────┼───────────────────────────────┼────────────────────────────────
     SWITCHER        │  ✓ fixe Rollen                │  ✓ Catch-All (od. eigener Satz)
     EDUPRO          │  ✓ gleich wie Switcher        │  ✓ content/utility/dev/general
     ────────────────┴───────────────────────────────┴────────────────────────────────
     Konstant immer: POOL fix (User-Wahl), fail-closed, beide Projekte symmetrisch.
     Einziger Unterschied: WELCHE freien Areas man im AUS-Modus definiert.
```

### Failover / Cooldown — in BEIDEN Modi (Folge von Route A)

Weil im Soll der Orchestrator DURCH die cascade laeuft (Route A, nicht Bypass),
bekommt auch AN Failover/Cooldown — jede Kategorie ist selbst eine Failover-Kette:

```
   orchestrator-{pool}:  opus-4-8 →(503/Fehler)→ sonnet-4-6     ← Orchestrator-Failover
        └─ implement-{pool}: deepseek →(Fehler)→ gemini-flash   ← Worker-Failover (pro Area)

   ① Worker-Failover      = einfach & gratis (Einzel-Shot, kein State)
   ② Orchestrator-Failover = moeglich, aber DER harte Teil von Route A
                             (mid-Tool-Konversation → braucht Message-History)
```

### Ehrlicher Status edupro (heute vs. Soll)

- **Heute:** edupro hat KEIN Pool-/Supermodell-/Orchestrator-Konzept — nur flache
  Kategorien (content/utility/dev/general) + optional purpose-Semantik. Eigene DB
  (`edupro`), eigener Seed. Es ist reine Textgenerierung, braucht KEINE Tools.
- **Soll (Unifizierung):** edupro bekommt die Pool-Dimension (fix, fail-closed)
  und wird symmetrisch zum Switcher (AN = gleiche fixe Rollen, AUS = eigener
  freier Area-Satz, pool-scoped).

### Tool-Passthrough: wann noetig? (PRAEZISE Regel)

Nicht "agentischer Client vs Text" (zu grob), sondern:
**Tool-Passthrough ist noetig, sobald Tool-Calls im Pfad sind.** Tool-Calls
entstehen durch (a) einen agentischen Client (Switcher: Read/Edit/Bash) ODER
(b) Orchestrator-Delegation (supermodel=AN — Delegation IST ein Tool-Call).

```
                     supermodel=AN                 supermodel=AUS
   ──────────────────────────────────────────────────────────────────────
   SWITCHER          ✓ (Delegation + agentisch)    ✓ (agentischer Client)
   EDUPRO            ✓ (Delegation = Tool-Call!)    –  (Text, keine Tools)
   ──────────────────────────────────────────────────────────────────────
   Merksatz: supermodel=AN braucht Tool-Passthrough IMMER (Delegation =
             Tool-Calls), egal welcher Client. supermodel=AUS nur, wenn der
             Client selbst Tools nutzt (Switcher ja, edupro nein).
```

- edupro HEUTE (flach, Text, keine Delegation): Tool-Passthrough NICHT noetig.
- edupro MIT supermodel=AN + Delegation: Tool-Passthrough DOCH noetig — der
  Orchestrator delegiert per Tool-Call, der die cascade ueberleben muss
  (auch wenn die einzelnen Worker reiner Text sind).

---

## 13. Umsetzungsplan (Reihenfolge + was jeder Schritt freischaltet)

```
   [0] ✓ Streaming-Fix .................................. DONE (verifiziert)

   [1]  SPIKE — 1 Tool-Call durch die cascade beweisen ... klein, ZUERST
        braucht 1 Key (Gemini/OpenRouter) → beweist, dass Route A traegt

   [2]  ① TOOL-PASSTHROUGH (Route A) ............ ✓ UMGESETZT & end-to-end bewiesen (2026-06-30)
        Isolierter Chat-Pfad (ChatResult + LlmProvider.generateChat +
        LlmCascadeService.generateChat + OpenAiCompatController), Text-Pfad
        unberuehrt (edupro-sicher). generateChat-Override in ALLEN 3 Providern:
        OpenAiCompat (Ollama/OpenRouter/OpenAI/DeepSeek) nativ; Anthropic
        (tool_use↔tool_calls) + Gemini (functionCall↔tool_calls) normalisiert.
        BEWIESEN: cascade /v1/chat/completions mit tools → strukturierte tool_calls
        (JSON + SSE) via ollama:llama3.1:8b durch den vollen Stack.
        REST (optional): ToolCallNormalizer als eigene Klasse (aktuell inline je
        Provider); echtes mid-Tool-Konversation-Failover (synchron deckt
        Request-Level-Failover bereits ab — messages werden ans naechste Modell
        durchgereicht).

   [3]  ② PROVISIONING (parallel moeglich) .............. mittel
        cloud-Worker enablen + API-Keys hinterlegen
        SCHALTET FREI: Delegations-Ziele erreichbar (sonst cascade_exhausted)

   [4]  ③ SEMANTIK-WIRING (off-Mode) ............ ✓ UMGESETZT & bewiesen (2026-06-30)
        resolve(purpose,pool) in OpenAiCompatController + category_meta-Beschreibungen
        + "utility"-Modell fuer Router-Decision. Fail-closed (bleibt im Pool).
        BEWIESEN: model="local" routet "implementiere"→implement-local,
        "Commit-Message"→dispatch-local.

   [5]  ④ LOCAL-QUALITAET ...................... ✓ (llama3.1:8b als orchestrator-local)
        llama3.1:8b liefert strukturierte tool_calls (qwen nur Text). Primaer
        gesetzt, qwen als Failover. (Optional: mehr VRAM fuer groessere Modelle.)
```

### Wer braucht was

```
                        Tool-Passthrough   Provisioning   Semantik-Wiring
   ─────────────────────────────────────────────────────────────────────
   Switcher AN          ✓ (Deleg+agentisch) ✓ (Worker)     –
   Switcher AUS         ✓ (agentisch)        –             ✓ (Auto-Wahl)
   edupro   AN          ✓ (Delegation)       ✓ (Worker)    –
   edupro   AUS         – (Text, inert)      –             ✓ (Auto-Wahl)
   ─────────────────────────────────────────────────────────────────────
   Route A einmal gebaut = fuer ALLE da; ungenutzte Faelle = harmloser No-Op
```

### Entscheidungs-Gates (Input vom User noetig)

```
   ⬡ Welcher Key fuer Spike + Worker?   → OpenRouter und/oder Google
   ⬡ cloud-Worker: welche Modelle + enablen?
   ⬡ Hardware-Upgrade fuer local?       → sonst local nur eingeschraenkt
   ⬡ edupro jetzt unifizieren (Pool-Dimension) oder spaeter?
```

---

## 14. Zielbild final — so laeuft es nach der Umsetzung

### A. Was dann funktioniert

```
   ✓ Tools fliessen ueberall durch    → Switcher agentisch in JEDEM Pool
   ✓ Failover + Cooldown in AN & AUS  → opus→sonnet; deepseek→gemini ...
   ✓ AUS waehlt Area semantisch selbst → resolve(purpose, pool)
   ✓ AN delegiert per Orchestrator     → fixe Rollen, Worker pro Area
   ✓ POOL fix, local fail-closed       → nie automatischer Pool-Wechsel
   ✓ EIN Mechanismus, beide Projekte   → Switcher + edupro, gleiche Engine
```

### B. Modellverteilung SWITCHER (alles aktiv nach Provisioning)

```
   AREA \ POOL  │ cloud                  │ free                        │ local
   ─────────────┼────────────────────────┼─────────────────────────────┼──────────────────────────
   orchestrator │ opus-4-8 → sonnet-4-6   │ (starkes free-Modell)       │ qwen-coder:7b *(④ staerker)
   implement    │ deepseek → gemini-flash │ qwen3-coder → qwen3-next-80b│ qwen2.5-coder:7b
   review       │ gpt-4o-mini             │ gpt-oss-120b                │ qwen2.5:7b
   research     │ gemini-2.5-pro          │ gemma-4-31b                 │ qwen2.5:7b
   dispatch     │ gemini-flash-lite       │ llama-3.3-70b → gpt-oss-20b │ llama3.2:3b
   catch-all    │ opus-4-7 → sonnet-4-6   │ deepseek → qwen3-coder      │ qwen-coder:7b → qwen2.5:7b
       Pfeil = Failover-Reihenfolge; jedes Feld = eigene Failover-Kette
```

### C. Modellverteilung EDUPRO (Pool-Dimension ergaenzt)

```
   AREA \ POOL  │ cloud                 │ free            │ local
   ─────────────┼───────────────────────┼─────────────────┼──────────────────
   content      │ deepseek-chat         │ (free-Variante) │ (ollama-Variante)
   dev          │ deepseek-reasoner     │ (free-Variante) │ (ollama-Variante)
   utility      │ deepseek-v3.1         │ (free-Variante) │ gemma3:4b
   general      │ gpt-oss-120b/gem-lite │ (free-Variante) │ (ollama-Variante)
       cloud-Spalte = realer Seed; free/local = SEEDING-Entscheidung
```

### D. Anfrage-Fluss

```
   AN :  Pool=cloud → orchestrator opus-4-8 delegiert an implement/review/...
         opus faellt → sonnet-4-6 (Orchestrator-Failover)
   AUS:  Pool=local → resolve(purpose,"local") → review-local: qwen2.5:7b
         nichts passt → catch-all local; bleibt IMMER local (fail-closed)
```

### E. Gesamtrahmen

```
   Claude Code / edupro → CCR → llm-cascade (TOOL-FAEHIG)
      Pool fix (fail-closed)
        ├─ AN : Orchestrator delegiert an fixe Rollen   (Tools + Failover)
        └─ AUS: Semantic-Router resolve(purpose,pool)    (Tools wenn agentisch + Failover)
      → Pool×Area-Matrix, jedes Feld eine Failover-Kette
```

**In einem Satz:** ein tool-faehiges cascade-Backend fuer beide Projekte; User waehlt
einen festen Pool, darin waehlt entweder der Orchestrator (AN) oder der Semantic-Router
(AUS) die Area; local bleibt fail-closed; jedes Matrix-Feld hat Failover/Cooldown.