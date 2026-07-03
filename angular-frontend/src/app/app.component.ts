import { Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ModelsPageComponent,
  KiModelsPageConfig,
  CascadesViewLabels,
  FailoverChainLabels,
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
 * Switcher Angular-App — Single-Source-Composer (consolidation).
 *
 * Die komplette KI-Modell-Verwaltung (Cascade-Cooldown, Cascades-View,
 * Modell-Tabelle, Add-Form, API-Keys, Datenschutz, Supermodell-Matrix sowie
 * alle Statistiken) wird jetzt vom gemeinsamen `<ki-models-page>` gerendert —
 * **identisch zu EduPro**, keine Switcher-Sonderflocke mehr.
 *
 * Switcher-spezifisches Chrome bleibt drumherum: Status-Bar, Banner,
 * **Modus-Panel** (Manuell vs. Auto-Failover + Pool-Toggle + Supermodell-
 * Schalter) und der Claude-Restart-Button. Der Pool/Supermodell-State wird
 * über die `[activePool]`/`[supermodelOn]`-Inputs in die Library gereicht; bei
 * Pool-Wechsel ruft der Host `modelsPage.reload()` (Tabelle + Cascades + Matrix
 * neu nach dem aktiven Pool).
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    StatusBarComponent,
    BannerComponent,
    ModePanelComponent,
    ModelsPageComponent,
  ],
  template: `
    <main class="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8 space-y-6">
      <header class="mb-2">
        <h1 class="text-2xl font-extrabold tracking-tight text-slate-950 dark:text-slate-50">
          Claude Code Switcher
        </h1>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
          API-Anbieter, Modell und Auto-Failover für Claude Code.
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

      <!-- Switcher-spezifische Sektion: Manuell vs. Auto-Failover + Pool/Supermodell -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400 mb-4">
          Modus
        </h2>
        <sw-mode-panel
          [mode]="status()?.mode ?? 'manual'"
          [categories]="POOLS"
          [activeCategory]="activePool()"
          [categoryTitles]="poolTitles"
          [categoryHintMap]="poolHints"
          [supermodel]="supermodel()"
          (modeChanged)="onModeChange($event)"
          (promoteRequested)="onPromote()"
          (categoryChanged)="onPoolChange($event)"
          (supermodelChanged)="onSupermodelChange($event)"
        ></sw-mode-panel>

      </section>

      <!-- Modell-/Cascaden-Bereich: Kopfzeile mit Anzeige-Umschalter.
           Default zeigt nur den aktiven Pool; optional alle 3 Pools als Matrix —
           reine ANZEIGE (Routing bleibt der aktive Pool), damit man ohne
           Pool-Wechsel sieht, was in den anderen Pools konfiguriert ist. -->
      <div class="flex flex-col gap-3 pt-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400">
            Modelle &amp; Cascaden
          </h2>
          <p class="mt-0.5 text-xs text-slate-400 dark:text-slate-500">
            Anzeige · das Routing gilt immer dem aktiven Pool
          </p>
        </div>
        <label class="flex items-center gap-2 self-start text-xs text-slate-500 dark:text-slate-400 sm:self-auto">
          <span class="font-medium">Bereich:</span>
          <select
            [value]="displayFilter()"
            (change)="setDisplayFilter($any($event.target).value)"
            class="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            aria-label="Anzeige-Bereich filtern"
          >
            <option value="active">Aktiver Pool ({{ activePool() }})</option>
            <option value="cloud">Cloud</option>
            <option value="free">Free</option>
            <option value="local">Local</option>
            <option value="all">Alle Pools</option>
          </select>
        </label>
      </div>

      <!-- Gemeinsame KI-Modell-Seite (Library) — alle Sektionen, identisch zu EduPro.
           Supermodell-Matrix erscheint NUR bei [supermodelOn]=true (Schalter im Modus-Panel). -->
      <ki-models-page
        [config]="pageConfig"
        [activePool]="activePool()"
        [supermodelOn]="supermodel()"
        [visibleCategories]="visibleCategories()"
        [localOrchestratorPending]="localOrchestratorPending()"
        [addModelFilterEnabled]="true"
        (activeModelChanged)="onSwitchToModel($event)"
        (modelChanged)="reload()"
        (modelCreated)="reload()"
        (keyChanged)="reload()"
      ></ki-models-page>

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

      <footer class="pt-6 pb-2 text-center text-xs text-slate-400 dark:text-slate-500">
        Claude Code Switcher
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
   * Referenz auf die gemeinsame Library-Seite. Wird gebraucht um sie nach
   * externen Aktionen (Pool-Wechsel, SSE-Events) zu refreshen — die Library
   * hört nicht selbst auf SSE. `reload()` lädt Tabelle + Cascades + Matrix neu.
   */
  @ViewChild(ModelsPageComponent) modelsPage?: ModelsPageComponent;

  // Deutsche Labels für die Library-Components (analog zu EduPros i18n-Pipe).
  readonly modelsTableLabels = MODELS_TABLE_LABELS_DE;
  readonly addModelFormLabels = ADD_MODEL_FORM_LABELS_DE;
  readonly cascadesViewLabels: Partial<CascadesViewLabels> = CASCADES_VIEW_LABELS_DE;
  readonly failoverChainLabels: Partial<FailoverChainLabels> = FAILOVER_CHAIN_LABELS_DE;
  readonly apiKeysSectionLabels = API_KEYS_SECTION_LABELS_DE;
  readonly providerServersLabels = PROVIDER_SERVERS_LABELS_DE;

  // v0.18.0 — Deutsche Labels für die geteilten Analytics-Panels.
  readonly callOverviewLabels = {
    title: 'KI-Calls — Übersicht',
    subtitle: 'Erfolgs-Trend, Call-Summen und geschätzte Kosten über alle Provider.',
    trendTitle: 'Erfolgs-Trend (30 Tage)',
    card24h: 'Calls 24h',
    card7d: 'Calls 7d',
    card30d: 'Calls 30d',
    cardSuccess30d: 'Erfolg 30d',
    cardFailed30d: 'Fehler 30d',
    costTitle: 'Geschätzte Kosten (30 Tage)',
    costChars: 'Output-Zeichen',
    costTokens: 'Tokens (≈Zeichen/4)',
    costMoney: 'Kosten',
    loading: 'Lade Call-Stats…',
    empty: 'Noch keine Calls erfasst.',
  };
  readonly failoverAnalyticsLabels = {
    title: 'Failover-Analyse',
    subtitle: 'Wo die Kaskade ein Modell gedroppt hat (letzte 30 Tage) — nach Provider und Grund.',
    donutTitle: 'Failover-out / Provider',
    donutCenter: 'OUT',
    tableTitle: 'Provider × Grund',
    colProvider: 'Provider',
    colReason: 'Grund',
    colCount: 'Anzahl',
    loading: 'Lade Failover-Stats…',
    empty: 'Keine Failover-Events in den letzten 30 Tagen.',
  };

  /**
   * Sub-Hints pro Cascade-Name — wird als Untertitel unter dem Cascade-Namen angezeigt.
   * Switcher nutzt „cloud" (bezahlte Tier-Modelle) + „free-only" (kostenfreie OR-Modelle).
   */
  readonly cascadeHints: Record<string, string> = {
    cloud:       'Bezahlte Tier-Modelle (Anthropic / Google / DeepSeek) — direktes Pool-Modell mit Failover.',
    free:        'Kostenfreie OpenRouter-Modelle — rate-limited.',
    'free-only': 'Kostenfreie OpenRouter-Modelle — rate-limited.',
    local:       'Lokale Ollama-Modelle — fail-closed, kein Cloud-Ausweich.',
    general:     'Globaler Fallback — wird genutzt wenn kein Bereich passt.',
  };

  /**
   * Anzeige-Titel pro Kategorie — dynamisch aus `/api/categories`
   * (displayName || humanize). Renames propagieren live an Tabelle + Toggle.
   * Wird bei jedem reload() neu gebaut und via pageConfig in die Library gereicht.
   */
  readonly categoryTitles = signal<Record<string, string>>({});

  /** Die 3 Pools — der Bereich-Toggle zeigt NUR diese (nie Rollen, nie „Auto"). */
  readonly POOLS = ['cloud', 'free', 'local'];

  // Kurze Pool-Namen — konsistent mit dem Bereich-Toggle (Cloud / Free / Lokal).
  // Die beschreibenden Zusätze (Premium/OpenRouter/Ollama) leben in poolHints.
  readonly poolTitles: Record<string, string> = {
    cloud: 'Cloud',
    free:  'Free',
    local: 'Lokal',
  };
  readonly poolHints: Record<string, string> = {
    cloud: 'Beste Qualität (Anthropic/Google/DeepSeek), kostet.',
    free:  '€0, stark rate-limited, NICHT privat (Daten ggf. fürs Training).',
    local: 'Eigene Infra, privat, fail-closed — nichts verlässt den Rechner.',
  };

  /**
   * Reihenfolge der Kategorie-Sektionen in der Modelle-Tabelle: Pools zuerst,
   * dann die Compound-Matrix Rolle×Pool, `general` ganz hinten.
   */
  readonly cascadeOrder: string[] = [
    // Pool-gruppiert (cloud → free → local): pro Pool zuerst die AUS-Areas
    // (bare Pool + area-Compounds), dann die AN-Rollen. So folgt die
    // Anzeige-Reihenfolge der neuen Pool-Gruppierung in Tabelle + Cascades.
    // Cloud
    'cloud', 'general-cloud', 'dev-cloud', 'utility-cloud', 'content-cloud',
    'orchestrator-cloud', 'implement-cloud', 'review-cloud', 'research-cloud', 'dispatch-cloud',
    // Free (bare 'free' + Legacy 'free-only')
    'free', 'free-only', 'general-free', 'dev-free', 'utility-free', 'content-free',
    'orchestrator-free', 'implement-free', 'review-free', 'research-free', 'dispatch-free',
    // Local
    'local', 'general-local', 'dev-local', 'utility-local', 'content-local',
    'orchestrator-local', 'implement-local', 'review-local', 'research-local', 'dispatch-local',
    // Globaler Fallback ganz hinten
    'general',
  ];

  /**
   * v0.11.3 — Switcher-spezifischer Override für die ki-models-table.
   * Anthropic via Switcher braucht NICHT zwingend einen sk-ant-Key
   * (Max-OAuth-Cookie). Daher zeigt die Tabelle "Lokal" statt "Key fehlt"
   * und der "Aktiv setzen"-Button ist auch ohne Key verfügbar.
   */
  readonly switcherKeylessProviders: string[] = ['anthropic'];

  /**
   * Default-Kategorie pro Provider — wird beim Provider-Wechsel im „Neues
   * Modell hinzufügen"-Form vorgewählt. Anthropic/Gemini = cloud (bezahlt),
   * OpenRouter = free-only, Ollama = lokal (general).
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

  /** v2 — Supermodell-Modus (Orchestrierung-Achse) an/aus. */
  readonly supermodel = signal<boolean>(false);
  /** v2 — Aktiver Pool (Bereich-Achse): cloud | free | local. */
  readonly activePool = signal<string>('cloud');
  /** v2 — Local-Orchestrator gewählt, aber kein lokales Modell aktiv (fail-closed). */
  readonly localOrchestratorPending = signal<boolean>(false);

  /**
   * Anzeige-Filter (reine Sichtbarkeit, NICHT das Routing): welcher Pool in
   * Cascades-View + Models-Tabelle gezeigt wird.
   *   'active' → folgt dem aktiven Pool (Default)
   *   'cloud' | 'free' | 'local' → genau dieser Pool (ohne den aktiven zu wechseln)
   *   'all' → alle 3 Pools als Matrix
   * Das ROUTING gilt immer nur dem AKTIVEN Pool (siehe RouterService).
   */
  readonly displayFilter = signal<'active' | 'cloud' | 'free' | 'local' | 'all'>('active');
  setDisplayFilter(v: string): void {
    this.displayFilter.set(v as 'active' | 'cloud' | 'free' | 'local' | 'all');
  }

  /**
   * Whitelist der sichtbaren Kategorien/Cascaden — wird an `<ki-models-page>`
   * gereicht und dort an Tabelle + Cascades-View weiterverteilt.
   *
   * DEFAULT: nur der AKTIVE Pool (mit seinen Area- bzw. Rollen-Compounds). Über
   * {@link displayFilter} auf einen anderen Pool oder die volle Matrix umschaltbar.
   *
   * Naming-Konvention pro Pool:
   *   AUS (supermodel=false): bare Pool-Name + {general,dev,utility,content}-{pool}
   *     (cloud → 'cloud'; free → 'free' + Legacy 'free-only'; local → 'local')
   *   AN  (supermodel=true):  {orchestrator,implement,review,research,dispatch}-{pool}
   */
  readonly visibleCategories = computed<string[]>(() => {
    const f = this.displayFilter();
    const pools = f === 'all' ? ['cloud', 'free', 'local']
      : f === 'active' ? [this.activePool()]
      : [f];
    const out: string[] = [];
    if (!this.supermodel()) {
      const areas = ['general', 'dev', 'utility', 'content'];
      for (const pool of pools) {
        out.push(pool); // bare Pool-Name
        // Legacy: Backend akzeptiert 'free-only' als bare Free-Kategorie.
        if (pool === 'free') out.push('free-only');
        for (const a of areas) out.push(`${a}-${pool}`);
      }
      return out;
    }
    const roles = ['orchestrator', 'implement', 'review', 'research', 'dispatch'];
    for (const pool of pools) {
      for (const r of roles) out.push(`${r}-${pool}`);
    }
    return out;
  });

  /** EventSource für SSE-Live-Updates. Wird in ngOnInit aufgemacht + ngOnDestroy geschlossen. */
  private es: EventSource | null = null;

  /**
   * Config-Bundle für `<ki-models-page>`. Reicht alle switcher-spezifischen
   * Labels/Kategorien durch, damit die gemeinsame Library-Seite die deutschen
   * Texte + die Pool/Compound-Reihenfolge des Switchers nutzt. Getter, weil
   * `categoryTitles` + `activeModelId` dynamisch sind (Signal/Status).
   */
  get pageConfig(): KiModelsPageConfig {
    return {
      modelsTableLabels: this.modelsTableLabels,
      addModelFormLabels: this.addModelFormLabels,
      cascadesViewLabels: this.cascadesViewLabels,
      cascadeChainLabels: this.failoverChainLabels,
      apiKeysSectionLabels: this.apiKeysSectionLabels,
      providerServersLabels: this.providerServersLabels,
      cascadeHints: this.cascadeHints,
      categoryHints: this.cascadeHints,
      categoryTitles: this.categoryTitles(),
      categoryOrder: this.cascadeOrder,
      keylessProviders: this.switcherKeylessProviders,
      defaultCategoryByProvider: this.defaultCategoryByProvider,
      showActiveAction: true,
      activeModelId: this.activeModel(),
      callOverviewLabels: this.callOverviewLabels,
      failoverAnalyticsLabels: this.failoverAnalyticsLabels,
      // Kein Auto-Refresh — Aktualisierung nur per ↻-Button.
      delegationAutoRefreshSec: 0,
      cooldownAutoRefreshSec: 0,
    };
  }

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
    // /api/switch erfolgreich war. Toast unabhängig vom Trigger-Pfad (Picker,
    // Per-Row-„Als aktiv", Chat-Command „wechsel auf X").
    toastReloadOn('switch', (d) => `Wechsel auf ${d.provider}${d.model ? ' · ' + d.model : ''} — Wrapper startet neu`);
    reloadOn('auto-config');
    reloadOn('model-toggled');
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
          this.modelsPage?.reload(); // anderer Pool → Tabelle + Cascades + Matrix neu filtern
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

  /**
   * Genereller Refresh: Status + dynamische Kategorie-Titel + die gemeinsame
   * Library-Seite (Tabelle + Cascades + Matrix). Wird bei Mount + jedem
   * relevanten SSE-Event aufgerufen.
   */
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
    this.reloadCategoryTitles();
    this.modelsPage?.reload();
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
        // Cascade-View, Modell-Tabelle und Matrix neu laden.
        this.modelsPage?.reload();
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
        this.reload(); // Rollen-Matrix + Pending-State auffrischen
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
    return s?.activeRoute?.topModel || s?.activeRoute?.model || s?.model || null;
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
