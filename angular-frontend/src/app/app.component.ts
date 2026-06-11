import { Component, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ModelsTableComponent,
  AddModelFormComponent,
  CascadesViewComponent,
  ApiKeysSectionComponent,
  RoutingDecisionsComponent,
  ModelsQualityStatsComponent,
  CascadesViewLabels,
  FailoverChainLabels,
} from '@4dataclub/ki-models-ui';
import { SwitcherApiService, SwitcherStatus, ChainEntry, SwitcherAiModel } from './services/switcher-api.service';
import { StatusBarComponent } from './components/status-bar.component';
import { BannerComponent } from './components/banner.component';
import { ModePanelComponent } from './components/mode-panel.component';
import {
  MODELS_TABLE_LABELS_DE,
  ADD_MODEL_FORM_LABELS_DE,
  CASCADES_VIEW_LABELS_DE,
  FAILOVER_CHAIN_LABELS_DE,
  API_KEYS_SECTION_LABELS_DE,
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
    RoutingDecisionsComponent,
    ModelsQualityStatsComponent,
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
          [chain]="status()?.fallback_chain ?? []"
          [chainPosition]="status()?.chain_position ?? 0"
          [activeProvider]="status()?.provider ?? null"
          [activeModel]="activeModel()"
          [availableModels]="availableModels()"
          [categories]="categoriesList()"
          [activeCategory]="preferredCategory()"
          [categoryHintMap]="cascadeHints"
          (modeChanged)="onModeChange($event)"
          (chainChanged)="onChainChange($event)"
          (promoteRequested)="onPromote()"
          (switchTo)="onSwitchTo($event)"
          (categoryChanged)="onCategoryChange($event)"
        ></sw-mode-panel>
      </section>

      <!-- Cascade-Bereiche (Phase S') — N Karten dynamisch, jede mit eigener Failover-Chain + Cooldown -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <h2 class="text-xs font-bold uppercase tracking-[0.15em] text-slate-500 dark:text-slate-400 mb-4">
          Cascade-Bereiche
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
          [categoryTitles]="categoryTitles"
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

      <!-- Semantic-Routing-Cache (Library v0.11.0, llm-cascade ≥ 0.6.0).
           Zeigt cached purpose → category Entscheidungen + Test-Preview-Input.
           Bei Backend ohne /routing-Endpoint (Pre-0.6.0): empty state, kein Crash. -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-routing-decisions></ki-routing-decisions>
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

  // Deutsche Labels für die Library-Components (analog zu EduPros i18n-Pipe).
  readonly modelsTableLabels = MODELS_TABLE_LABELS_DE;
  readonly addModelFormLabels = ADD_MODEL_FORM_LABELS_DE;
  readonly cascadesViewLabels: Partial<CascadesViewLabels> = CASCADES_VIEW_LABELS_DE;
  readonly failoverChainLabels: Partial<FailoverChainLabels> = FAILOVER_CHAIN_LABELS_DE;
  readonly apiKeysSectionLabels = API_KEYS_SECTION_LABELS_DE;

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
   * Anzeige-Titel pro Kategorie in der Modelle-Tabelle. Ohne diesen Input würde
   * die Library ab v0.10.0 die Kategorie-Strings capitalizen (`free-only` →
   * `Free Only`); wir wollen explizite, sprechende Labels.
   */
  readonly categoryTitles: Record<string, string> = {
    cloud:       'Cloud — Premium-Modelle',
    'free-only': 'Free Only — kostenfrei',
    general:     'General — Fallback',
  };

  /**
   * Reihenfolge der Kategorie-Sektionen in der Modelle-Tabelle. Cloud zuerst
   * (Default für den Switcher), Free-Only danach. `general` ist Fallback und
   * landet automatisch hinten falls überhaupt jemand ein `general`-Modell hat.
   */
  readonly cascadeOrder: string[] = ['cloud', 'free-only', 'general'];

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
  readonly availableModels = signal<{ provider: string; modelId: string; displayName: string }[]>([]);

  /** v0.7.5 — Liste der verfügbaren Cascade-Bereiche (cloud, free-only, …).
   *  Wird beim Init via GET /api/cascades geholt. */
  readonly categoriesList = signal<string[]>([]);
  /** v0.7.5 — Aktuell vom User gewählter Override (leer = Semantic Routing). */
  readonly preferredCategory = signal<string>('');

  /** EventSource für SSE-Live-Updates. Wird in ngOnInit aufgemacht + ngOnDestroy geschlossen. */
  private es: EventSource | null = null;

  ngOnInit(): void {
    this.reload();
    this.startSse();
    this.loadCategoriesAndPref();
  }

  /**
   * v0.7.5 — Cascade-Bereiche + Preferred-Category beim Mount laden.
   *  - Bereiche-Liste aus GET /api/cascades (was eh schon im Cascades-View
   *    rendert) → wir nehmen nur die Namen für das Modus-Panel-Toggle.
   *  - Preferred-Category aus GET /api/preferred-category → signal-set
   *    damit der korrekte Toggle vor-gehighlighted ist.
   *
   * Fehler werden geschluckt — bei Cascade < 0.7.5 bleibt der Toggle
   * versteckt (categoriesList leer) und der State leer.
   */
  private loadCategoriesAndPref(): void {
    this.api.listCascades().subscribe({
      next: (cs: any[]) => {
        const names = Array.isArray(cs) ? cs.map((c) => c?.name).filter((n): n is string => !!n) : [];
        this.categoriesList.set(names);
      },
      error: () => this.categoriesList.set([]),
    });
    this.api.getPreferredCategory().subscribe({
      next: (resp: any) => this.preferredCategory.set(resp?.category || ''),
      error: () => this.preferredCategory.set(''),
    });
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
        if (s.lastWarn && Date.now() - s.lastWarn.at < 5 * 60_000) {
          this.warn.set({ percent: s.lastWarn.percent, project: s.lastWarn.project });
        }
      },
      error: (e) => this.error.set('Status nicht erreichbar: ' + (e?.message ?? e)),
    });
    this.reloadAvailableModels();
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
          }));
        this.availableModels.set(usable);
      },
      error: () => this.availableModels.set([]),
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
   * v0.7.5 — User klickt einen Cascade-Bereich (Cloud / Free Only).
   * POST /api/preferred-category → bei Success: lokales Signal updaten +
   * status-bar neu lesen.
   *
   * Leer-String setzt zurück auf Semantic Routing.
   */
  onCategoryChange(category: string): void {
    this.api.setPreferredCategory(category).subscribe({
      next: () => {
        this.preferredCategory.set(category);
      },
      error: () => {
        // Backend nicht da → Signal nicht updaten, UI bleibt im alten State.
      },
    });
  }

  onChainChange(chain: ChainEntry[]): void {
    this.api.setAuto({
      mode: this.status()?.mode ?? 'auto',
      fallback_chain: chain,
      chain_position: 0,
    }).subscribe(() => this.reload());
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
