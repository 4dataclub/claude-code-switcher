import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SwitcherStatus } from '../services/switcher-api.service';

/**
 * Header-Status-Bar. Zeigt aktiver Provider, Modell, Modus-Pille.
 */
@Component({
  selector: 'sw-status-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="status-bar">
      <div class="dot" [class.active]="!!status?.provider"></div>
      <span class="label">Aktiv:</span>
      <span class="provider">{{ providerLabel() }}</span>
      <span class="model" *ngIf="modelText()">· {{ modelText() }}</span>
      <span class="mode-pill" [class.auto]="status?.mode === 'auto'">
        {{ status?.mode === 'auto' ? 'Auto-Failover' : 'Manuell' }}
      </span>
    </div>
  `,
  styles: [`
    .status-bar {
      display: flex; align-items: center; gap: 0.6rem;
      padding: 0.75rem 1rem; background: #161616; border: 1px solid #2a2a2a;
      border-radius: 0.75rem; margin-bottom: 1rem;
      font-size: 0.85rem;
    }
    .dot { width: 0.6rem; height: 0.6rem; border-radius: 50%; background: #444; }
    .dot.active { background: #10b981; box-shadow: 0 0 0.4rem #10b98155; }
    .label { color: #888; }
    .provider { color: #e5e5e5; font-weight: 700; }
    .model { color: #9ca3af; font-family: ui-monospace, monospace; font-size: 0.75rem; }
    .mode-pill {
      margin-left: auto;
      padding: 0.2rem 0.6rem; border-radius: 999px;
      font-size: 0.625rem; font-weight: 800;
      text-transform: uppercase; letter-spacing: 0.08em;
      background: #1f1f1f; color: #888;
    }
    .mode-pill.auto { background: #1e3a8a; color: #93c5fd; }
  `],
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
