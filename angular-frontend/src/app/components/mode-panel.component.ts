import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChainEntry } from '../services/switcher-api.service';
import { PROVIDER_MODELS } from './provider-grid.component';

/**
 * Mode-Toggle (Manuell / Auto-Failover) + Chain-Editor.
 *
 * Im Auto-Modus wird die Failover-Chain editierbar: pro Stufe ein Provider+Model.
 * Bei Quota-Erreichung des aktiven Modells wechselt der Switcher-Wrapper
 * automatisch zur nächsten Stufe.
 *
 * `chain` (Input) — aktuelle Chain
 * `chainPosition` (Input) — wo der Switcher gerade steht
 *
 * Events:
 * - `(modeChanged)` — `'manual' | 'auto'`
 * - `(chainChanged)` — neue Chain-Liste
 * - `(promoteRequested)` — User klickt „Zurück zu Anthropic" (Chain-Pos auf 0)
 */
@Component({
  selector: 'sw-mode-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="mode-panel">
      <div class="mode-toggle">
        <button class="mode-btn" [class.active]="mode === 'manual'" (click)="setMode('manual')">Manuell</button>
        <button class="mode-btn" [class.active]="mode === 'auto'" (click)="setMode('auto')">Auto-Failover</button>
      </div>

      <div *ngIf="mode === 'auto'" class="auto-panel">
        <p class="hint">
          <strong>Failover-Chain</strong> — bei Quota-Erreichung wird der Reihe nach durchgegangen.
        </p>

        <div class="chain-rows">
          <div *ngFor="let row of localChain; let i = index" class="chain-row">
            <span class="step">{{ i + 1 }}.</span>
            <select [(ngModel)]="row.provider" (change)="onProviderChange(i)" class="select">
              <option value="anthropic">Anthropic</option>
              <option value="google">Google AI Studio</option>
              <option value="openrouter">OpenRouter</option>
            </select>
            <select [(ngModel)]="row.model" (change)="emitChainChange()" class="select">
              <option *ngFor="let m of modelsFor(row.provider)" [value]="m.id">{{ m.name }}</option>
            </select>
            <button class="del" (click)="removeRow(i)" *ngIf="localChain.length > 1" title="Entfernen">×</button>
          </div>
        </div>

        <button class="add" (click)="addRow()">+ Stufe hinzufügen</button>

        <div class="position">
          <span class="position-label">Aktuelle Stufe:</span>
          <span class="position-value">{{ positionLabel() }}</span>
          <button class="promote" *ngIf="(chainPosition ?? 0) > 0" (click)="promoteRequested.emit()">↶ Zurück zu Stufe 1</button>
        </div>

        <p class="helper">
          Bei Quota-Erreichung wechselt der Wrapper automatisch zur nächsten Stufe und startet
          Claude Code mit <code>--resume</code> neu (Kontext bleibt erhalten).
          Voraussetzung: <code>claude-auto</code> als Wrapper.
        </p>
      </div>
    </div>
  `,
  styles: [`
    .mode-panel { margin-bottom: 1.5rem; }
    .mode-toggle { display: inline-flex; background: #161616; border-radius: 0.75rem; padding: 0.2rem; border: 1px solid #2a2a2a; }
    .mode-btn {
      padding: 0.4rem 0.9rem; background: transparent; border: none;
      color: #888; font-size: 0.8rem; font-weight: 700; cursor: pointer; border-radius: 0.5rem;
    }
    .mode-btn.active { background: #38bdf8; color: #000; }
    .auto-panel { margin-top: 0.75rem; padding: 0.75rem; background: #0a1010; border: 1px solid #2a2a2a; border-radius: 0.75rem; }
    .hint { color: #888; font-size: 0.75rem; margin: 0 0 0.6rem; }
    .hint strong { color: #aaa; }
    .chain-rows { display: flex; flex-direction: column; gap: 0.4rem; }
    .chain-row { display: flex; align-items: center; gap: 0.4rem; }
    .step { color: #888; font-size: 0.75rem; min-width: 1.3rem; }
    .select {
      padding: 0.35rem 0.5rem; background: #1f1f1f; border: 1px solid #333; color: #e5e5e5;
      border-radius: 0.4rem; font-size: 0.75rem; flex: 1;
    }
    .del { width: 1.6rem; height: 1.6rem; padding: 0; background: #2a2a2a; color: #888; border: none; border-radius: 0.3rem; cursor: pointer; }
    .del:hover { background: #ef4444; color: white; }
    .add { margin-top: 0.6rem; padding: 0.4rem 0.7rem; background: transparent; color: #38bdf8; border: 1px dashed #38bdf866; border-radius: 0.5rem; font-size: 0.75rem; cursor: pointer; }
    .position {
      display: flex; align-items: center; gap: 0.6rem;
      margin-top: 0.75rem; padding-top: 0.6rem; border-top: 1px dashed #1f1f1f;
    }
    .position-label { color: #888; font-size: 0.75rem; }
    .position-value { flex: 1; color: #ccc; font-size: 0.8rem; }
    .promote { padding: 0.3rem 0.6rem; background: #38bdf8; color: #000; border: none; border-radius: 0.4rem; font-size: 0.7rem; font-weight: 700; cursor: pointer; }
    .helper { color: #666; font-size: 0.7rem; margin-top: 0.75rem; line-height: 1.4; }
    code { background: #1f1f1f; padding: 0.05rem 0.25rem; border-radius: 0.2rem; font-size: 0.65rem; }
  `],
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
    // Beim Provider-Wechsel auf erstes verfügbares Modell zurücksetzen
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
