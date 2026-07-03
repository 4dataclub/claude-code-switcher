# EduPro-Konsolidierung: alles Geteilte in `ki-models-ui` + `llm-cascade`

> **Für ausführende Worker:** Diese Plan-Datei wird mit der superpowers:executing-plans
> bzw. subagent-driven-development Skill abgearbeitet. Schritte nutzen Checkbox-Syntax (`- [ ]`).

**Goal:** Alles, was wir am Switcher gebaut haben (Supermodell-Matrix, Stats, Datenschutz-Toggle,
Live-Delegations-View), so in die geteilten Repos `ki-models-ui` (UI) + `llm-cascade` (Backend)
heben, dass **EduPro es ohne eigene „Sonderflocke" mitnutzen kann** — EduPro mountet künftig nur
`<ki-models-page>` und braucht keinen produktspezifischen UI-Code.

**Architecture:** `ki-models-ui` ist eine Angular-**Library** (kein App), konsumiert von zwei
Host-Apps: Switcher-`angular-frontend` (Base-URL `/api`) und EduPro-Admin (Base-URL `/api/admin`).
Die Library spricht ein generisches Backend-Contract über den `KI_MODELS_API_BASE`-Token; jeder
Host-Backend bedient dieselben relativen Pfade. Produktspezifische Zustände (aktiver Pool,
Supermodell an/aus) kommen als optionale `@Input`s — Default so, dass ein nacktes Mount
funktioniert (= keine Sonderflocke).

**Tech Stack:** Angular 17 standalone (ng-packagr Library), Spring Boot (llm-cascade :8091 +
Switcher-Backend :2000), Tailwind in den Hosts, Library bringt eigene scoped Styles mit.

## Global Constraints

- **Sicherheit Session:** Meine Claude-Session hängt an **Anthropic direkt** (nicht am Router).
  **NICHT** `/api/switch`, `/api/supermodel`, `/api/mode` oder `/api/preferred-category` POSTen —
  das schreibt `~/.claude/.switcher-restart` → Wrapper killt die Session. Pool/Supermodell nie
  live umschalten; alle Zustands-Checks per GET / Simulation.
- **Keine Sonderflocke bei EduPro:** Jede neue Library-Komponente muss mit reinem
  `<ki-…></ki-…>`-Mount (ohne Pflicht-Inputs) sinnvoll rendern. Produktspezifik nur als
  **optionale** Inputs mit Default.
- **Datenschutz-Default:** `logPromptSnippet` bleibt **Default AUS**. Toggle nie standardmäßig an.
- **Library-Verifikation:** Die Library hat **keine** Unit-Test-Infra (0 `.spec.ts`). Verifikation
  = `npm run build` (lib baut fehlerfrei) **+ Inkognito-Browser-Test** am Switcher auf
  `http://localhost:2000`. KEINE erfundenen Test-Frameworks einführen.
- **Key-erhaltend:** `app_settings` (API-Keys) nie löschen/anfassen. Reseeds nur key-erhaltend.
- **EduPro liegt NICHT auf diesem Rechner** (nur 3 Repos hier: claude-code-switcher, ki-models-ui,
  llm-cascade). EduPro-Adoption macht Djavid am anderen PC (Phase 5 = Checkliste, kein Code hier).

---

## ✅ EduPro-Quelle inspiziert (2026-06-24, Blocker aufgelöst)

EduPro shallow-geklont nach `~/Dokumente/KI-Projekte/edupro` (`github.com/4dataclub/edupro`,
read-only). Direkt am Code verifiziert — der vermutete Schema-Widerspruch ist **geklärt**:

**1. EduPros Kategorie-Schema ist `content` / `dev` / `utility` / `general` (Task-Typ), NICHT cloud/free/local.**
   (Von Djavid bestätigt + Prod-Screenshots 772-779, 2026-06-24.)
   - `AiModelConfig.java:52` — „Routing-Kategorie (Phase R)", null/leer → `general` (Z.123).
   - Admin-UI „Bereich"-Toggle = `Auto / Content / Dev / Utility / General` (Auto = Auto-Routing, kein
     gespeicherter Wert). Pro Bereich eine eigene Failover-Kaskade. Kein `cloud`/`free`/`local`,
     **keine** Rollen-Achse. Beschreibungen: Content=„Qualität zählt", Dev=„Code/Agent/PR-Review,
     stärkeres Reasoning", Utility=„Übersetzungen/Verifier — nur lokal (Ollama), keine Daten verlassen
     den Server", General=„Fallback wenn spezifischere Kaskaden erschöpft".

**2. EduPro hat KEIN Supermodell-Konzept.** Grep über das ganze Repo (Backend + Frontend):
   **null** Treffer für `supermodel`, `orchestrator`, `implement/review/research/dispatch`-Rollen oder
   eine Rollen×Pool-Matrix. EduPro macht reines **Kategorie-Routing** (preferredCategory-Override über
   `<ki-cascade-mode-panel>`) — dieselbe **llm-cascade-Substrat-Mechanik** wie der Switcher, aber mit
   **anderen Kategorie-Werten** und ohne Rollen/Pools.

**⇒ Korrektur zu Djavids Annahme** („Supermodell läuft bei EduPro bereits, cloud/free/local"): Geteilt
ist der **preferredCategory-Routing-Substrat** (gleiche Endpoints, gleiches `<ki-cascade-mode-panel>`),
**nicht** das Supermodell. Supermodell (Rollen×Pools, cloud/free/local, Compound-Kategorien) ist heute
**rein Switcher**. EduPro kann es erst nutzen, wenn die Matrix in die Library wandert — und das geht
snowflake-frei nur, wenn die Komponente **generisch über (Pools, Rollen, Kategorien)** ist und ihre
Achsen **aus der Backend-Kategorienliste ableitet**, statt switcher-Namen hartzucodieren (siehe Task 5).

**EduPro mountet heute** (Base `/api/admin`): `ki-cascade-mode-panel`, `ki-cascades-view`,
`ki-cascade-cooldown`, `ki-models-table`, `ki-add-model-form`, `ki-provider-servers`,
`ki-api-keys-section`, `ki-models-quality-stats`, `ki-models-performance`, `ki-models-cooldown-state`
(10 Komponenten). **Kein** Supermodell-Matrix, **kein** Privacy-Toggle, **keine** Delegations-Live.

**Architektur-Präzisierung (Djavid, 2026-06-24, jetzt am Code bestätigt):** Switcher-spezifisch ist
**nur** die **Terminal-Wrapper-Integration** — Restart-Marker `~/.claude/.switcher-restart`,
`claude-auto` mit gewähltem Pool/Rollen-Kontext starten, Quota-Banner + Restart-Button. Geteilt
(gehört in Library + llm-cascade): Modell-Verwaltung, preferredCategory-Routing **und** — neu zu
generalisieren — die Supermodell-Matrix. Im Switcher-Host bleibt nur die Terminal-Chrome als Wrapper
um `<ki-models-page>`.

**🧱 LEITPRINZIP „gleiche Basis, UI nur Zusatz" (Djavid, 2026-06-24):** Die geteilte **Basis** (Logik,
Routing-Mechanik, Datenmodell, Komponenten-Code) ist für **beide** Produkte **identisch und immer
vorhanden** — auch das Supermodell. „Supermodell AUS" bzw. „EduPro zeigt es nicht" heißt **nicht
abwesend**, sondern **nicht gerendert**: die `<ki-supermodel-matrix>` + `supermodelOn`-Input + die
Compound-Kategorie-Behandlung in llm-cascade sind im selben Basis-Bundle für beide drin und tun nichts,
wenn ungenutzt. **UI ein-/ausblenden ist nur die dünne Host-Schicht obendrauf.** Der einzige
produktspezifische Unterschied ist **Daten/Konfig, kein Code-Pfad**: (a) ob ein Produkt die
Compound-Kategorien seedet, (b) ob der Host die Matrix mountet/zeigt. Beides sind Schalter über
derselben Basis → **keine Sonderflocke**. Wenn ein Verhalten je Produkt abweicht, gehört es als
**Input/Flag** in die Basis, nicht als Fork.

**UI-Fixes EduPro→Switcher mit-konsolidieren (Djavid, 2026-06-24):** Der Switcher nutzt heute ein
**eigenes** `sw-mode-panel` (`ModePanelComponent`) statt der Library-`<ki-cascade-mode-panel>` — daher
fehlt ihm der **Auto-Tab** (seit Library v0.14.1 vorhanden) und der Toggle-Tab-Name stimmt nicht
zwingend mit dem Cascade-Block-Titel überein. EduPro hat beides, weil es die Library-Komponente mountet
(Toggle-Name = `categoryTitles` via `labelFor()`). → Beim Host-Swap (Task 10) den Switcher auf die
geteilte `<ki-cascade-mode-panel>` umstellen und **denselben `categoryTitles`-Bund** an Toggle UND
`<ki-cascades-view>` geben → Auto-Tab + Namensgleichheit fallen automatisch ab. Switcher ist auf
Library **v0.15.0** (vendored tgz), Repo ist **v0.16.0** → Bump auf v0.17.0 zieht ohnehin alle
zwischenzeitlichen UI-Fixes mit.

---

## Current State (vor Plan-Ausführung verifiziert am 2026-06-24)

- `research-local` ist **bereits geseedet** — `GET :8091/api/categories` listet alle 18 Compound-
  Kategorien inkl. `research-local`. Der Seeder-Teil des alten `research-local`-Plans ist erledigt;
  offen bleibt nur die **Policy-Text-Korrektur** (Agent + Doku + Matrix-Leerzelle).
- `logPromptSnippet`-Feature ist in `llm-cascade` **codiert, aber UNCOMMITTED**
  (`LlmCallLog.java`, `SettingsService.java`, `LlmCascadeService.java`).
- Watcher-Skripte (`switcher-watch.sh`, `delegator-watch.sh`) sind **fertig, aber UNTRACKED**.
- `ki-models-ui` ist **v0.16.0**; Switcher vendort noch **v0.15.0** (`vendor/4dataclub-ki-models-ui-0.15.0.tgz`).
- llm-cascade hat schon generische Settings-Endpoints: `GET /api/settings`, `POST /api/settings/{key}`.
- Switcher-Backend proxyt Settings als `GET /api/cascade-settings` + `POST /api/cascade-settings/{key}`.
- Switcher rendert schon die meisten Stat-Komponenten; die Supermodell-Matrix ist aktuell
  **inline im Switcher-Host** (`app.component.ts` Z.~102-138 + Felder `ROLES`/`roleMeta`/`POOLS`/
  `poolTitles`/`poolHints`/`cellModels`/`activePool`/`supermodel`).

---

## Decisions to lock with Djavid (Phase 0, morgen, vor Codebeginn)

1. **Privacy-Toggle Backend-Contract.** Damit beide Hosts es ohne Snowflake bedienen, definiert die
   Library `GET {base}/settings` + `POST {base}/settings/{key}`. Switcher hat darunter schon den
   Proxy (`cascade-settings`) → braucht nur einen **dünnen Alias** `/api/settings(/{key})`. EduPro
   ergänzt am anderen PC `/api/admin/settings(/{key})`. **Alternative** (kein Backend-Change): Toggle
   über das bestehende `api-keys/setting/{key}` schieben — aber dann taucht es fälschlich in der
   Key-Liste auf. → **Empfehlung: eigener `/settings`-Contract.**
2. **Supermodell-Matrix `activePool`/`supermodelOn`.** Für „null Snowflake" leitet die Matrix
   `activePool` selbst aus `getPreferredCategory()` ab (Pool = preferredCategory in llm-cascade,
   beide Backends können das). `supermodelOn` ist **optionaler Input** (Default `true` = Matrix
   sichtbar); der Switcher-Host füttert sein `/api/status`-Signal rein, EduPro lässt's weg.
   → **Bestätigen: Matrix immer sichtbar by default?**
3. **Live-Delegations-View.** Neue Library-Komponente `<ki-delegation-live>` (liest
   `GET {base}/stats/calls`, Auto-Refresh) statt Bash-Watcher zu portieren. Bash-Skripte bleiben
   Switcher-Dev-Tool. → **Bestätigen: Browser-Komponente gewünscht?**
4. **Supermodell-AN/AUS in EduPro einführen (Djavid-Wunsch, 2026-06-24).** Machbar, aber NICHT
   gratis — EduPro hat heute kein Supermodell. Was es braucht:
   - **UI:** schon abgedeckt — die generische `<ki-supermodel-matrix>` (Task 5) + ein Toggle-Input
     `supermodelOn`. EduPro mountet beides zusätzlich, kein Snowflake-Code.
   - **Routing-Mechanik:** „Supermodell AN/AUS" ist im Kern **nur `preferredCategory`** in llm-cascade
     (das gleiche Knopf, den EduPro für Content/Dev/Utility/General schon nutzt). AN = preferredCategory
     auf eine Compound-Kategorie `<rolle>-<pool>`; AUS = leer (Semantic Routing).
   - **Backend-Seed:** EduPro muss die Compound-Kategorien (`<rolle>-<pool>`) in seinem
     `DataInitializer` seeden — sonst sind die Matrix-Zellen leer. **Das ist der einzige echte
     EduPro-Backend-Aufwand.**
   - **Constraint:** `preferredCategory` ist EIN globaler Override. EduPros Bereich-Override
     (Content/Dev/Utility/General) und ein Supermodell-Pool-Override teilen sich diesen einen Knopf →
     sie sind **gegenseitig exklusiv** (entweder Task-Typ-Routing ODER Rollen×Pool-Routing aktiv, nicht
     beides gleichzeitig). → **Entscheiden: Supermodell als zusätzlicher Modus neben den 4 Bereichen,
     oder ersetzt es sie?** (Produktentscheidung, am anderen PC.)

---

## File Structure

**`ki-models-ui` (shared library) — Neu/Geändert:**
- Modify: `projects/ki-models-ui/src/lib/services/ki-models-api.service.ts` — `getSettings()`,
  `setSetting()`, `getDelegationCalls()` hinzufügen.
- Create: `projects/ki-models-ui/src/lib/models/app-setting.ts` — `AppSetting`-Interface.
- Create: `projects/ki-models-ui/src/lib/models/delegation-call.ts` — `DelegationCall`-Interface.
- Create: `projects/ki-models-ui/src/lib/components/privacy-settings.component.ts` — `<ki-privacy-settings>`.
- Create: `projects/ki-models-ui/src/lib/components/supermodel-matrix.component.ts` — `<ki-supermodel-matrix>`.
- Create: `projects/ki-models-ui/src/lib/components/delegation-live.component.ts` — `<ki-delegation-live>`.
- Create: `projects/ki-models-ui/src/lib/components/models-page.component.ts` — `<ki-models-page>` (komponiert alles).
- Modify: `projects/ki-models-ui/src/public-api.ts` — neue Exporte.
- Modify: `projects/ki-models-ui/package.json` — Version → `0.17.0`.

**`llm-cascade` (shared backend):**
- Bereits codiert (nur committen): `LlmCallLog.java`, `SettingsService.java`, `LlmCascadeService.java`.
- Modify: `agents/supermodel.md` (im switcher-Repo gespiegelt) — research-local-Policy.
- (Settings-Endpoints existieren bereits — kein Backend-Change nötig.)

**`claude-code-switcher` (Host + Backend + Docs):**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java` —
  `/api/settings(/{key})`-Alias + `/api/stats/calls`-Proxy (falls fehlt).
- Modify: `angular-frontend/src/app/app.component.ts` — Matrix-Inline-Block ersetzen durch
  `<ki-models-page>`, switcher-eigene Chrome (status-bar/banner/mode-panel/restart) als Wrapper behalten.
- Modify: `angular-frontend/package.json` + Create: `angular-frontend/vendor/4dataclub-ki-models-ui-0.17.0.tgz`.
- Modify: `README.md`, `SUPERMODELL.md`, `agents/supermodel.md`.
- Wrapper-Skripte committen: `wrapper/switcher-watch.sh`, `wrapper/delegator-watch.sh`.

---

## Phase 1 — llm-cascade: bestehende Arbeit sichern + Policy-Texte

### Task 1: logPromptSnippet committen (Code existiert schon)
**Files:** `llm-cascade/src/main/java/com/dataclub/llmcascade/{model/LlmCallLog.java,service/SettingsService.java,service/LlmCascadeService.java}`

- [ ] **Schritt 1:** `git -C ~/Dokumente/KI-Projekte/llm-cascade status` + `git diff` — bestätigen, dass
      nur die 3 erwarteten Dateien geändert sind (Datenschutz-Snippet-Feature).
- [ ] **Schritt 2:** Feature-Branch `git -C … checkout -b feat/log-prompt-snippet`.
- [ ] **Schritt 3:** Committen (HEREDOC-Message; Co-Author wie üblich). Botschaft: Datenschutz-Toggle
      `logPromptSnippet` (Default AUS), Prompt-Snippet nur bei explizitem Opt-in in `llm_call_log.prompt_snippet`.
- [ ] **Schritt 4:** `git log --oneline -1` zur Verifikation.

### Task 2: research-local-Policy in der Doku korrigieren (Seeder ist schon erledigt)
**Files:** `claude-code-switcher/agents/supermodel.md`, `claude-code-switcher/SUPERMODELL.md`,
`~/.claude/agents/supermodel.md` (Maschinen-Kopie), `~/Dokumente/brain/02 Projekte/Claude Code Switcher.md`

Inhalt der Korrektur (aus dem alten gespeicherten research-local-Plan, unverändert gültig):
- research-Zeile local: nicht mehr „REFUSE / nicht im Local-Pool", sondern: *route an `research-$POOL`
  (lokales Modell); verarbeite lokale Docs + interne/VPN-Ressourcen; NIEMALS öffentliches Web/Cloud/
  Gemini; braucht die Aufgabe zwingend öffentliches Web → REFUSE mit „Public-Web-Research nicht im
  Local-Pool — fail-closed, nichts verlässt das interne Netz".*
- fail-closed-Grenze: „must not leave **the machine**" → „must not leave the **internal network**".

- [ ] **Schritt 1:** `agents/supermodel.md` Z.~28 (research local) + Z.~42 (fail-closed) anpassen.
- [ ] **Schritt 2:** `SUPERMODELL.md` research-Zellen + „🔒 Lokal = fail-closed"-Abschnitt: Grenze als
      **internes Netz-Perimeter** (offline-fähig; Intranet/VPN ok; nichts ins öffentliche Web/Cloud).
- [ ] **Schritt 3:** `~/.claude/agents/supermodel.md` mit der Repo-Version synchronisieren (`cp`).
- [ ] **Schritt 4:** brain-Note research-Zeile + 8GB-Prosa angleichen.
- [ ] **Schritt 5:** Auf Switcher-Feature-Branch committen (siehe Phase 4).

---

## Phase 2 — ki-models-ui: geteilte Komponenten bauen

> **Konvention:** Jede neue Komponente spiegelt das Muster von
> `components/models-quality-stats.component.ts` (standalone, `inject(KiModelsApiService)`,
> Signals, scoped `styles`, graceful 404-Fallback). Verifikation jeweils per `npm run build`.

### Task 3: API-Service erweitern
**Files:** Modify `services/ki-models-api.service.ts`; Create `models/app-setting.ts`, `models/delegation-call.ts`

- [ ] **Schritt 1:** `models/app-setting.ts`: `export interface AppSetting { key: string; value: string; }`.
- [ ] **Schritt 2:** `models/delegation-call.ts`: Interface passend zu `/api/stats/calls`-Items:
      `{ id:number; calledAt:string; provider:string|null; model:string|null; service:string|null;
      success:boolean; outputChars:number|null; promptSnippet:string|null; }`.
- [ ] **Schritt 3:** Service-Methoden ergänzen:
  ```typescript
  getSettings(): Observable<AppSetting[]> {
    return this.http.get<any>(`${this.base}/settings`).pipe(
      map((r): AppSetting[] => Array.isArray(r)
        ? r.map(x => ({ key: x.key ?? x.settingKey, value: String(x.value ?? '') }))
        : []),
    );
  }
  setSetting(key: string, value: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.base}/settings/${encodeURIComponent(key)}`, { value });
  }
  getDelegationCalls(): Observable<DelegationCall[]> {
    return this.http.get<DelegationCall[]>(`${this.base}/stats/calls`);
  }
  ```
- [ ] **Schritt 4:** `npm run build` (im ki-models-ui Repo) — fehlerfrei.

### Task 4: `<ki-privacy-settings>` (Datenschutz-Toggle, B)
**Files:** Create `components/privacy-settings.component.ts`

- [ ] **Schritt 1:** Standalone-Komponente, Muster wie quality-stats. Liest beim `ngOnInit`
      `getSettings()`, findet `logPromptSnippet`, setzt ein Signal `enabled`. Toggle ruft
      `setSetting('logPromptSnippet', enabled ? 'true' : 'false')`. **Default-Darstellung AUS**,
      wenn Setting fehlt/404. Begleittext: „Speichert pro Delegations-Call einen gekürzten
      Prompt-Ausschnitt (max. 160 Zeichen) — nur für Debug/Live-Watch. Standard: AUS (Datenschutz)."
- [ ] **Schritt 2:** Inputs: `@Input() title`, `@Input() subtitle` (überschreibbar, deutsche Defaults).
- [ ] **Schritt 3:** `npm run build` — fehlerfrei.

### Task 5: `<ki-supermodel-matrix>` (Matrix konsolidieren)
**Files:** Create `components/supermodel-matrix.component.ts`; Quelle zum Spiegeln:
`claude-code-switcher/angular-frontend/src/app/app.component.ts` (Matrix-Block + `ROLES`/`roleMeta`/
`POOLS`/`poolTitles`/`poolHints`/`cellModels`/`categoryTitles`).

- [ ] **Schritt 1:** Switcher-Matrix-Logik in die Komponente übernehmen. Modell-Daten generisch via
      `api.listModels()` + `api.listCategories()` (Zelle = Modelle mit `category === role + '-' + pool`).
- [ ] **Schritt 2:** Achsen **konfigurierbar** machen (EduPro hat heute weder Rollen noch cloud/free/local —
      die Komponente darf nicht auf Switcher-Namen hartcodiert sein):
      `@Input() pools: string[] = ['cloud','free','local']`, `@Input() roles: string[] =
      ['orchestrator','implement','review','research','dispatch']`. **Fallback:** wenn nichts gesetzt,
      Achsen aus der Backend-Kategorienliste ableiten (Compound-Kategorien `<role>-<pool>` zerlegen).
      Enthält das Backend **keine** Compound-Kategorien (EduPro-Fall: nur `utility/content/general`) →
      Matrix rendert leeren/ausgeblendeten Zustand statt zu crashen.
- [ ] **Schritt 3:** Inputs (alle optional, Defaults für nacktes Mount):
      `@Input() activePool?: string` (wenn nicht gesetzt → selbst via `getPreferredCategory()` ableiten);
      `@Input() supermodelOn = true`; `@Input() localOrchestratorPending = false` (Switcher-spezifische
      Warnung, EduPro lässt weg); `@Input() labels?` (Rollen-/Pool-Texte, Default deutsch).
- [ ] **Schritt 4:** **`@Input() disabled = false`** (Djavid 2026-06-24): Matrix + Toggle werden
      **sichtbar gerendert, aber gesperrt** (Buttons `disabled`, gedimmt, kein Klick) statt versteckt.
      So ist die UI für beide Produkte identisch — EduPro mountet sie mit `disabled=true` und sieht die
      Fähigkeit, kann sie aber noch nicht nutzen. Optionaler `@Input() disabledHint?` (Default z.B.
      „Supermodell — demnächst verfügbar"). Wenn `disabled` → keine `setPreferredCategory`-Calls, nur
      Anzeige. (Switcher: `disabled=false`. Visibility-Verstecken bleibt zusätzlich über `*ngIf` im Host
      möglich, ist aber NICHT der Default — Default ist „zeigen + sperren".)
- [ ] **Schritt 5:** research-Leerzelle mit **korrigierter** Policy (siehe Task 2) — nicht mehr
      „Web = Cloud, fail-closed", sondern lokal/intern erlaubt, nur öffentliches Web verweigert.
- [ ] **Schritt 6:** `npm run build` — fehlerfrei.

### Task 6: `<ki-delegation-live>` (Browser-Watcher für beide Produkte)
**Files:** Create `components/delegation-live.component.ts`

- [ ] **Schritt 1:** Standalone-Komponente: `api.getDelegationCalls()`, Auto-Refresh (z.B. 5s),
      zeigt pro Call: Zeit, ✓/✗, `provider:model`, `[service]`, Output-Chars, und — falls vorhanden —
      `promptSnippet` (nur befüllt wenn logPromptSnippet AN). Graceful 404 → leerer Zustand.
- [ ] **Schritt 2:** `@Input() autoRefreshSec = 5`, `@Input() maxRows = 50`, `@Input() title/subtitle`.
- [ ] **Schritt 3:** `npm run build` — fehlerfrei.

### Task 7: `<ki-models-page>` (komponierte Seite — der Single-Source-Einstieg)
**Files:** Create `components/models-page.component.ts`

- [ ] **Schritt 1:** Standalone-Komponente, importiert + rendert in **fester kanonischer Reihenfolge**:
      Verwaltung zuerst (cascade-cooldown, cascades-view, models-table, add-model-form, api-keys-section,
      **privacy-settings**), dann Supermodell (**supermodel-matrix**), dann Statistiken (provider-servers,
      quality-stats, performance, cooldown-state, routing-decisions, **delegation-live**).
- [ ] **Schritt 2:** Pass-Through-Inputs: ein optionales `labels`-Bündel + die wenigen Matrix-Inputs
      (`activePool`, `supermodelOn`, `localOrchestratorPending`). Re-emittiert die Events, die Hosts
      brauchen: `(modelChanged)`, `(activeModelChanged)` (für den Switcher-Switch).
- [ ] **Schritt 3:** Nacktes `<ki-models-page>` (ohne Inputs) muss rendern (= EduPro-Default, keine Snowflake).
- [ ] **Schritt 4:** `npm run build` — fehlerfrei.

### Task 8: Exporte + Version + tgz
**Files:** Modify `public-api.ts`, `package.json`

- [ ] **Schritt 1:** In `public-api.ts` alle neuen Komponenten + Modelle exportieren.
- [ ] **Schritt 2:** `package.json` Version → `0.17.0`.
- [ ] **Schritt 3:** `npm run build` → dann `cd dist/ki-models-ui && npm pack` → erzeugt
      `4dataclub-ki-models-ui-0.17.0.tgz`.
- [ ] **Schritt 4:** Feature-Branch committen (siehe Phase 4).

---

## Phase 3 — Switcher: Backend-Alias + Host auf `<ki-models-page>` umstellen

### Task 9: Backend-Endpoints für den neuen Library-Contract
**Files:** Modify `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java`

- [ ] **Schritt 1:** `GET /api/settings` + `POST /api/settings/{key}` als **dünne Aliase** auf die
      bestehenden `cascadeSettings()` / `setCascadeSetting()` (Z.1029/1037) hinzufügen — damit die
      Library `{base}/settings` trifft.
- [ ] **Schritt 2:** `GET /api/stats/calls` als Proxy zu llm-cascade `/api/stats/calls` ergänzen
      (für `<ki-delegation-live>`); Fallback leeres Array wenn Cascade nicht erreichbar.
- [ ] **Schritt 3:** Build des Switcher-Backends (`./gradlew build` bzw. wie im Repo üblich) — grün.

### Task 10: Host auf die Library-Seite umstellen
**Files:** Modify `angular-frontend/package.json` (+ vendor tgz), `angular-frontend/src/app/app.component.ts`

- [ ] **Schritt 1:** Neue tgz nach `angular-frontend/vendor/4dataclub-ki-models-ui-0.17.0.tgz` kopieren;
      `package.json`-Dependency auf `file:vendor/4dataclub-ki-models-ui-0.17.0.tgz` bumpen; `npm install`.
- [ ] **Schritt 2:** In `app.component.ts` den **Inline-Matrix-Block + die Einzel-`<ki-*>`-Sektionen**
      durch ein einziges `<ki-models-page [activePool]="activePool()" [supermodelOn]="supermodel()"
      [localOrchestratorPending]="…" (activeModelChanged)="onSwitchToModel($event)"
      (modelChanged)="reload()"></ki-models-page>` ersetzen.
- [ ] **Schritt 3:** Switcher-**Chrome behalten** als Wrapper außen herum: `sw-status-bar`,
      `sw-banner`, `sw-mode-panel`, Restart-Button. (Diese bleiben switcher-only.)
- [ ] **Schritt 4:** Tote switcher-only Matrix-Felder/Helfer entfernen, die jetzt in der Library leben
      (`ROLES`/`roleMeta`/`cellModels`/`poolTitles`/… nur falls nirgends sonst genutzt).
- [ ] **Schritt 5:** `npm run build` des Frontends — fehlerfrei.

### Task 11: Browser-Verifikation (golden path + edge)
- [ ] **Schritt 1:** Switcher-Container rebuilden/hochfahren (Backend + Frontend).
- [ ] **Schritt 2:** Inkognito `http://localhost:2000`: Seite rendert identisch wie vorher; Matrix da;
      neue Datenschutz-Karte (Default AUS); Delegation-Live-Karte da.
- [ ] **Schritt 3:** Datenschutz-Toggle AN → ein Test-Delegations-Call (über vorhandene Mittel, **ohne**
      `/api/switch`!) → `promptSnippet` erscheint im Live-View; Toggle AUS → kein Snippet mehr.
- [ ] **Schritt 4:** Regression: Modell-CRUD, API-Keys, Cascade-View, Stats unverändert funktionsfähig.

---

## Phase 4 — Doku + Git (pro Repo Feature-Branch, dann PRs)

> gh CLI ist NICHT installiert. Optionen: (a) gh via brew installieren + `GH_TOKEN` aus gespeicherten
> Credentials, oder (b) Branches pushen und PR-Erstell-Links ausgeben. **Vor dem Push an die geteilten
> 4dataclub-Repos Djavid bestätigen lassen** (shared-state, hohe Blast-Radius).

### Task 12: READMEs + Docs aktualisieren
- [ ] `ki-models-ui/README.md`: neue Komponenten (`ki-models-page`, `ki-supermodel-matrix`,
      `ki-privacy-settings`, `ki-delegation-live`) + neue Service-Methoden + Backend-Contract `/settings`, `/stats/calls`.
- [ ] `ki-models-ui/examples/edupro-integration.md` + `switcher-integration.md`: auf `<ki-models-page>` umstellen.
- [ ] `llm-cascade/README.md`: `logPromptSnippet`-Datenschutz-Abschnitt (Default AUS, Opt-in, Spalte `prompt_snippet`).
- [ ] `claude-code-switcher/README.md`: `switcher-watch.sh` (vereint UI/Router/Deleg) dokumentieren.
- [ ] `SUPERMODELL.md`: Testergebnisse (5/5 local grün; cloud/free brauchen API-Keys) + research-local-Policy.
- [ ] brain-Note `02 Projekte/Claude Code Switcher.md`: Stand festhalten.

### Task 13: Commits + PRs
- [ ] Pro Repo Feature-Branch committen (llm-cascade: `feat/log-prompt-snippet`; ki-models-ui:
      `feat/shared-models-page`; switcher: `feat/edupro-shared-consolidation`).
- [ ] Push + PRs gegen `4dataclub/*` (nach Djavids OK). PR-Bodies: Summary + Test-Plan.

---

## Phase 5 — EduPro-Adoption (anderer PC, Djavid führt aus — Checkliste, kein Code hier)

- [ ] `npm i @4dataclub/ki-models-ui@0.17.0` (bzw. tgz) im EduPro-`angular-frontend`.
- [ ] **REGRESSIONS-GARANTIE zuerst prüfen:** nach dem Bump läuft EduPros bestehende **Bereich-Wahl
      (Content/Dev/Utility/General)** 1:1 weiter — die Konsolidierung ist für EduPro **rein additiv**.
      Bestehende Komponenten-APIs (`ki-cascade-mode-panel`, `ki-models-table`, `ki-api-keys-section` …)
      dürfen nicht brechen. Inkognito-Test: Bereich-Toggle setzt weiterhin korrekt `preferredCategory`,
      Kaskaden/Stats unverändert. (Begründung: gesperrte Matrix macht **keine** API-Calls; EduPro seedet
      **keine** Compound-Kategorien → Supermodell-Mechanik bleibt inert; Bereich-Toggle bleibt einziger
      Schreiber von `preferredCategory` → kein Konflikt.)
- [ ] KI-Modelle-Tab-Body durch `<ki-models-page></ki-models-page>` ersetzen (nacktes Mount, keine Snowflake).
- [ ] EduPro-Backend: `GET/POST /api/admin/settings(/{key})` + `GET /api/admin/stats/calls` ergänzen
      (analog zu Switcher Task 9), beide fronten dieselbe llm-cascade.
- [ ] **Supermodell-Matrix sichtbar, aber gesperrt:** EduPro mountet sie mit `[disabled]="true"` (zeigt
      die Fähigkeit „demnächst", kein Routing-Eingriff). Aktivieren später = (1) Compound-Kategorien
      `<rolle>-<pool>` in EduPros `DataInitializer` seeden + (2) `disabled=false`. Produktentscheidung
      „neben vs. statt den 4 Bereichen" (Plan-Decision Nr. 4) vorher klären.
- [ ] Inkognito-Browser-Test im EduPro-Admin.

---

## Verification (Gesamt)

- `ki-models-ui`: `npm run build` grün; `public-api.ts` exportiert alle neuen Symbole.
- Switcher: Backend-Build grün; Frontend-Build grün; Inkognito-Test auf :2000 (Matrix, Datenschutz-
  Toggle Default AUS, Delegation-Live, CRUD/Stats-Regression).
- llm-cascade: `GET :8091/api/categories` zeigt weiterhin alle 18 inkl. `research-local`;
  `GET :8091/api/settings` enthält `logPromptSnippet` (Default `false`).
- Datenschutz-Beweis: Toggle AUS → `prompt_snippet` bleibt `null`; AN → max. 160 Zeichen.
- `app_settings`/Keys unangetastet (Count > 0, Keys in UI da).
- EduPro-Adoption ist **reines Mount + 2 Backend-Aliase** — kein produktspezifischer UI-Code (Ziel: keine Sonderflocke).