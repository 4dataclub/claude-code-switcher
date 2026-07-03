import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SwitcherStatus } from '../services/switcher-api.service';

/**
 * Header-Status-Bar. Zeigt dieselbe „Wahrheit" wie der switcher-watch Box-Header:
 * aktiver Provider/Modell, Pool, Supermodell-Zustand und Failover-Modus.
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-BG (slate-50/slate-900 dark) —
 * passt zu EduPro-Style.
 */
@Component({
  selector: 'sw-status-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-2xl bg-white dark:bg-slate-900 px-4 py-3 ring-1 ring-slate-200 dark:ring-slate-800 text-sm">
      <span
        class="inline-block w-2.5 h-2.5 rounded-full"
        [class.bg-emerald-500]="!!status?.provider"
        [class.bg-slate-400]="!status?.provider"
      ></span>
      <span class="text-slate-500 dark:text-slate-400">Aktiv:</span>
      <span class="font-bold text-slate-900 dark:text-slate-100">{{ providerLabel() }}</span>
      <span *ngIf="modelText()" class="font-mono text-xs text-slate-500 dark:text-slate-400">· {{ modelText() }}</span>

      <!-- Badges rechts: Pool, Supermodell, Failover — spiegelt die watch-Box. -->
      <span class="ml-auto flex flex-wrap items-center gap-2">
        <span class="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold uppercase tracking-widest bg-violet-100 text-violet-700 dark:bg-violet-900 dark:text-violet-200">
          Pool: {{ status?.pool || 'cloud' }}
        </span>
        <span
          class="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold uppercase tracking-widest"
          [class.bg-emerald-100]="status?.supermodel"
          [class.text-emerald-700]="status?.supermodel"
          [class.dark:bg-emerald-900]="status?.supermodel"
          [class.dark:text-emerald-200]="status?.supermodel"
          [class.bg-slate-100]="!status?.supermodel"
          [class.text-slate-600]="!status?.supermodel"
          [class.dark:bg-slate-800]="!status?.supermodel"
          [class.dark:text-slate-400]="!status?.supermodel"
        >
          Supermodell: {{ status?.supermodel ? 'AN' : 'AUS' }}
        </span>
        <span
          class="px-2.5 py-0.5 rounded-full text-[10px] font-extrabold uppercase tracking-widest"
          [class.bg-sky-100]="status?.mode === 'auto'"
          [class.text-sky-700]="status?.mode === 'auto'"
          [class.dark:bg-sky-900]="status?.mode === 'auto'"
          [class.dark:text-sky-200]="status?.mode === 'auto'"
          [class.bg-slate-100]="status?.mode !== 'auto'"
          [class.text-slate-600]="status?.mode !== 'auto'"
          [class.dark:bg-slate-800]="status?.mode !== 'auto'"
          [class.dark:text-slate-400]="status?.mode !== 'auto'"
        >
          Failover: {{ status?.mode === 'auto' ? 'Auto' : 'Manuell' }}
        </span>
      </span>
    </div>
  `,
})
export class StatusBarComponent {
  @Input() status: SwitcherStatus | null = null;

  providerLabel(): string {
    switch (this.status?.provider) {
      case 'google':      return 'Google AI Studio';
      case 'openrouter':  return 'OpenRouter';
      case 'anthropic':   return 'Anthropic';
      case 'llm-cascade': return 'llm-cascade';
      case 'ollama':      return 'Ollama';
      default:            return '–';
    }
  }

  /** Echtes Modell bevorzugen (activeRoute.topModel) statt der Cascade-Kategorie
   *  (activeRoute.model = z.B. "orchestrator-cloud"). */
  modelText(): string {
    return this.status?.activeRoute?.topModel
        || this.status?.activeRoute?.model
        || this.status?.model
        || '';
  }
}
