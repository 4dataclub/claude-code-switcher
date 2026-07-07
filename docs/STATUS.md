# Projekt-Status & Handoff

> Aktueller Arbeitsstand für den nächsten Entwickler. Kurz halten, bei jedem
> größeren Meilenstein aktualisieren.

**Stand: 2026-07-07** — Watcher-Fix (`wrapper/switcher-watch.sh`): läuft jetzt im
**cloud-Pool mit Supermodell AN**. Noch **ungetestet**: `local`-Pool und Supermodell
**AUS**.

## Architektur-Kern (aktuell)

- **Failover ist serverseitig** in der `llm-cascade`: pro Request wird bei Fehler/
  Quota/Timeout/nicht-erreichbarem Server transparent das nächste Modell der Kette
  genutzt, ausgefallene Modelle bekommen Cooldown. Kein Session-Neustart.
- **Alle Pools routen über llm-cascade.** `RouterService.buildLlmCascadeProvider(pool)`
  ist pool-spezifisch: bei `local` kennt der ccr-Router nur `*-local`-Targets +
  keine Cloud-Keys ⇒ **fail-closed** (nichts verlässt das interne Netz).
- **Pool-Keys:** `cloud`/`free` sind reine Buckets (Name ohne Semantik, Anzeige-Name
  via `category_meta.display_name` frei änderbar). **`local` ist der einzige Pool mit
  Key-Semantik** — der Code prüft literal `"local"` für fail-closed. Key `local`
  niemals umbenennen; Anzeige-Name ändern ist ok.
- **Zwei getrennte Pool-Controls (nicht verwechseln):**
  - **Bereich-Toggle** (Modus-Panel, `/api/mode`) = **echter Pool-Wechsel**: pinnt
    IMMER das Top des gewählten Pools als aktive Session + Restart. AUS: plain
    `{pool}`-Top (anthropic-direkt bei anthropic/kein-Key, sonst via ccr→llm-cascade);
    AN: `orchestrator-{pool}`-Top. Kein „Pool gewechselt, aber nichts aktiv"-Zustand.
  - **Anzeige-Dropdown** (Aktiver/Cloud/Free/Local/Alle) = reine **Anzeige/Filterung**
    der Tabellen, kein Routing-Effekt.
- **Cascade-Beschreibungen = semantischer Routing-Text:** Bei Supermodell AUS
  klassifiziert der Router jeden Request anhand dieser „Passt zu…/Passt NICHT zu…"-
  Texte in eine Area (general/dev/utility/content). Editierbar in der Cascade-Karte.
  Pool-Compounds `{area}-{pool}` erben den Text der bare Area.
- **Observability:** Der cloud-OAuth-Direktpfad umgeht die Cascade → wird **nicht**
  geloggt. `free`/`local` (und cloud mit echtem `sk-ant`-Key) laufen durch die Cascade
  → volle Logs (Prompt-Log via `logPromptSnippet`-Toggle) + Stats.

## Verifiziert (live, 2026-07-03)

- **cloud AUS** → opus-4-7 (cloud-Top), anthropic-direkt (OAuth, kein BASE_URL). ✓
- **cloud AN** → opus-4-8 (orchestrator-cloud-Top), anthropic-direkt + Failover-Kette
  `[opus-4-8 → gemini-2.5-pro]`. ✓
- Backend-Tests grün (42: ApiControllerTest + RouterServiceTest).
- llm-cascade: `OpenAiCompatProviderTest` (Connection-Failover) grün.

## Verifiziert (live, 2026-07-07)

- **Watcher (`wrapper/switcher-watch.sh`) läuft im cloud-Pool mit Supermodell AN.** ✓

### Teststand Verhaltens-Matrix (7 Zeilen)

| # | Zeile | Code | Live | Anker |
|---|---|:--:|:--:|---|
| 1 | AN·OAuth | ✅ | ✅ **live** | `ApiController:318`, `RouterService:265` |
| 2 | AN·Anthropic-Key | ✅ | ❌ offen (kein `sk-ant`) | `ApiController:305-310` |
| 3 | AN·Nicht-Anthropic-Key (deepseek/OR) | ✅ | ✅ **live** | `RouterService:104-111` |
| 4 | AN·local | ✅ | ⏳ bis Config B+ | `RouterService:206-208`, `DataInitializer` |
| 5 | AUS·OAuth | ✅ | ❌ offen | `RouterService:265` |
| 6 | AUS·Key | ✅ | ❌ offen ⟨?⟩ | Cascade-③-Scoping |
| 7 | AUS·local | ⚠️ | ⏳ bis Config B+ | `general/utility-local` nicht geseedet |

- **Live bewiesen: Zeile 1 + 3** (beide Supermodell AN, cloud). Rest code-konform, aber
  noch nicht durchgeklickt.
- **Haken Z.7:** `content-local`/`dev-local` existieren, `general-local`/`utility-local`
  bewusst NICHT geseedet (fail-closed).
- **Noch ungetestet:** `local`-Pool (Z.4/7) + Supermodell **AUS** (Z.5/6). `local` wird
  getestet, sobald **Config B+** verfügbar ist.

### Nächste Tests (offene Matrix-Zeilen — Reihenfolge egal)

1. **Z.5 AUS·OAuth** — Supermodell AUS im cloud-Pool: prüfen, dass 1 Modell pro Anfrage
   semantisch ins PURPOSE-Fach geht (content/dev/general/utility), Loop ungeloggt.
2. **Z.6 AUS·Key** — cloud-Key + Supermodell AUS: Cascade-③ klassifiziert auf PURPOSE,
   NICHT auf AN-Rollen. `⟨?⟩` explizit gegenprüfen.
3. **Z.2 AN·Anthropic-Key** — echten `sk-ant-api03`-Key in DB `app_settings.anthropicApiKey`
   legen → Opus läuft über Router, Loop wird geloggt (`[ROUTER]`).
4. **Z.4 AN·local** — sobald **Config B+** + Ollama-Modelle: lokaler Kopf delegiert an
   `*-local`, alles geloggt, kein Cloud-Ausweich (fail-closed).
5. **Z.7 AUS·local** — mit Config B+: PURPOSE-local. Vorher klären, ob `general-local`/
   `utility-local` geseedet werden sollen oder bewusst fail-closed bleiben.

## Offen / To-Do

- **Laufzeit verifiziert (2026-07-03):** Pool-Routing (`local`→ollama, `free`→openrouter)
  live; semantische Auflösung live (Code-Task→qwen2.5-coder, Gruß→llama3.1); Failover
  real in Events (`switch_down` bei 503 + Cooldown, `promote_primary` nach Ablauf);
  Quality-Auto-Disable real. Offen nur: AUS-Pool-Wechsel-Repin live durchklicken.
- PR **#88** (`feat/pool-matrix-409-logpanel`) ist obsolet — abgelöst durch #89/#91/#93.
  Kann auf GitHub geschlossen werden.
- Zukunft: **data-driven Pools** (Pools aus Tabelle statt hartkodiertem 3-Enum;
  fail-closed als per-Pool-Flag statt literal `"local"`). Erst wenn alles stabil läuft.

## Deploy-Hinweise

- Nach **Backend**-Rebuild immer `docker compose restart switcher-frontend`, sonst
  liefert nginx 502 (gecachte alte Backend-Container-IP).
- Frontend-Docker-Build dauert oft > 2 min → Geduld / im Hintergrund bauen.
- `llm-cascade` wird lokal aus `../llm-cascade` gebaut (compose override);
  `ki-models-ui` ist als `.tgz` in `angular-frontend/vendor/` vendored.

## Repos

- claude-code-switcher (dieses) · llm-cascade · ki-models-ui — alle `4dataclub/*`.
- Aktuell vendored: ki-models-ui **0.36.0**.
