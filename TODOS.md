# Switcher — Offene Todos

> **Source-of-Truth:** `~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/project_next_session.md`
>
> Diese Datei ist ein **Spiegel** der Brain-Tasks im Repo, damit beide
> Maintainer denselben Stand sehen — auch ohne Brain-Klon. Bei Konflikt: Brain gewinnt.
>
> **Pattern-übergeordnet:** Jedes 4dataclub-Repo bekommt eine `TODOS.md`.

Stand: 2026-05-24 — Phase K ✅, S'' PR #40 offen (cascade free-only/cloud)

---

## 🔵 Phase S'' — Cascade-Kategorien `free-only` + `cloud` — PR #40 OFFEN

> Switcher nutzt `free-only` / `cloud` (Kosten-Tier), **nicht** `utility`/`content` wie EduPro.
>
> ```
> ┌─ free-only ──────┐   ┌─ cloud ────────┐
> │ deepseek-free    │   │ claude-opus    │
> │ llama-3.3        │   │ gpt-4o         │
> │ gemma3           │   │ gemini-pro     │
> │ cooldown: 0s     │   │ cooldown: 32s  │ ← unabhängig
> └──────────────────┘   └────────────────┘
> ```

| # | Task | Status |
|---|---|---|
| S2.1 | DataInitializer: `default`→`cloud`, `fallback`→`free-only` | ✅ PR #40 |
| S2.2 | DB-Migration bestehender Einträge (idempotent) | ✅ PR #40 |
| S2.3 | Modell-Seed: cloud + free-only neu | ✅ PR #40 |
| S2.4 | Angular cascadeHints-Map auf neue Keys umgestellt | ✅ PR #40 |
| S2.5 | README Cascade-Diagramm | ✅ PR #40 |

---

## 🟢 Phase N — Ollama als Provider (Single-Host) — PLAN FREIGEGEBEN

> Plan-File: `~/.claude/plans/ich-werde-dir-fragen-lucky-pearl.md` (freigegeben 2026-05-16).
> Switcher-Anteile aus Phase N. Komplette Liste in Brain-TODOS.

| # | Task | Datei(en) | Aufwand |
|---|---|---|---|
| N.2 | Optional: Ollama-Service auch im Switcher-`docker-compose.yml` (analog llm-cascade-Repo, `profiles: ["local-llm"]`). Sinnvoll wenn Switcher als 2. Konsument lokales Modell-Routing braucht. | `docker-compose.yml` | 30min |
| N.7 | Switcher-Frontend (heute Vanilla-HTML) bekommt im Add-Model-Form ebenfalls die Standort-Dropdown-Option — sobald Phase L.4 Switcher auf Angular migriert, kommt es automatisch über die ki-models-ui-Library | später (an Phase L.4 gekoppelt) | n/a |

**Hinweis:** llm-cascade ist das Hauptarbeitsfeld für Phase N (Ollama-Bean + Container + Seed). Switcher ist nur sekundärer Konsument.

**Vorrang:** Phase K ✅ fertig — Ollama-Provider passt ohne Schema-Änderung in die bestehende `ai_model_config`-Tabelle.

---

## 💡 Idee 2026-05-15 — Ollama als 4. Provider (80/20-Routing)

> User-Diktat: „80% Routine lokal, 20% Hard-Tasks an Claude über Provider-Wahl."
> Video-Quelle: <https://www.youtube.com/watch?v=z_uzyXCSyPk> (Gemma 4 + Ollama).

**Was:** Lokales Ollama als zusätzlicher LlmProvider neben Anthropic/Google/OpenRouter.
Nutzt den **bereits geplanten `openai_compat`-Catch-All** — Ollama hat OpenAI-kompatible API auf `:11434/v1`.

| Aspekt | Implementation-Hinweis |
|---|---|
| Provider-Eintrag | `ollama` als Bean-Name auf `OpenAiCompatProvider` mit `baseUrl=http://host.docker.internal:11434/v1` |
| Modell-Default-Seed | `gemma-3:4b` (oder Gemma-4 sobald via Ollama verfügbar) |
| Cooldown | `null` — lokal hat kein Quota-Limit |
| `apiKeySettingKey` | `ollamaBaseUrl` (Wert ist URL, nicht Key) |
| Routing-Heuristik | Cascade-Order: Ollama zuerst (wenn aktiv) → Cloud-Fallback bei Confidence-Drop oder explizitem `:hard:`-Tag |
| docker-compose | optionalen `ollama`-Service (Profile `local-llm`) ergänzen, nicht im Default-Profile (Hardware-Anforderungen) |

Detail in Brain: [Idee — Lokale KI für 80% Routine, Claude für 20% Hard-Tasks](file:///Users/data/Documents/obsidian-brain/shared/01%20Inbox/Idee%20—%20Lokale%20KI%20für%2080%25%20Routine,%20Claude%20für%2020%25%20Hard-Tasks%20(Ollama%20+%20Gemma%204).md).

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

## ✅ Phase K — Eigener Modell-Context — FERTIG (verifiziert 2026-05-17)

> Switcher hat eigene PostgreSQL-DB (`switcher_pgdata` Volume), `AiModelConfig`-Entity +
> `AiModelConfigRepository` + `SwitcherModelService` + `DataInitializer`.
> Symmetrie mit EduPro hergestellt — llm-cascade ist reine Engine.

---

## 🟡 Phase L: `ki-models-ui` vereinheitlichen (analog `llm-cascade`) — AKTIV

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
| L.1 | Repo + ng-Library-Skeleton + Components + Service + InjectionToken | ✅ done |
| L.2 | 5 Components: models-table, add-model-form, cascade-cooldown, api-keys-section, failover-chain | ✅ done (v0.7.0) |
| L.3 | EduPro migriert: alle 5 `<ki-…>`-Components live | ✅ done |
| L.4 | Switcher Vanilla-HTML → Angular + Library-Integration + mode-panel/banner/status-bar | ✅ done |
| L.5 | **npm-Publish** auf GitHub Packages + Git-Tag | 🟡 offen — aktuell Tarballs |
| L.6 | Doku-Spiegel: tools-site ✅ — Switcher README offen | 🟡 teilweise |
| L.7 | Sanity-Check: Bug-Fix → Release → `npm update` → Fix sichtbar | offen (wartet auf L.5) |

Details in
`~/obsidian-brain/shared/04 Ressourcen/KI & Automatisierung/Claude Memory/project_next_session.md`.

---

## ✅ Abgeschlossene PRs

- [#7](https://github.com/4dataclub/claude-code-switcher/pull/7) — Backend-Prep für UI-Library (`cascade-models` CRUD + settings-proxy) — **merged 2026-05-17**
