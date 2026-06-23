# Orchestrator-Session-Konsistenz — Design

- **Datum:** 2026-06-23
- **Branch:** `fix/local-orchestrator-8gb-default`
- **Status:** Design freigegeben (beide offenen Entscheidungen bestätigt) — bereit für Plan

## Leitregel (von Djavid festgelegt)

> Der **Orchestrator ist die oberste Einheit**. Das Modell, das als `orchestrator-{pool}`
> eingestellt ist, **MUSS auch die laufende Session sein** — für **alle** Pools gleich.
> Kein versteckter Sonderpfad, kein hart verdrahteter Opus-Pin.

Konkret: Schaltet der User auf **local**, darf Opus **nicht** „im Hintergrund" weiterlaufen.
Die Session selbst muss dann das `orchestrator-local`-Modell (Werks-Seed `qwen2.5-coder:7b`)
sein. „Session = was im Orchestrator steht" ist die Konsistenz-Invariante.

## Problem / Ist-Zustand (code-verifiziert)

Heute weicht die laufende Session von der `orchestrator-{pool}`-Einstellung ab — in zwei Fällen:

### A) cloud/free: Session ≠ Cascade-Top
- `pinOrchestratorForPool` (`ApiController.java:561-574`) setzt bei cloud/free **hart
  `provider=anthropic`** und entfernt `ANTHROPIC_BASE_URL`. Es liest **kein** Modell aus
  `orchestrator-{pool}`. Das laufende Modell ist dann der claude-Binary-Default (`opus-4-8`).
- Die `orchestrator-cloud`-Zelle ist aber auf `claude-sonnet-4-6` geseedet
  (`DataInitializer.java:102-103`). → UI zeigt `currentModel = sonnet-4-6`, die Session läuft
  aber `opus-4-8`. **Zwei verschiedene Wahrheiten.**
- Die `orchestrator-cloud`-Zellen werden via `orchestratorFailoverChain()`
  (`ApiController.java:529-546`) nur als **Failover-Kette** geladen (`fallback_chain`),
  nicht als Primär-Modell.

### B) local: Session bleibt Opus (kein Reroute)
- `pinOrchestratorForPool` (`ApiController.java:553-559`) gibt für local **`false`** zurück
  (kein Restart) und setzt nur `localOrchestratorPending`. Es gibt **kein** Reroute der
  Haupt-Session auf das lokale Modell.
- Kommentar Z.556-557 sagt es explizit: „echtes ccr-Routing aufs lokale Main-Loop-Modell ist
  **Phase E**, sobald Ollama-Modelle da sind" — also bewusst **noch nicht implementiert**.

### Technische Wurzel: der Router kann kein Ollama
- Der Router (ccr / claude-code-router) baut in `RouterService.buildProviders`
  (`RouterService.java:87-117`) **nur** Provider für `google` und `openrouter`. `UI_TO_CCR`
  (`RouterService.java:53-57`) kennt nur `google/anthropic/openrouter`.
- **Ollama ist kein Router-Ziel.** Die Haupt-Session kann via `ANTHROPIC_BASE_URL→:3456`
  (`HOST_ROUTER_URL`, `ApiController.java:55`, gesetzt z.B. Z.237/248) heute nur zu
  google/openrouter geroutet werden; anthropic = direkt (kein BASE_URL).
- ⇒ Der Weg „Session → lokales Modell" **fehlt schlicht**. Deshalb wurde Phase E nie verdrahtet.

## Ziel-Design

Eine Invariante für alle Pools: **Session = oberstes aktiviertes Modell von `orchestrator-{pool}`**,
der Rest der Zelle = Failover-Kette (Reihenfolge via `orderIdx`, wie heute datengetrieben).

### Teil 1 — cloud/free (kleinerer Eingriff)
`pinOrchestratorForPool` liest künftig das **Top-Modell von `orchestrator-{pool}`** und routet
die Session dorthin, statt `provider=anthropic` hart zu setzen:
- Top = anthropic-Modell → Session direkt (kein BASE_URL), Modell = das Cascade-Top.
- Top = google/openrouter → `ANTHROPIC_BASE_URL→:3456` + Router-Default-Route auf dieses Modell.
- Failover-Kette = restliche Zellen via `orchestratorFailoverChain()` (bereits vorhanden).
- `AutoPromoteService` holt die Top-Wahl nach Cooldown zurück (Logik bleibt).

Damit gilt: `orchestrator-cloud`-`currentModel` == Session-Modell. UI und Realität stimmen überein.

### Teil 2 — local (das eigentliche „Phase E")
1. **Ollama als Router-Provider** in `RouterService.buildProviders` ergänzen: ccr
   openai-compatible Transformer → `http://<ollama-host>:11434/v1` (Ollama spricht eine
   OpenAI-kompatible API). `UI_TO_CCR` um `ollama` erweitern.
2. **local-Pin verdrahten:** statt `return false` schreibt der Pin
   `ANTHROPIC_BASE_URL→:3456` + Router-Default-Route `ollama,<orchestrator-local-Top>`
   (Werks-Seed `qwen2.5-coder:7b`) und **triggert den Restart**.
3. **Fail-closed durchziehen:** bei local enthält die Router-Config **ausschließlich** den
   Ollama-Provider — **kein** google/openrouter-Fallback; `mode=manual` (kein Auto-Cloud-
   Ausweich). Nichts verlässt das interne Netz.
4. **localOrchestratorPending** bleibt das Signal: kein aktiviertes lokales Modell →
   pending=true, **kein** Cloud-Ausweich (lieber STOPP als Leak).

Ergebnis: Beim Umschalten auf local **verschwindet Opus**, die Session läuft auf dem
`orchestrator-local`-Modell — exakt wie Djavids Regel verlangt.

## Wiring-Details (für die Planungsphase)
- **Erreichbarkeit Router→Ollama:** der ccr-Container muss den Ollama-Host erreichen
  (Host-Ollama via `host.docker.internal` / Host-Gateway, oder Ollama-Container im selben
  Netz). Muss in docker-compose/Router-Config sauber gesetzt werden.
- **ccr-Transformer für Ollama:** verifizieren, dass der vorhandene openai-compatible
  Transformer mit Ollamas `/v1/chat/completions` funktioniert (Tool-Use/Streaming).
- **Restart-Pfad:** `setMode` muss für local jetzt `needRestart=true` liefern (Restart-Marker
  `supermodel-local`), Wrapper zieht die Session neu hoch auf das lokale Modell.
- **Qualität:** Claude Code auf `qwen2.5-coder:7b` als Main-Loop ist funktional schwächer —
  bewusst akzeptiert (User-Wunsch); nicht Teil dieses Specs.

## Entschieden (2026-06-23, Djavid)
1. **Approach local: Approach A bestätigt** — Session läuft bei local echt über ccr→Ollama,
   Opus verschwindet.
2. **Scope: beides** — Teil 1 (cloud/free Pin = Cascade-Top) **und** Teil 2 (local/Phase E)
   sind im Umfang.

## Betroffene Dateien (Voraussicht)
- `java-backend/.../controller/ApiController.java` — `pinOrchestratorForPool`, `setMode`
  (Restart-Trigger local), evtl. `orchestratorFailoverChain` (Top als Primär).
- `java-backend/.../service/RouterService.java` — `buildProviders` (+Ollama), `UI_TO_CCR`,
  `writeRouterConfig` (local = nur Ollama).
- `docker-compose.yml` / Router-Netz — Router→Ollama-Erreichbarkeit.
- Tests: `ApiControllerTest`, `RouterServiceTest` — Pin/Route/Fail-closed-Fälle.
- Doku: `SUPERMODELL.md` (Phase-E-Status), brain-Note.

## Sicherheit (unverändert gültig)
- Diese Session hängt an **Anthropic direkt** — Backend-Rebuild trifft sie nicht. Aber:
  sobald der local-Pin scharf ist und der User auf local schaltet, wird die Session
  **bewusst** neu gestartet (genau das ist das Ziel).
- Reseeds key-erhaltend (`app_settings`/API-Keys nie anfassen).
- Local fail-closed: Router-Config bei local nur Ollama, kein Cloud-Fallback.
