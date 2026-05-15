import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Quota-Warn-Banner + Cooldown-Recheck-Banner.
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-BG — passt zu EduPro-Style.
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
    <div
      *ngIf="warn && !dismissedWarn()"
      class="flex items-center gap-3 rounded-2xl px-4 py-3 text-sm bg-amber-50 dark:bg-amber-950/40 ring-1 ring-amber-200 dark:ring-amber-800 text-amber-900 dark:text-amber-200"
    >
      <span class="text-base">⚠️</span>
      <div class="flex-1">
        <strong class="font-bold">Quota bei ~{{ warn.percent }}%</strong>
        <span *ngIf="warn.project"> — in Projekt „{{ warn.project }}"</span>.
        Auto-Failover ist {{ autoMode ? 'an' : 'aus' }}.
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          (click)="switchNow.emit()"
          class="px-3 py-1.5 text-xs font-bold rounded-lg bg-amber-500 text-amber-950 hover:bg-amber-400 transition"
        >Jetzt switchen</button>
        <button
          type="button"
          (click)="dismissWarn()"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-amber-300 dark:border-amber-700 hover:bg-amber-100 dark:hover:bg-amber-900 transition"
        >Weitermachen</button>
      </div>
    </div>

    <div
      *ngIf="recheck && !dismissedRecheck()"
      class="flex items-center gap-3 rounded-2xl px-4 py-3 text-sm bg-sky-50 dark:bg-sky-950/40 ring-1 ring-sky-200 dark:ring-sky-800 text-sky-900 dark:text-sky-200 mt-3"
    >
      <span class="text-base">🔄</span>
      <div class="flex-1">
        <strong class="font-bold">Cooldown abgelaufen</strong>
        — letzter Failover vor {{ recheck.hoursAgo }} h. Anthropic wieder probieren?
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          (click)="promoteNow.emit()"
          class="px-3 py-1.5 text-xs font-bold rounded-lg bg-sky-500 text-sky-950 hover:bg-sky-400 transition"
        >Jetzt zurück</button>
        <button
          type="button"
          (click)="dismissRecheck()"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-sky-300 dark:border-sky-700 hover:bg-sky-100 dark:hover:bg-sky-900 transition"
        >Später</button>
      </div>
    </div>
  `,
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
