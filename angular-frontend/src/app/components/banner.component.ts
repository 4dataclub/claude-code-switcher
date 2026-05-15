import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Quota-Warn-Banner + Cooldown-Recheck-Banner.
 *
 * `warn` (Input) — wenn gesetzt, zeigt das 90%-Warn-Banner mit „Jetzt switchen"
 *   und „Weitermachen"-Buttons.
 * `recheck` (Input) — wenn gesetzt, zeigt das Cooldown-abgelaufen-Banner
 *   mit „Zurück zu Anthropic"-Button.
 *
 * Events:
 * - `(switchNow)` — User klickt „Jetzt switchen"
 * - `(promoteNow)` — User klickt „Jetzt zurück" (zum Primary)
 * - `(dismissed)` — User klickt „Weitermachen" oder „Später"
 */
@Component({
  selector: 'sw-banner',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="warn && !dismissedWarn()" class="banner warn">
      <span class="icon">⚠️</span>
      <div class="text">
        <strong>Quota bei ~{{ warn.percent }}%</strong>
        <span *ngIf="warn.project"> — in Projekt „{{ warn.project }}"</span>.
        Auto-Failover ist {{ autoMode ? 'an' : 'aus' }}.
      </div>
      <div class="actions">
        <button class="btn primary" (click)="switchNow.emit()">Jetzt switchen</button>
        <button class="btn" (click)="dismissWarn()">Weitermachen</button>
      </div>
    </div>

    <div *ngIf="recheck && !dismissedRecheck()" class="banner recheck">
      <span class="icon">🔄</span>
      <div class="text">
        <strong>Cooldown abgelaufen</strong> — letzter Failover vor {{ recheck.hoursAgo }} h.
        Anthropic wieder probieren?
      </div>
      <div class="actions">
        <button class="btn primary recheck-primary" (click)="promoteNow.emit()">Jetzt zurück</button>
        <button class="btn" (click)="dismissRecheck()">Später</button>
      </div>
    </div>
  `,
  styles: [`
    .banner {
      display: flex; align-items: center; gap: 0.75rem;
      padding: 0.75rem 1rem; border-radius: 0.75rem; margin-bottom: 0.75rem;
      font-size: 0.85rem;
    }
    .banner.warn { background: #2a1a0a; border: 1px solid #f59e0b66; color: #fcd34d; }
    .banner.recheck { background: #0a1a2a; border: 1px solid #38bdf866; color: #7dd3fc; }
    .icon { font-size: 1.1rem; }
    .text { flex: 1; }
    .actions { display: flex; gap: 0.4rem; }
    .btn {
      padding: 0.3rem 0.7rem; border-radius: 0.5rem;
      border: 1px solid #444; background: transparent; color: inherit;
      font-size: 0.75rem; font-weight: 700; cursor: pointer;
    }
    .btn.primary { background: #f59e0b; color: #000; border-color: #f59e0b; }
    .btn.recheck-primary { background: #38bdf8; color: #000; border-color: #38bdf8; }
  `],
})
export class BannerComponent {
  @Input() warn: { percent: number; project?: string } | null = null;
  @Input() recheck: { hoursAgo: number } | null = null;
  @Input() autoMode = false;

  @Output() switchNow = new EventEmitter<void>();
  @Output() promoteNow = new EventEmitter<void>();
  @Output() dismissed = new EventEmitter<'warn' | 'recheck'>();

  readonly dismissedWarn = signal(false);
  readonly dismissedRecheck = signal(false);

  dismissWarn(): void {
    this.dismissedWarn.set(true);
    this.dismissed.emit('warn');
  }

  dismissRecheck(): void {
    this.dismissedRecheck.set(true);
    this.dismissed.emit('recheck');
  }
}
