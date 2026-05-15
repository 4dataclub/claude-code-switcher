import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SwitcherStatus } from '../services/switcher-api.service';

/**
 * Header-Status-Bar. Zeigt aktiver Provider, Modell, Modus-Pille.
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-BG (slate-50/slate-900 dark) —
 * passt zu EduPro-Style.
 */
@Component({
  selector: 'sw-status-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="flex items-center gap-3 rounded-2xl bg-white dark:bg-slate-900 px-4 py-3 ring-1 ring-slate-200 dark:ring-slate-800 text-sm">
      <span
        class="inline-block w-2.5 h-2.5 rounded-full"
        [class.bg-emerald-500]="!!status?.provider"
        [class.bg-slate-400]="!status?.provider"
      ></span>
      <span class="text-slate-500 dark:text-slate-400">Aktiv:</span>
      <span class="font-bold text-slate-900 dark:text-slate-100">{{ providerLabel() }}</span>
      <span *ngIf="modelText()" class="font-mono text-xs text-slate-500 dark:text-slate-400">· {{ modelText() }}</span>
      <span
        class="ml-auto px-2.5 py-0.5 rounded-full text-[10px] font-extrabold uppercase tracking-widest"
        [class.bg-sky-100]="status?.mode === 'auto'"
        [class.text-sky-700]="status?.mode === 'auto'"
        [class.dark:bg-sky-900]="status?.mode === 'auto'"
        [class.dark:text-sky-200]="status?.mode === 'auto'"
        [class.bg-slate-100]="status?.mode !== 'auto'"
        [class.text-slate-600]="status?.mode !== 'auto'"
        [class.dark:bg-slate-800]="status?.mode !== 'auto'"
        [class.dark:text-slate-400]="status?.mode !== 'auto'"
      >
        {{ status?.mode === 'auto' ? 'Auto-Failover' : 'Manuell' }}
      </span>
    </div>
  `,
})
export class StatusBarComponent {
  @Input() status: SwitcherStatus | null = null;

  providerLabel(): string {
    switch (this.status?.provider) {
      case 'google':     return 'Google AI Studio';
      case 'openrouter': return 'OpenRouter';
      case 'anthropic':  return 'Anthropic';
      default:           return '–';
    }
  }

  modelText(): string {
    return this.status?.activeRoute?.model || this.status?.model || '';
  }
}
