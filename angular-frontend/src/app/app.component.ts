import { Component, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ModelsTableComponent,
  AddModelFormComponent,
  CascadeCooldownComponent,
  ApiKeysSectionComponent,
} from '@dataclub/ki-models-ui';
import { SwitcherApiService, SwitcherStatus, ChainEntry } from './services/switcher-api.service';
import { StatusBarComponent } from './components/status-bar.component';
import { BannerComponent } from './components/banner.component';
import { ProviderGridComponent } from './components/provider-grid.component';
import { ModePanelComponent } from './components/mode-panel.component';

/**
 * Switcher Angular-App — Phase L.4 (Vanilla → Angular Port).
 *
 * Top-Level Shell mit:
 * - Status-Bar (current provider/model/mode)
 * - Banner (Quota-Warn + Cooldown-Recheck)
 * - Mode-Panel (manual / auto + chain-editor)
 * - Provider-Grid (Cards + Models + manueller Switch)
 * - ki-models-ui Library für Cascade-Verwaltung
 * - Restart-Button
 *
 * Live-Updates via SSE in einer späteren Iteration. Aktuell wird `loadStatus()`
 * nach jedem Action manuell aufgerufen.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    StatusBarComponent,
    BannerComponent,
    ProviderGridComponent,
    ModePanelComponent,
    ModelsTableComponent,
    AddModelFormComponent,
    CascadeCooldownComponent,
    ApiKeysSectionComponent,
  ],
  template: `
    <main class="shell">
      <header class="hdr">
        <h1>Claude Code Switcher</h1>
        <p class="subtitle">API-Anbieter, Modell und Auto-Failover für Claude Code</p>
      </header>

      <sw-status-bar [status]="status()"></sw-status-bar>

      <sw-banner
        [warn]="warn()"
        [recheck]="recheck()"
        [autoMode]="status()?.mode === 'auto'"
        (switchNow)="onSwitchNow()"
        (promoteNow)="onPromote()"
      ></sw-banner>

      <section class="section">
        <h2>Modus</h2>
        <sw-mode-panel
          [mode]="status()?.mode ?? 'manual'"
          [chain]="status()?.fallback_chain ?? []"
          [chainPosition]="status()?.chain_position ?? 0"
          (modeChanged)="onModeChange($event)"
          (chainChanged)="onChainChange($event)"
          (promoteRequested)="onPromote()"
        ></sw-mode-panel>
      </section>

      <section class="section">
        <h2>Aktiver Anbieter (manuell)</h2>
        <sw-provider-grid
          [activeProvider]="status()?.provider ?? null"
          [activeModel]="activeModel()"
          (switchTo)="onSwitchTo($event)"
        ></sw-provider-grid>
      </section>

      <section class="section card">
        <h2>Cascade-Cooldown</h2>
        <ki-cascade-cooldown></ki-cascade-cooldown>
      </section>

      <section class="section card">
        <h2>Cascade-Modelle</h2>
        <ki-models-table (modelChanged)="reload()"></ki-models-table>
        <ki-add-model-form (modelCreated)="reload()"></ki-add-model-form>
      </section>

      <section class="section card">
        <ki-api-keys-section (keyChanged)="reload()"></ki-api-keys-section>
      </section>

      <div class="actions">
        <button class="restart" (click)="onRestart()" [disabled]="restarting()">
          {{ restarting() ? 'Restart läuft…' : '↺ Claude neu starten' }}
        </button>
      </div>

      <p *ngIf="error()" class="error">{{ error() }}</p>

      <footer class="ftr">
        Wrapper: <code>cd wrapper && ./install.sh</code> · dann <code>claude-auto</code> statt <code>claude</code>.
      </footer>

      <div *ngIf="toast() as t" class="toast" [class.toast-err]="t.type === 'err'">
        {{ t.msg }}
      </div>
    </main>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; background: #0a0a0a; color: #e5e5e5; font-family: ui-sans-serif, system-ui, sans-serif; }
    .shell { max-width: 1100px; margin: 0 auto; padding: 1.5rem; }
    .hdr { padding: 1rem 0 1.5rem; border-bottom: 1px solid #2a2a2a; margin-bottom: 1.25rem; }
    .hdr h1 { font-size: 1.6rem; font-weight: 800; letter-spacing: -0.02em; margin: 0; }
    .subtitle { color: #888; font-size: 0.85rem; margin: 0.25rem 0 0; }
    .section { margin-bottom: 1.5rem; }
    .section h2 { font-size: 0.75rem; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; color: #888; margin: 0 0 0.75rem; }
    .section.card { background: #ffffff; color: #0f172a; padding: 1.5rem 2rem; border-radius: 1rem; }
    .section.card h2 { color: #475569; }
    .actions { margin: 1.5rem 0; }
    .restart {
      width: 100%; padding: 0.85rem; background: #38bdf8; color: #000; border: none;
      border-radius: 0.75rem; font-weight: 800; font-size: 0.9rem; cursor: pointer;
    }
    .restart:disabled { opacity: 0.5; cursor: not-allowed; }
    .ftr { color: #666; font-size: 0.75rem; text-align: center; padding: 1rem 0; }
    code { background: #1f1f1f; padding: 0.1rem 0.3rem; border-radius: 0.25rem; font-size: 0.7rem; }
    .error { color: #f87171; font-size: 0.85rem; margin-top: 0.5rem; }
    .toast {
      position: fixed; bottom: 1.5rem; left: 50%; transform: translateX(-50%);
      padding: 0.7rem 1.2rem; background: #064e3b; color: #d1fae5;
      border-radius: 0.6rem; font-size: 0.85rem; font-weight: 700;
      box-shadow: 0 8px 24px rgba(0,0,0,0.4); z-index: 100;
      animation: toastIn 0.2s ease-out;
    }
    .toast.toast-err { background: #7f1d1d; color: #fee2e2; }
    @keyframes toastIn { from { opacity: 0; transform: translate(-50%, 0.5rem); } to { opacity: 1; transform: translate(-50%, 0); } }
  `],
})
export class AppComponent implements OnDestroy {
  private readonly api = inject(SwitcherApiService);

  readonly status = signal<SwitcherStatus | null>(null);
  readonly warn = signal<{ percent: number; project?: string } | null>(null);
  readonly recheck = signal<{ hoursAgo: number } | null>(null);
  readonly error = signal<string | null>(null);
  readonly restarting = signal(false);
  readonly toast = signal<{ msg: string; type: 'ok' | 'err' } | null>(null);

  /** EventSource für SSE-Live-Updates. Wird in ngOnInit aufgemacht + ngOnDestroy geschlossen. */
  private es: EventSource | null = null;

  activeModel(): string | null {
    const s = this.status();
    return s?.activeRoute?.model || s?.model || null;
  }

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

  onSwitchTo(event: { provider: string; modelId: string }): void {
    this.error.set(null);
    this.api.switchProvider({ provider: event.provider, model: event.modelId }).subscribe({
      next: () => this.reload(),
      error: (e) => this.error.set('Switch failed: ' + (e?.error?.error ?? e?.message ?? e)),
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
    // bei Banner-„Jetzt switchen": chain-promote zur nächsten Stufe
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
