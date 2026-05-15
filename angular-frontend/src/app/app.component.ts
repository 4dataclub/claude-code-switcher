import { Component, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ModelsTableComponent,
  AddModelFormComponent,
  CascadeCooldownComponent,
  ApiKeysSectionComponent,
} from '@4dataclub/ki-models-ui';
import { SwitcherApiService, SwitcherStatus, ChainEntry } from './services/switcher-api.service';
import { StatusBarComponent } from './components/status-bar.component';
import { BannerComponent } from './components/banner.component';
import { ModePanelComponent } from './components/mode-panel.component';
import {
  MODELS_TABLE_LABELS_DE,
  ADD_MODEL_FORM_LABELS_DE,
  CASCADE_COOLDOWN_LABELS_DE,
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
    CascadeCooldownComponent,
    ApiKeysSectionComponent,
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
          (modeChanged)="onModeChange($event)"
          (chainChanged)="onChainChange($event)"
          (promoteRequested)="onPromote()"
        ></sw-mode-panel>
      </section>

      <!-- Cascade-Cooldown Override (Library-Component, identisch zu EduPro) -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-cascade-cooldown [labels]="cascadeCooldownLabels"></ki-cascade-cooldown>
      </section>

      <!-- Modelle (Tabelle + Add-Form) — Library-Components, identisch zu EduPro -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800 space-y-8">
        <ki-models-table
          [labels]="modelsTableLabels"
          (modelChanged)="reload()"
        ></ki-models-table>
        <ki-add-model-form
          [labels]="addModelFormLabels"
          (modelCreated)="reload()"
        ></ki-add-model-form>
      </section>

      <!-- API-Keys (Library-Component, identisch zu EduPro) -->
      <section class="rounded-[40px] bg-white dark:bg-slate-900 p-6 sm:p-8 shadow-sm ring-1 ring-slate-200 dark:ring-slate-800">
        <ki-api-keys-section [labels]="apiKeysSectionLabels" (keyChanged)="reload()"></ki-api-keys-section>
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

  // Deutsche Labels für die Library-Components (analog zu EduPros i18n-Pipe).
  readonly modelsTableLabels = MODELS_TABLE_LABELS_DE;
  readonly addModelFormLabels = ADD_MODEL_FORM_LABELS_DE;
  readonly cascadeCooldownLabels = CASCADE_COOLDOWN_LABELS_DE;
  readonly apiKeysSectionLabels = API_KEYS_SECTION_LABELS_DE;

  readonly status = signal<SwitcherStatus | null>(null);
  readonly warn = signal<{ percent: number; project?: string } | null>(null);
  readonly recheck = signal<{ hoursAgo: number } | null>(null);
  readonly error = signal<string | null>(null);
  readonly restarting = signal(false);
  readonly toast = signal<{ msg: string; type: 'ok' | 'err' } | null>(null);

  /** EventSource für SSE-Live-Updates. Wird in ngOnInit aufgemacht + ngOnDestroy geschlossen. */
  private es: EventSource | null = null;

  ngOnInit(): void {
    this.reload();
    this.startSse();
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

    reloadOn('switch');
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
  }

  onModeChange(mode: 'manual' | 'auto'): void {
    this.api.setAuto({
      mode,
      fallback_chain: this.status()?.fallback_chain,
      chain_position: this.status()?.chain_position,
    }).subscribe(() => this.reload());
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
