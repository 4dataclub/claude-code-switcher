import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChainEntry } from '../services/switcher-api.service';

/**
 * Provider/Modell-Whitelist für den Chain-Editor (Auto-Failover).
 *
 * Statisches Mapping — bewusst klein gehalten auf die tatsächlich in der
 * Switcher-Praxis sinnvollen Routen. Erweitern wenn neue Provider eingebunden
 * werden.
 */
const PROVIDER_MODELS: Record<string, { id: string; name: string }[]> = {
  anthropic: [
    { id: 'claude-opus-4-7', name: 'Claude Opus 4.7' },
    { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6' },
    { id: 'claude-haiku-4-5-20251001', name: 'Claude Haiku 4.5' },
  ],
  google: [
    { id: 'gemini-2.5-pro', name: 'Gemini 2.5 Pro' },
    { id: 'gemini-2.5-flash', name: 'Gemini 2.5 Flash' },
    { id: 'gemini-2.5-flash-lite', name: 'Gemini 2.5 Flash Lite' },
    { id: 'gemini-3-pro-preview', name: 'Gemini 3 Pro (Preview)' },
    { id: 'gemini-3-flash-preview', name: 'Gemini 3 Flash (Preview)' },
  ],
  openrouter: [
    { id: 'deepseek/deepseek-chat-v3.1', name: 'DeepSeek v3.1' },
    { id: 'meta-llama/llama-3.3-70b-instruct:free', name: 'Llama 3.3 70B (free)' },
    { id: 'openai/gpt-oss-120b:free', name: 'GPT-OSS 120B (free)' },
  ],
};

/**
 * Mode-Toggle (Manuell / Auto-Failover) + Chain-Editor — Switcher-Spezifisch.
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-Background (slate-50/slate-900
 * dark) — passt zu EduPro-Style des umgebenden Cards in `AppComponent`.
 *
 * Im Auto-Modus wird die Failover-Chain editierbar: pro Stufe ein Provider+Model.
 * Bei Quota-Erreichung des aktiven Modells wechselt der Switcher-Wrapper
 * automatisch zur nächsten Stufe.
 *
 * Events:
 * - `(modeChanged)` — `'manual' | 'auto'`
 * - `(chainChanged)` — neue Chain-Liste
 * - `(promoteRequested)` — User klickt „Zurück zu Stufe 1"
 */
@Component({
  selector: 'sw-mode-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div>
      <!-- Tab-Toggle Manuell / Auto-Failover -->
      <div class="inline-flex rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
        <button
          type="button"
          (click)="setMode('manual')"
          class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
          [class.bg-slate-950]="mode === 'manual'"
          [class.text-slate-50]="mode === 'manual'"
          [class.dark:bg-slate-50]="mode === 'manual'"
          [class.dark:text-slate-950]="mode === 'manual'"
          [class.text-slate-500]="mode !== 'manual'"
          [class.dark:text-slate-400]="mode !== 'manual'"
        >Manuell</button>
        <button
          type="button"
          (click)="setMode('auto')"
          class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
          [class.bg-slate-950]="mode === 'auto'"
          [class.text-slate-50]="mode === 'auto'"
          [class.dark:bg-slate-50]="mode === 'auto'"
          [class.dark:text-slate-950]="mode === 'auto'"
          [class.text-slate-500]="mode !== 'auto'"
          [class.dark:text-slate-400]="mode !== 'auto'"
        >Auto-Failover</button>
      </div>

      <!-- Manual-Hinweis -->
      <p *ngIf="mode === 'manual'" class="mt-3 text-sm text-slate-600 dark:text-slate-400">
        Du wählst Provider + Modell selbst aus der Modell-Tabelle unten. Bei Quota-Erreichung
        zeigt der Banner einen Switch-Vorschlag — der Wrapper macht den Wechsel aber nur,
        wenn du im Auto-Failover-Modus bist.
      </p>

      <!-- Auto-Chain-Editor -->
      <div *ngIf="mode === 'auto'" class="mt-4 rounded-2xl bg-slate-50 dark:bg-slate-800 p-4 sm:p-5 ring-1 ring-slate-200 dark:ring-slate-700">
        <p class="text-sm text-slate-600 dark:text-slate-300 mb-3">
          <strong class="font-semibold text-slate-900 dark:text-slate-100">Failover-Chain</strong>
          — bei Quota-Erreichung wird der Reihe nach durchgegangen.
        </p>

        <div class="space-y-2">
          <div *ngFor="let row of localChain; let i = index" class="flex items-center gap-2">
            <span class="w-6 text-xs font-bold text-slate-500 dark:text-slate-400">{{ i + 1 }}.</span>
            <select
              [(ngModel)]="row.provider"
              (change)="onProviderChange(i)"
              class="flex-1 min-w-0 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-sm text-slate-900 dark:text-slate-100"
            >
              <option value="anthropic">Anthropic</option>
              <option value="google">Google AI Studio</option>
              <option value="openrouter">OpenRouter</option>
            </select>
            <select
              [(ngModel)]="row.model"
              (change)="emitChainChange()"
              class="flex-1 min-w-0 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-sm text-slate-900 dark:text-slate-100"
            >
              <option *ngFor="let m of modelsFor(row.provider)" [value]="m.id">{{ m.name }}</option>
            </select>
            <button
              type="button"
              (click)="removeRow(i)"
              *ngIf="localChain.length > 1"
              title="Entfernen"
              class="w-7 h-7 rounded-lg bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-red-500 hover:text-white transition"
            >×</button>
          </div>
        </div>

        <button
          type="button"
          (click)="addRow()"
          class="mt-3 px-3 py-1.5 text-xs font-bold rounded-lg border border-dashed border-slate-400 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-slate-950 dark:hover:border-slate-50 hover:text-slate-950 dark:hover:text-slate-50 transition"
        >+ Stufe hinzufügen</button>

        <div class="flex items-center gap-3 mt-4 pt-3 border-t border-dashed border-slate-300 dark:border-slate-700">
          <span class="text-xs text-slate-500 dark:text-slate-400">Aktuelle Stufe:</span>
          <span class="flex-1 text-sm text-slate-700 dark:text-slate-200">{{ positionLabel() }}</span>
          <button
            type="button"
            *ngIf="(chainPosition ?? 0) > 0"
            (click)="promoteRequested.emit()"
            class="px-3 py-1.5 text-xs font-bold rounded-lg bg-slate-950 dark:bg-slate-50 text-slate-50 dark:text-slate-950 hover:opacity-90 transition"
          >↶ Zurück zu Stufe 1</button>
        </div>

        <p class="mt-3 text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          Bei Quota-Erreichung wechselt der Wrapper automatisch zur nächsten Stufe und startet
          Claude Code mit
          <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300">--resume</code>
          neu (Kontext bleibt erhalten). Voraussetzung:
          <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300">claude-auto</code>
          als Wrapper.
        </p>
      </div>
    </div>
  `,
})
export class ModePanelComponent {
  @Input() mode: 'manual' | 'auto' = 'manual';
  @Input() chain: ChainEntry[] = [];
  @Input() chainPosition: number | null = 0;

  @Output() modeChanged = new EventEmitter<'manual' | 'auto'>();
  @Output() chainChanged = new EventEmitter<ChainEntry[]>();
  @Output() promoteRequested = new EventEmitter<void>();

  localChain: ChainEntry[] = [];

  ngOnChanges(): void {
    this.localChain = this.chain.length
      ? this.chain.map((e) => ({ ...e }))
      : [
          { provider: 'anthropic',  model: 'claude-sonnet-4-6' },
          { provider: 'google',     model: 'gemini-2.5-pro' },
          { provider: 'openrouter', model: 'deepseek/deepseek-chat-v3.1' },
        ];
  }

  setMode(m: 'manual' | 'auto'): void {
    if (this.mode === m) return;
    this.modeChanged.emit(m);
  }

  onProviderChange(idx: number): void {
    // Beim Provider-Wechsel auf erstes verfügbares Modell zurücksetzen.
    const first = this.modelsFor(this.localChain[idx].provider)[0];
    if (first) this.localChain[idx].model = first.id;
    this.emitChainChange();
  }

  modelsFor(provider: string) {
    return PROVIDER_MODELS[provider] || [];
  }

  addRow(): void {
    this.localChain = [...this.localChain, { provider: 'anthropic', model: 'claude-sonnet-4-6' }];
    this.emitChainChange();
  }

  removeRow(idx: number): void {
    this.localChain = this.localChain.filter((_, i) => i !== idx);
    this.emitChainChange();
  }

  emitChainChange(): void {
    this.chainChanged.emit([...this.localChain]);
  }

  positionLabel(): string {
    const pos = this.chainPosition ?? 0;
    const entry = this.localChain[pos];
    if (!entry) return `Stufe ${pos + 1}`;
    return `Stufe ${pos + 1} (${entry.provider} · ${entry.model})`;
  }
}
