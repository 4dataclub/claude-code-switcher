# Switcher — Offene Todos

> **Source-of-Truth:** `~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/project_next_session.md`
>
> Diese Datei ist ein **Spiegel** der Brain-Tasks im Repo, damit beide
> Maintainer denselben Stand sehen — auch ohne Brain-Klon. Bei Konflikt: Brain gewinnt.
>
> **Pattern-übergeordnet:** Jedes 4dataclub-Repo bekommt eine `TODOS.md`.

Stand: 2026-05-15

---

## 💡 Idee 2026-05-15 — Ollama als 4. Provider (80/20-Routing)

> User-Diktat: „80% Routine lokal, 20% Hard-Tasks an Claude über Provider-Wahl."
> Video-Quelle: <https://www.youtube.com/watch?v=z_uzyXCSyPk> (Gemma 4 + Ollama).

**Was:** Lokales Ollama als zusätzlicher LlmProvider neben Anthropic/Google/OpenRouter.
Nutzt den **bereits geplanten `openai_compat`-Catch-All** (siehe Brain-Idee „Switcher-UI nach
EduPro-Pattern" Z.107–124) — Ollama hat OpenAI-kompatible API auf `:11434/v1`.

| Aspekt | Implementation-Hinweis |
|---|---|
| Provider-Eintrag | `ollama` als Bean-Name auf `OpenAiCompatProvider` mit `baseUrl=http://host.docker.internal:11434/v1` |
| Modell-Default-Seed | `gemma-3:4b` (oder Gemma-4 sobald via Ollama verfügbar) |
| Cooldown | `null` — lokal hat kein Quota-Limit |
| `apiKeySettingKey` | `ollamaBaseUrl` (Wert ist URL, nicht Key) |
| Routing-Heuristik | Cascade-Order: Ollama zuerst (wenn aktiv) → Cloud-Fallback bei Confidence-Drop oder explizitem `:hard:`-Tag |
| docker-compose | optionalen `ollama`-Service (Profile `local-llm`) ergänzen, nicht im Default-Profile (Hardware-Anforderungen) |

Detail in Brain: [Idee — Lokale KI für 80% Routine, Claude für 20% Hard-Tasks](file:///Users/data/Documents/obsidian-brain/shared/01%20Inbox/Idee%20—%20Lokale%20KI%20für%2080%25%20Routine,%20Claude%20für%2020%25%20Hard-Tasks%20(Ollama%20+%20Gemma%204).md).

**Vorrang:** zu klären. Phase K (eigene DB) ist Voraussetzung; danach passt Ollama-Provider
ohne Schema-Änderung in die `ai_model_config`-Tabelle.

---

## 🟠 Test-Auto-Pipeline (Bootstrap einmalig)

> Soll: gleiches Auto-Heal/Audit/Agent-Pattern wie EduPro für i18n, nur für Tests.
> Tasks entstehen nicht hier, sondern als Findings im Audit-System.

Switcher-Bootstrap-Anteile (einmalig):

| Schritt | Was | Datei |
|---|---|---|
| Boot.1 | `spring-boot-starter-test` + JUnit5 + Mockito | `java-backend/pom.xml` |
| Boot.2 | Vorlage-Test pro Schicht: 1 Controller-Test (`ApiControllerTest`), 1 Service-Test (z.B. `LlmCascadeClientTest`) | `java-backend/src/test/java/com/dataclub/switcher/` |
| Boot.5 | GitHub-Actions `auto-test.yml` als Required-Check | `.github/workflows/auto-test.yml` |
| Boot.6 | Cross-Stack-Smoke: `docker-compose.test.yml` mit llm-cascade + curl-Smoke-Skript | `docker-compose.test.yml`, `scripts/smoke.sh` |

Frontend-Tests folgen erst nach Phase L.4 (Switcher Vanilla-HTML → Angular-Migration);
dann via gemeinsamer ki-models-ui-Test-Strategie.

**Übergangs-Regel:** Bis Boot.5 läuft, bleibt manuelle Checkliste Pflicht.

Detail: `~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/feedback_test_before_merge.md`.

---

---

## 🟡 Phase K — Eigener Modell-Context (vor Phase L)

> **Warum:** EduPro hat eigene `ai_model_config`-DB, Switcher proxied heute alles an
> llm-cascade. Damit ist der Switcher **kein** eigenständiger Context — er teilt
> Modell-Liste + Keys mit der Cascade. Soll: beide Konsumenten symmetrisch, llm-cascade
> wird zur reinen Engine.
>
> **Plan-File:** `~/.claude/plans/ich-werde-dir-fragen-lucky-pearl.md`
> **Aufwand-Schätzung:** ~4–6h

### Tasks

| # | Task | Datei(en) | Status |
|---|---|---|---|
| K.1 | JPA + Postgres-Driver einbinden | `java-backend/pom.xml`, `java-backend/src/main/resources/application.properties`, `docker-compose.yml` | offen |
| K.2 | `AiModelConfig` Entity + Repo (Vorlage: EduPro `com.edupro.model.AiModelConfig`) | `java-backend/src/main/java/com/dataclub/switcher/model/AiModelConfig.java`, `…/repository/AiModelConfigRepository.java` | offen |
| K.2 | `AppSetting` Entity + Repo (für API-Keys + Cascade-Config-Tri-State) | `…/model/AppSetting.java`, `…/repository/AppSettingRepository.java` | offen |
| K.3 | `ApiController.java`: `/api/admin/ai-models` (GET/POST/PUT/DELETE/reorder) auf lokale DB umstellen | `…/controller/ApiController.java` | offen |
| K.3 | `ApiController.java`: `/api/admin/api-keys`, `/api/admin/cascade-config` auf `AppSetting`-Repo | `…/controller/ApiController.java` | offen |
| K.3 | `LlmCascadeClient.generate()` bleibt — einziger Pfad gegen Cascade | `…/service/LlmCascadeClient.java` | n/a |
| K.3 | `/api/admin/ai-models/{id}/test` bleibt Cascade-Proxy (Cascade hat die Provider-SDKs) | `…/controller/ApiController.java` | offen |
| K.4 | `DataInitializer`: Seed aus `~/.claude/settings.json` (Migrations-Pfad) + Default-Chain als Fallback | NEU: `…/DataInitializer.java` | offen |
| K.4 | `ConfigService` reduzieren auf CLI-Operativ-Daten (`activeRoute`, `chain_position`) — UI-Configs raus | `…/service/ConfigService.java` | offen |
| K.5 | Frontend (Vanilla-HTML): **unverändert** — Vertrag bleibt gleich, UI ruft dieselben Endpoints | `frontend/index.html` | n/a |
| K.6 | Verification: `docker compose down -v && up --build`, CRUD-Smoke-Test, Isolation-Test (DB ↔ Cascade einzeln stoppen) | — | offen |

### Verifikations-Befehle

```bash
cd ~/Downloads/ki-projekte/claude-switcher
docker compose down -v && docker compose up --build

curl http://localhost:2000/api/admin/ai-models
curl -X POST http://localhost:2000/api/admin/ai-models \
  -H 'Content-Type: application/json' \
  -d '{"provider":"anthropic","modelId":"claude-opus-4-7","apiKeySettingKey":"anthropicApiKey","displayName":"Opus"}'

# Isolation
docker compose stop db        && curl http://localhost:2000/api/admin/ai-models  # → 500
docker compose start db       && docker compose stop llm-cascade
curl http://localhost:2000/api/admin/ai-models                                    # → 200
curl -X POST http://localhost:2000/api/admin/ai-models/1/test                     # → Cascade-Fehler
```

---

## Nach K — Phase L: `ki-models-ui` vereinheitlichen (analog `llm-cascade`)

> **User-Direktive 2026-05-14:** ki-models-ui muss **wie llm-cascade** wiederverwendbar
> aufgebaut + verteilt werden. llm-cascade ist die Vorlage:
>
> | Aspekt | llm-cascade | ki-models-ui (Soll) |
> |---|---|---|
> | Artefakt | Docker-Image | npm-Package |
> | Registry | GHCR public | npm-Registry public |
> | Konsument bindet via | `image:` in docker-compose | `"@dataclub/ki-models-ui"` in package.json |
> | Kontext-Isolation | eigene DB pro Konsument | API-Base-URL via `InjectionToken` |
> | Konsument-Logik außen | `LlmCascadeClient.java` | `@Output()`-Events |

**Stand:** Repo existiert (`~/Downloads/ki-projekte/ki-models-ui`, Commit `421ba00`),
L.1-Skeleton committed, **noch nicht published**, kein Konsument zieht sie.

Tasks:

| # | Task | Status |
|---|---|---|
| L.1 | Repo + ng-Library-Skeleton + 4 Components + Service + InjectionToken | ✅ done |
| L.2 | 2 fehlende Components ergänzen (mode-panel manual/auto-failover, key-card) | offen |
| L.3 | EduPro migrieren: `npm i @dataclub/ki-models-ui`, admin.component umschreiben | offen |
| L.4 | **Switcher Vanilla-HTML → Angular-Migration** + Library integrieren; Switcher-spezifisch (Quota-Banner + Restart-Button) bleibt außerhalb | offen |
| L.5 | **npm-Publish** + Git-Tag + README mit ASCII-Architektur-Bild | offen |
| L.6 | Doku-Spiegel: README in EduPro + Switcher mit „ki-models-ui-Integration"-Sektion + tools-site-Eintrag (`feedback_shared_lib_docs_propagation` greift) | offen |
| L.7 | Sanity-Check: Bug-Fix in Library → ein Release → `npm update` in beiden Konsumenten → Fix in beiden UIs sichtbar | offen |

Erst nach Abschluss Phase K starten. Details in
`~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/project_next_session.md`.

---

## Offene PRs

- [#7](https://github.com/4dataclub/claude-code-switcher/pull/7) — Backend-Prep für UI-Library (`cascade-models` CRUD + settings-proxy). **Prüfen + ggf. mergen vor Phase K** — die Erkenntnisse aus Phase K können den PR beeinflussen (Endpoints werden gegen lokale DB statt Cascade gebaut).
