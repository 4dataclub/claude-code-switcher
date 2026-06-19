import { Component, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ModelsTableComponent,
  AddModelFormComponent,
  CascadesViewComponent,
  ApiKeysSectionComponent,
  ModelsQualityStatsComponent,
  ModelsPerformanceComponent,
  ModelsCooldownStateComponent,
  ProviderServersComponent,
  CascadesViewLabels,
  FailoverChainLabels,
  ProviderServersLabels,
} from '@4dataclub/ki-models-ui';
import { SwitcherApiService, SwitcherStatus, SwitcherAiModel } from './services/switcher-api.service';
import { StatusBarComponent } from './components/status-bar.component';
import { BannerComponent } from './components/banner.component';
import { ModePanelComponent } from './components/mode-panel.component';
import {
  MODELS_TABLE_LABELS_DE,
  ADD_MODEL_FORM_LABELS_DE,
  CASCADES_VIEW_LABELS_DE,
  FAILOVER_CHAIN_LABELS_DE,
  API_KEYS_SECTION_LABELS_DE,
  PROVIDER_SERVERS_LABELS_DE,
} from './labels.de';

/**
 * Switcher Angular-App — Phase L.4 (Vanilla abgelöst, Angular ist alleinige UI auf :2000).
 *
 * Look-and-Feel: **exakt wie EduPro Admin-Tab „KI-Modelle"** — Tailwind, slate-50/
 * slate-950 Page-BG, rounded-[40px] weiße bzw. dark-slate-900 Cards, gemeinsame
 * `@4dataclub/ki-models-ui` Library-Components. Switcher-spezifische Ergänzung:
 * der **Modus-Panel** oben (Manuell vs. Auto-Failover + Chain-Editor) plus
 * Status-Bar, Banner und Restart-Button.
 *
 * Das alte dunkle Provider-Grid („AKTIVER ANBIETER (MANUELL)") ist entfernt —
 * Modell-Auswahl + Cascade-Verwaltung passieren ausschließlich über die Library-
 * Components, identisch zu EduPro.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    StatusBarComponent,
    BannerComponent,
    ModePanelComponent,
    ModelsTableComponent,
    AddModelFormComponent,
    CascadesViewComponent,
    ApiKeysSectionComponent,
    ModelsQualityStatsComponent,
    ModelsPerformanceComponent,
    ModelsCooldownStateComponent,
    ProviderServersComponent,
  ],
  template: `
    <main class="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8 space-y-6">
      <header class="mb-2">
        <h1 class="text-2xl font-extrabold tracking-tight text-slate-950 dark:text-slate-50">
          Claude Code Switcher
        </h1>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
          API-Anbieter, Modell und Auto-Failover für Claude Code — gemeinsame Verwaltung mit EduPro.
        </p>
      </header>

      <sw-status-bar [status]="status()"></sw-status-bar>

      <sw-banner
        [warn]="warn()"
        [recheck]="recheck()"
        [autoMode]="status()?.mode === 'auto'"
        (switchNow)="onSwitchNow()"
        (promoteNow)="onPromote()"
      ></sw-banner>

      <!-- Switcher-spezifische Sektion: Manuell vs. Auto-Failover + Chain-Editor -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400 mb-4">
          Modus
        </h2>
        <sw-mode-panel
          [mode]="status()?.mode ?? 'manual'"
          [activeProvider]="status()?.provider ?? null"
          [activeModel]="activeModel()"
          [availableModels]="availableModels()"
          [categories]="POOLS"
          [activeCategory]="activePool()"
          [categoryTitles]="poolTitles"
          [categoryHintMap]="poolHints"
          [supermodel]="supermodel()"
          (modeChanged)="onModeChange($event)"
          (promoteRequested)="onPromote()"
          (switchTo)="onSwitchTo($event)"
          (categoryChanged)="onPoolChange($event)"
          (supermodelChanged)="onSupermodelChange($event)"
        ></sw-mode-panel>
      </section>

      <!-- Rollen pro Pool — erscheinen NUR bei Supermodell AN (additiv, im aktiven
           Pool), verschwinden bei AUS. Jede Rolle = Compound-Kategorie {rolle}-{pool}
           mit ihrer Failover-Kette ① ②. -->
      <section *ngIf="supermodel()" class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400 mb-1">
          Rollen im Pool „{{ poolTitles[activePool()] }}"
        </h2>
        <p class="text-xs text-slate-500 dark:text-slate-400 mb-4">
          Opus plant, delegiert pro Schritt an die günstigste Rolle (über <code class="px-1 rounded bg-slate-100 dark:bg-slate-800">&#64;supermodel</code>) und prüft am Ende. ① ② = Failover-Kette mit Cooldown.
        </p>

        <p *ngIf="activePool() === 'local' && localOrchestratorPending()"
           class="mb-4 rounded-2xl bg-amber-50 dark:bg-amber-950/40 ring-1 ring-amber-300 dark:ring-amber-800 px-4 py-3 text-xs font-semibold text-amber-700 dark:text-amber-300">
          ⚠ Lokaler Orchestrator gewählt, aber kein lokales Modell aktiv — <strong>fail-closed</strong> (kein automatischer Cloud-Ausweich). Ollama-Modell ziehen + aktivieren.
        </p>

        <div class="grid gap-3 sm:grid-cols-2">
          <div *ngFor="let role of ROLES" class="rounded-2xl bg-slate-50 dark:bg-slate-800 p-4 ring-1 ring-slate-200 dark:ring-slate-700">
            <div class="flex items-baseline justify-between gap-2">
              <strong class="text-sm font-bold text-slate-900 dark:text-slate-100">{{ roleMeta[role].label }}</strong>
              <code class="text-[10px] text-slate-400">{{ role }}-{{ activePool() }}</code>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-2">{{ roleMeta[role].desc }}</p>
            <ng-container *ngIf="cellModels(role).length; else emptyCell">
              <div *ngFor="let m of cellModels(role); let i = index" class="text-xs font-mono text-slate-700 dark:text-slate-300">
                {{ i + 1 }}. {{ m.displayName }}
                <span class="text-slate-400">· {{ m.provider }}</span>
                <span *ngIf="!m.enabled" class="text-amber-500"> · aus</span>
              </div>
            </ng-container>
            <ng-template #emptyCell>
              <p class="text-xs italic text-slate-400">
                <ng-container *ngIf="role === 'research'; else genericEmpty">
                  <span *ngIf="activePool() === 'local'">Nicht im Local-Pool (Web = Cloud, fail-closed).</span>
                  <span *ngIf="activePool() !== 'local'">Über Gemini-MCP (Grounding) — kein Cascade-Modell nötig.</span>
                </ng-container>
                <ng-template #genericEmpty>Kein Modell — unten in der Tabelle anlegen (Kategorie <code>{{ role }}-{{ activePool() }}</code>).</ng-template>
              </p>
            </ng-template>
          </div>
        </div>
      </section>

      <!-- Cascade-Bereiche (Phase S') — N Karten dynamisch, jede mit eigener Failover-Chain + Cooldown.
           id="cascade-bereiche-section" ist Scroll-Target für den Auto-Mode-Info-Card-Button im sw-mode-panel. -->
      <section id="cascade-bereiche-section" class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400 mb-4">
          Cascade-Bereiche <span class="text-slate-400 dark:text-slate-500 normal-case tracking-normal font-semibold">— nur Pool „{{ poolTitles[activePool()] }}"</span>
        </h2>
        <ki-cascades-view
          [labels]="cascadesViewLabels"
          [chainLabels]="failoverChainLabels"
          [hintByCascade]="cascadeHints"
        ></ki-cascades-view>
      </section>

      <!-- Modelle (Tabelle + Add-Form) — Library-Components, identisch zu EduPro -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800 space-y-8">
        <ki-models-table
          [labels]="modelsTableLabels"
          [showActiveAction]="true"
          [activeModelId]="activeModel()"
          [categoryTitles]="categoryTitles()"
          [categoryHints]="cascadeHints"
          [categoryOrder]="cascadeOrder"
          [keylessProviders]="switcherKeylessProviders"
          (modelChanged)="reload()"
          (activeModelChanged)="onSwitchToModel($event)"
        ></ki-models-table>
        <ki-add-model-form
          [labels]="addModelFormLabels"
          [defaultCategoryByProvider]="defaultCategoryByProvider"
          (modelCreated)="onModelCreated()"
        ></ki-add-model-form>
      </section>

      <!-- API-Keys (Library-Component, identisch zu EduPro) -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-api-keys-section [labels]="apiKeysSectionLabels" (keyChanged)="onKeyChanged()"></ki-api-keys-section>
      </section>

      <!-- Inferenz-Server (Library v0.15.0) — externe Server pro Modell (Ollama),
           Default localhost. Verwaltung hier; Zuweisung pro Modell in der Tabelle. -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-provider-servers [labels]="providerServersLabels"></ki-provider-servers>
      </section>

      <!-- Quality-Stats (Library v0.12.0, llm-cascade ≥ 0.7.2).
           Worst-first: KILL-Kandidaten (✗ Score < 0.1) stehen oben damit der
           User sofort sieht welche Modelle Probleme machen. Switcher delegiert
           via cascade.getQualityStats() an die llm-cascade-Sidecar (gemeinsame
           DB mit Switcher) — bei Cascade < 0.7.2 zeigt die Component „keine
           Daten" statt zu crashen. -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-models-quality-stats
          [title]="'Modell-Qualität — letzte 30 Tage'"
          [subtitle]="'Schlechte Modelle stehen oben. KILL-Kandidaten sollten deaktiviert werden.'">
        </ki-models-quality-stats>
      </section>

      <!-- Performance-Stats (Library v0.14.0, llm-cascade ≥ 0.7.6).
           Calls, Success-Rate, Cost-Schätzung pro Modell. Switcher hat
           keinen eigenen costMapping-Input gesetzt → Cost-Spalte versteckt
           (Switcher wird normalerweise nicht für Bulk-Generate genutzt). -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-models-performance
          [title]="'Modell-Performance — letzte 30 Tage'"
          [subtitle]="'Calls und Erfolgsrate pro Provider/Modell.'">
        </ki-models-performance>
      </section>

      <!-- Cooldown-State Live-View (Library v0.14.0, llm-cascade ≥ 0.7.6).
           Auto-Refresh 30s. Rote Zeilen = auto-disabled, gelb = Cooldown,
           grün = ready. Zeigt sofort wenn ein Modell Probleme hat. -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-models-cooldown-state
          [title]="'Cooldown-State — Live'"
          [subtitle]="'Auto-Disabled (rot) → Cooldown (gelb) → ready (grün). Auto-Refresh 30s.'"
          [autoRefreshSec]="30">
        </ki-models-cooldown-state>
      </section>

      <!-- Switcher-spezifisch: Claude-Restart-Trigger -->
      <div class="pt-2">
        <button
          type="button"
          (click)="onRestart()"
          [disabled]="restarting()"
          class="w-full rounded-2xl bg-slate-950 dark:bg-slate-50 text-slate-50 dark:text-slate-950 px-5 py-3 text-sm font-bold tracking-wide shadow-sm hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed transition"
        >
          {{ restarting() ? 'Restart läuft…' : '↺ Claude neu starten' }}
        </button>
      </div>

      <p *ngIf="error()" class="text-sm font-medium text-red-600 dark:text-red-400">
        {{ error() }}
      </p>

      <footer class="pt-6 pb-2 text-center text-xs text-slate-500 dark:text-slate-500">
        Wrapper:
        <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-800 text-slate-700 dark:text-slate-300">cd wrapper && ./install.sh</code>
        · dann
        <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-800 text-slate-700 dark:text-slate-300">claude-auto</code>
        statt
        <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-800 text-slate-700 dark:text-slate-300">claude</code>.
      </footer>

      <div
        *ngIf="toast() as t"
        class="fixed bottom-6 left-1/2 -translate-x-1/2 px-5 py-3 rounded-xl text-sm font-bold shadow-lg z-50"
        [class.bg-emerald-600]="t.type === 'ok'"
        [class.text-emerald-50]="t.type === 'ok'"
        [class.bg-red-700]="t.type === 'err'"
        [class.text-red-50]="t.type === 'err'"
      >
        {{ t.msg }}
      </div>
    </main>
  `,
})
export class AppComponent implements OnDestroy {
  private readonly api = inject(SwitcherApiService);

  /**
   * Referenz auf die Library-Modell-Tabelle. Wird gebraucht um sie nach
   * externen Aktionen (Add-Model-Form, API-Key-Save) zu refreshen — die
   * Library hört nicht selbst auf SSE und hat keinen [refreshTrigger]-Input.
   */
  @ViewChild(ModelsTableComponent) modelsTable?: ModelsTableComponent;

  /**
   * Cascade-Bereiche-View. Das Backend filtert `/api/cascades` nach dem aktiven
   * Pool → bei Pool-Wechsel rufen wir hier `reload()`, damit nur die Cascaden
   * des gewählten Pools angezeigt werden (Übersichtlichkeit).
   */
  @ViewChild(CascadesViewComponent) cascadesView?: CascadesViewComponent;

  // Deutsche Labels für die Library-Components (analog zu EduPros i18n-Pipe).
  readonly modelsTableLabels = MODELS_TABLE_LABELS_DE;
  readonly addModelFormLabels = ADD_MODEL_FORM_LABELS_DE;
  readonly cascadesViewLabels: Partial<CascadesViewLabels> = CASCADES_VIEW_LABELS_DE;
  readonly failoverChainLabels: Partial<FailoverChainLabels> = FAILOVER_CHAIN_LABELS_DE;
  readonly apiKeysSectionLabels = API_KEYS_SECTION_LABELS_DE;
  readonly providerServersLabels = PROVIDER_SERVERS_LABELS_DE;

  /**
   * Sub-Hints pro Cascade-Name — wird als Untertitel unter dem Cascade-Namen angezeigt.
   * Switcher nutzt „cloud" (bezahlte Tier-Modelle) + „free-only" (kostenfreie OR-Modelle).
   * Phase S'': Umbenennung default→cloud, fallback→free-only.
   */
  readonly cascadeHints: Record<string, string> = {
    cloud:       'Bezahlte Tier-Modelle (Anthropic / Google / OpenRouter) — eigener Cooldown.',
    'free-only': 'Kostenfreie OpenRouter-Modelle — kein Cooldown, Rate-Limited.',
    general:     'Globaler Fallback — wird genutzt wenn kein Bereich passt.',
  };

  /**
   * Anzeige-Titel pro Kategorie in der Modelle-Tabelle — jetzt **dynamisch**
   * aus `/api/categories` (displayName || humanize). Bugfix: Renames der
   * Kategorie-DisplayNames propagieren live an Tabelle + Toggle, kein Hardcode
   * mehr (vorher fest auf cloud/free-only/general → Compound-Kategorien +
   * Umbenennungen erschienen nie). Wird bei jedem reload() neu gebaut.
   */
  readonly categoryTitles = signal<Record<string, string>>({});

  /** Die 3 Pools — der Bereich-Toggle zeigt NUR diese (nie Rollen, nie „Auto"). */
  readonly POOLS = ['cloud', 'free', 'local'];
  /** Die Rollen — erscheinen pro Pool NUR wenn Supermodell AN ist. */
  readonly ROLES = ['orchestrator', 'implement', 'review', 'research', 'dispatch'];

  readonly poolTitles: Record<string, string> = {
    cloud: 'Cloud — Premium (bezahlt)',
    free:  'Free — OpenRouter :free',
    local: 'Lokal — Ollama (privat)',
  };
  readonly poolHints: Record<string, string> = {
    cloud: 'Beste Qualität (DeepSeek/GPT/Gemini), kostet.',
    free:  '€0, stark rate-limited, NICHT privat (Daten ggf. fürs Training).',
    local: 'Eigene Infra, privat, fail-closed — nichts verlässt den Rechner.',
  };
  readonly roleMeta: Record<string, { label: string; desc: string }> = {
    orchestrator: { label: 'Orchestrator', desc: 'Plant + synthetisiert (Claude Code selbst)' },
    implement: { label: 'Implement', desc: 'Bulk-Code, Backend, Boilerplate, CRUD' },
    review:    { label: 'Review',    desc: 'Korrektheit, Sicherheit, Tests' },
    research:  { label: 'Research',  desc: 'Web/Google, große Docs' },
    dispatch:  { label: 'Dispatch',  desc: 'Triviales: Commit-Msgs, Summaries' },
  };

  /**
   * Reihenfolge der Kategorie-Sektionen in der Modelle-Tabelle: Pools zuerst,
   * dann die Compound-Matrix Rolle×Pool, `general` ganz hinten.
   */
  readonly cascadeOrder: string[] = [
    'cloud', 'free-only', 'local',
    'orchestrator-cloud', 'orchestrator-free', 'orchestrator-local',
    'implement-cloud', 'review-cloud', 'research-cloud', 'dispatch-cloud',
    'implement-free', 'review-free', 'dispatch-free',
    'implement-local', 'review-local', 'dispatch-local',
    'general',
  ];

  /**
   * v0.11.3 — Switcher-spezifischer Override für die ki-models-table.
   * Anthropic via Switcher braucht NICHT zwingend einen sk-ant-Key,
   * weil Claude Code via Max-OAuth-Cookie zum nächsten Modell wechselt
   * (kein direkter api.anthropic.com-Call vom Switcher selbst). Daher
   * zeigt die Tabelle "Lokal" statt "Key fehlt" — und der "Aktiv setzen"-
   * Button ist auch ohne Key verfügbar.
   *
   * Ollama ist immer keyless (vom Backend markiert), egal ob hier
   * gelistet oder nicht.
   */
  readonly switcherKeylessProviders: string[] = ['anthropic'];

  /**
   * Default-Kategorie pro Provider — wird beim Provider-Wechsel im "Neues
   * Modell hinzufügen"-Form vorgewählt. Switcher-Schema: Anthropic/Gemini
   * sind cloud (bezahlt), OpenRouter ist free-only (typisch :free), Ollama
   * läuft lokal und kommt nicht in die Cascade.
   */
  readonly defaultCategoryByProvider: Record<string, string> = {
    anthropic:     'cloud',
    gemini:        'cloud',
    openai:        'cloud',
    deepseek:      'cloud',
    openrouter:    'free-only',
    ollama:        'general',
    openai_compat: 'general',
  };

  readonly status = signal<SwitcherStatus | null>(null);
  readonly warn = signal<{ percent: number; project?: string } | null>(null);
  readonly recheck = signal<{ hoursAgo: number } | null>(null);
  readonly error = signal<string | null>(null);
  readonly restarting = signal(false);
  readonly toast = signal<{ msg: string; type: 'ok' | 'err' } | null>(null);
  /**
   * Aktive (enabled + keyConfigured) Cascade-Modelle — speist die Manuell-
   * Picker-Dropdowns im Modus-Panel. Wird bei jedem `reload()` und bei
   * SSE-Toggle-Events neu geladen. Provider-Namensraum wird hier auf
   * Switcher-UI (`google` statt `gemini`) gemappt.
   */
  readonly availableModels = signal<{ provider: string; modelId: string; displayName: string; category?: string | null }[]>([]);

  /** v2 — Supermodell-Modus (Orchestrierung-Achse) an/aus. */
  readonly supermodel = signal<boolean>(false);
  /** v2 — Aktiver Pool (Bereich-Achse): cloud | free | local. */
  readonly activePool = signal<string>('cloud');
  /** v2 — Local-Orchestrator gewählt, aber kein lokales Modell aktiv (fail-closed). */
  readonly localOrchestratorPending = signal<boolean>(false);
  /** v2 — Alle Modelle gruppiert nach Compound-Kategorie {rolle}-{pool} (Rollen-Panel). */
  readonly matrixModels = signal<Record<string, { provider: string; modelId: string; displayName: string; enabled: boolean }[]>>({});

  /** EventSource für SSE-Live-Updates. Wird in ngOnInit aufgemacht + ngOnDestroy geschlossen. */
  private es: EventSource | null = null;

  ngOnInit(): void {
    this.reload();
    this.startSse();
    this.loadCategoriesAndPref();
  }

  /**
   * Beim Mount: dynamische Kategorie-Titel + 2-Achsen-State (Pool + Supermodell)
   * laden. Fehler werden geschluckt (Cascade ohne Feature → Defaults).
   */
  private loadCategoriesAndPref(): void {
    this.reloadCategoryTitles();
    this.api.getSupermodel().subscribe({
      next: (resp) => {
        this.supermodel.set(!!resp?.enabled);
        this.activePool.set(resp?.pool || 'cloud');
        this.localOrchestratorPending.set(!!resp?.localOrchestratorPending);
      },
      error: () => { this.supermodel.set(false); this.activePool.set('cloud'); },
    });
  }

  /** Baut categoryTitles dynamisch aus /api/categories (displayName || humanize). */
  private reloadCategoryTitles(): void {
    this.api.listCategoryMetas().subscribe({
      next: (metas) => {
        const titles: Record<string, string> = {};
        for (const m of metas || []) {
          if (!m?.name) continue;
          const dn = (m.displayName ?? '').trim();
          titles[m.name] = dn || this.humanizeCategory(m.name);
        }
        this.categoryTitles.set(titles);
      },
      error: () => { /* Cascade ohne Feature → Library humanized selbst */ },
    });
  }

  /** `implement-cloud` → `Implement · Cloud` (Fallback ohne displayName). */
  private humanizeCategory(name: string): string {
    return name.split(/[-_]/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' · ');
  }

  /** Modelle der Compound-Zelle {role}-{activePool} (für das Rollen-Panel). */
  cellModels(role: string): { provider: string; modelId: string; displayName: string; enabled: boolean }[] {
    return this.matrixModels()[`${role}-${this.activePool()}`] ?? [];
  }

  ngOnDestroy(): void {
    this.es?.close();
    this.es = null;
  }

  /**
   * SSE-Stream `/api/events` abonnieren. Bei Events:
   * - `warn` → Quota-Banner anzeigen
   * - `recheck-due` → Cooldown-Recheck-Banner anzeigen
   * - `auto-switched`, `chain-promoted`, `auto-promoted`, `switch`,
   *   `auto-config`, `chain-exhausted`, `quota-error` → Toast + reload
   * - Library-Events (`model-toggled`, `model-tested`, etc.) → kein Toast,
   *   nur reload (die Library-Components haben eigenen State)
   */
  private startSse(): void {
    if (typeof EventSource === 'undefined') return;
    try {
      this.es = new EventSource(this.api.eventsUrl());
    } catch {
      return;
    }

    const reloadOn = (event: string) => this.es?.addEventListener(event, () => this.reload());
    const toastReloadOn = (event: string, msg: (data: any) => string, type: 'ok' | 'err' = 'ok') => {
      this.es?.addEventListener(event, (e: MessageEvent) => {
        try {
          const data = e.data ? JSON.parse(e.data) : {};
          this.showToast(msg(data), type);
        } catch {
          this.showToast(msg({}), type);
        }
        this.reload();
      });
    };

    this.es.addEventListener('warn', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        this.warn.set({ percent: d.percent, project: d.project });
      } catch {}
    });

    this.es.addEventListener('recheck-due', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        this.recheck.set({ hoursAgo: d.sinceFailoverHours ?? 0 });
      } catch {
        this.recheck.set({ hoursAgo: 0 });
      }
    });

    toastReloadOn('quota-error', (d) => `Quota erreicht in „${d.project ?? 'Projekt'}" (Modus: ${d.mode})`, 'err');
    toastReloadOn('auto-switched', (d) =>
      `Auto-Switch → ${d.to?.provider}/${d.to?.model} (Stufe ${(d.position ?? 0) + 1}/${d.total})`);
    toastReloadOn('chain-exhausted', () => 'Alle Provider der Chain ausgeschöpft', 'err');
    toastReloadOn('chain-promoted', () => 'Zurück auf Anthropic — Wrapper holt sich Restart-Marker');
    toastReloadOn('auto-promoted', (d) => `Auto-Promote → Anthropic (Cooldown ${d.hoursSinceFailover ?? '?'} h abgelaufen)`);

    // 'switch'-Event mit Toast + Reload — wird vom Backend gefeuert wenn
    // /api/switch erfolgreich war. Wir hängen einen Toast dran damit der
    // User unabhängig vom Trigger-Pfad (Picker, Per-Row-„Als aktiv", Chat-
    // Command „wechsel auf X") visuelles Feedback bekommt.
    toastReloadOn('switch', (d) => `Wechsel auf ${d.provider}${d.model ? ' · ' + d.model : ''} — Wrapper startet neu`);
    reloadOn('auto-config');
    reloadOn('model-toggled');
    reloadOn('model-tested');
    reloadOn('model-created');
    reloadOn('model-deleted');
    reloadOn('models-reordered');
    reloadOn('model-reenabled');
    reloadOn('setting-updated');
    reloadOn('cooldown-override');
    // v2: Supermodell-Toggle live spiegeln (Wrapper/anderer Tab schaltet um)
    this.es?.addEventListener('supermodel', () => {
      this.api.getSupermodel().subscribe({
        next: (r) => {
          this.supermodel.set(!!r?.enabled);
          if (r?.pool) this.activePool.set(r.pool);
          this.localOrchestratorPending.set(!!r?.localOrchestratorPending);
        },
        error: () => {},
      });
    });
    // 2-Achsen 'mode'-Event: Pool + Supermodell direkt aus dem Payload.
    this.es?.addEventListener('mode', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        if (d.pool && d.pool !== this.activePool()) {
          this.activePool.set(d.pool);
          this.cascadesView?.reload(); // anderer Pool → Cascade-Bereiche neu filtern
          this.modelsTable?.reload();
          this.reloadAvailableModels();
        }
        if (typeof d.supermodel === 'boolean') this.supermodel.set(d.supermodel);
        this.localOrchestratorPending.set(!!d.localOrchestratorPending);
      } catch {}
    });
    // Kategorie-Rename/-Delete → categoryTitles + Tabelle dynamisch neu.
    reloadOn('category-updated');
  }

  /**
   * Library-Event `(activeModelChanged)` — User klickt „Als aktiv" pro Zeile
   * in der Modell-Tabelle. Provider-Namensraum mappen (cascade-`gemini` →
   * switcher-`google`) und Live-Switch via `/api/switch`.
   */
  onSwitchToModel(m: { provider: string; modelId: string }): void {
    const uiProvider = m.provider === 'gemini' ? 'google' : m.provider;
    this.onSwitchTo({ provider: uiProvider, model: m.modelId });
  }

  private showToast(msg: string, type: 'ok' | 'err' = 'ok'): void {
    this.toast.set({ msg, type });
    setTimeout(() => this.toast.set(null), 4000);
  }

  reload(): void {
    this.api.status().subscribe({
      next: (s) => {
        this.status.set(s);
        if (s.pool) this.activePool.set(s.pool);
        if (typeof s.supermodel === 'boolean') this.supermodel.set(s.supermodel);
        this.localOrchestratorPending.set(!!s.localOrchestratorPending);
        if (s.lastWarn && Date.now() - s.lastWarn.at < 5 * 60_000) {
          this.warn.set({ percent: s.lastWarn.percent, project: s.lastWarn.project });
        }
      },
      error: (e) => this.error.set('Status nicht erreichbar: ' + (e?.message ?? e)),
    });
    this.reloadAvailableModels();
    this.reloadCategoryTitles();
  }

  /**
   * Lädt die Cascade-Modell-Liste und filtert auf alles was im „Wechseln zu"-
   * Picker nutzbar ist: Key gesetzt + nicht auto-disabled. Das `enabled`-Flag
   * (Cascade-Chain-Aktiv-Toggle) wird BEWUSST nicht abgefragt — ein Modell mit
   * Key kann live ausgewählt werden, auch wenn es gerade nicht in der
   * Cascade-Chain steht. Provider-Namensraum wird gemappt (`gemini` → `google`).
   */
  private reloadAvailableModels(): void {
    this.api.listAiModels().subscribe({
      next: (models) => {
        const usable = models
          .filter((m) => m.keyConfigured && !m.autoDisabled)
          .map((m) => ({
            provider: m.provider === 'gemini' ? 'google' : m.provider,
            modelId: m.modelId,
            displayName: m.displayName || m.modelId,
            // v0.7.5: Kategorie durchreichen damit das Modus-Panel den
            // Picker im Manuell-Mode nach aktiver Bereich-Auswahl filtern kann.
            category: m.category ?? null,
          }));
        this.availableModels.set(usable);
        // Matrix: ALLE Modelle (auch disabled) nach Compound-Kategorie gruppieren,
        // damit das Rollen-Panel die Zellen (inkl. noch nicht aktivierter Local-
        // Modelle) zeigt. API-Reihenfolge = orderIdx (Failover-Kette).
        const matrix: Record<string, { provider: string; modelId: string; displayName: string; enabled: boolean }[]> = {};
        for (const m of models) {
          const cat = m.category || 'general';
          (matrix[cat] ??= []).push({
            provider: m.provider === 'gemini' ? 'google' : m.provider,
            modelId: m.modelId,
            displayName: m.displayName || m.modelId,
            enabled: !!m.enabled,
          });
        }
        this.matrixModels.set(matrix);
      },
      error: () => { this.availableModels.set([]); this.matrixModels.set({}); },
    });
  }

  /**
   * Wird vom Add-Model-Form gefeuert. Library-Tabelle weiß nichts vom Form,
   * also triggern wir hier explizit ihr `reload()` + zusätzlich unseren
   * eigenen Status- + Picker-Refresh.
   */
  onModelCreated(): void {
    this.modelsTable?.reload();
    this.reload();
  }

  /**
   * Wird von der API-Keys-Section gefeuert. Tabelle muss refreshen damit
   * die „Key gesetzt"-Spalte korrekt ist, und der Picker muss neu evaluieren.
   */
  onKeyChanged(): void {
    this.modelsTable?.reload();
    this.reload();
  }

  onModeChange(mode: 'manual' | 'auto'): void {
    this.api.setAuto({
      mode,
      fallback_chain: this.status()?.fallback_chain,
      chain_position: this.status()?.chain_position,
    }).subscribe(() => this.reload());
  }

  /**
   * User klickt einen Pool-Tab (Cloud / Free / Lokal). POST /api/mode {pool}
   * persistiert die Bereich-Achse. `local` ist fail-closed: das Backend pinnt
   * NIE auf Cloud/Opus; fehlt ein lokales Modell, kommt eine Pending-Warnung.
   */
  onPoolChange(pool: string): void {
    const prev = this.activePool();
    this.activePool.set(pool); // optimistisch
    this.api.setMode({ pool }).subscribe({
      next: (r) => {
        this.activePool.set(r.pool || pool);
        this.localOrchestratorPending.set(!!r.localOrchestratorPending);
        this.showToast(r.note ? r.note : 'Pool: ' + (this.poolTitles[pool] || pool), r.note ? 'err' : 'ok');
        // Backend filtert /api/cascades + /api/ai-models jetzt nach dem neuen Pool →
        // Cascade-View, Modell-Tabelle und Matrix/Picker neu laden.
        this.cascadesView?.reload();
        this.modelsTable?.reload();
        this.reloadAvailableModels();
        if (r.restart) this.reload();
      },
      error: () => {
        this.activePool.set(prev); // rollback
        this.showToast('Pool-Wechsel fehlgeschlagen', 'err');
      },
    });
  }

  /**
   * v2 — Supermodell-Achse an/aus (Pool unverändert). Backend pinnt Opus nur
   * bei Pool cloud/free; bei Pool=local bleibt der Orchestrator lokal (fail-closed).
   */
  onSupermodelChange(on: boolean): void {
    const localPool = this.activePool() === 'local';
    this.supermodel.set(on); // optimistisch
    this.api.setSupermodel(on).subscribe({
      next: (r) => {
        this.supermodel.set(!!r?.enabled);
        this.toast.set({
          msg: on
            ? (localPool ? 'Supermodell AN — lokaler Orchestrator (fail-closed)' : 'Supermodell AN — Opus orchestriert')
            : 'Supermodell AUS',
          type: 'ok',
        });
        this.reload(); // Rollen-Panel + Pending-State auffrischen
      },
      error: () => {
        this.supermodel.set(!on); // rollback
        this.toast.set({ msg: 'Supermodell-Umschaltung fehlgeschlagen', type: 'err' });
      },
    });
  }

  onPromote(): void {
    this.api.chainPromote().subscribe(() => this.reload());
  }

  /**
   * Manueller Wechsel des aktiven Providers/Modells (vom Mode-Panel
   * im Manuell-Modus). Wrapper kriegt den Restart-Marker und startet
   * Claude Code mit den neuen Env-Vars neu.
   */
  onSwitchTo(event: { provider: string; model: string }): void {
    this.error.set(null);
    this.api.switchProvider({ provider: event.provider, model: event.model }).subscribe({
      next: () => {
        this.showToast(`Wechsel auf ${event.provider} · ${event.model} — Wrapper startet neu`);
        this.reload();
      },
      error: (e) => this.error.set('Switch failed: ' + (e?.error?.error ?? e?.message ?? e)),
    });
  }

  /** Best-Effort: aktives Modell aus activeRoute oder Top-Level-model. */
  activeModel(): string | null {
    const s = this.status();
    return s?.activeRoute?.model || s?.model || null;
  }

  onSwitchNow(): void {
    // Banner-„Jetzt switchen": chain-promote zur nächsten Stufe.
    this.api.chainPromote().subscribe(() => {
      this.warn.set(null);
      this.reload();
    });
  }

  onRestart(): void {
    this.restarting.set(true);
    this.error.set(null);
    this.api.restart().subscribe({
      next: () => {
        this.restarting.set(false);
        this.reload();
      },
      error: (e) => {
        this.error.set('Restart failed: ' + (e?.message ?? e));
        this.restarting.set(false);
      },
    });
  }
}
