import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Switcher-Steuerpanel: Supermodell-Achse (an/aus) + Switching (Manuell /
 * Auto-Failover, nur klassisch) + Bereich/Pool-Toggle (cloud/free/local).
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-Background (slate-50/slate-900
 * dark) — passt zu EduPro-Style des umgebenden Cards in `AppComponent`.
 *
 * - **Manuell-Mode**: Claude Code läuft auf einem fixen Modell. Der Live-Wechsel
 *   passiert über den grünen „Als aktiv"-Button pro Zeile in der Modell-Tabelle
 *   (gefiltert über den Bereich-Toggle) — hier kein eigener Picker mehr.
 * - **Auto-Mode**: die Failover-Chain ist editierbar (Library-Component oben).
 *   Bei Quota-Erreichung wechselt der Wrapper automatisch zur nächsten Stufe.
 *
 * Events:
 * - `(modeChanged)` — `'manual' | 'auto'`
 * - `(categoryChanged)` — Bereich/Pool gewechselt (cloud|free|local)
 * - `(supermodelChanged)` — Supermodell an/aus
 * - `(promoteRequested)` — „Zurück zu Stufe 1"
 */
@Component({
  selector: 'sw-mode-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-5">
      <!-- Row 0: Supermodell (Orchestrierung an/aus) — die 2. Achse, gilt in JEDEM Bereich/Pool -->
      <div>
        <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
          <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400 sm:w-24 sm:shrink-0">Supermodell</span>
          <div class="inline-flex self-start rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
            <button
              type="button"
              (click)="setSupermodel(false)"
              class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
              [class.bg-slate-950]="!supermodel"
              [class.text-slate-50]="!supermodel"
              [class.dark:bg-slate-50]="!supermodel"
              [class.dark:text-slate-950]="!supermodel"
              [class.text-slate-500]="supermodel"
              [class.dark:text-slate-400]="supermodel"
            >Aus</button>
            <button
              type="button"
              (click)="setSupermodel(true)"
              class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
              [class.bg-indigo-600]="supermodel"
              [class.text-white]="supermodel"
              [class.text-slate-500]="!supermodel"
              [class.dark:text-slate-400]="!supermodel"
            >An</button>
          </div>
        </div>
        <p *ngIf="supermodel" class="mt-2 text-xs text-slate-500 dark:text-slate-400 sm:ml-28">Der Orchestrator plant, verteilt die Schritte auf die Rollen des gewählten Bereichs und prüft am Ende.</p>
      </div>

      <!-- Row 1: Switching (Manuell / Auto-Failover) — NUR im klassischen Modus.
           Bei Supermodell AN orchestriert der Orchestrator (gepinnt) + die Cascade macht das
           Rollen-Failover via Cooldown; ein Session-Auto-Failover würde den Orchestrator
           entpinnen → die Achse ist dann widersprüchlich + ausgeblendet
           (Backend erzwingt ohnehin mode=manual). -->
      <div *ngIf="!supermodel" class="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
        <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400 sm:w-24 sm:shrink-0">Switching</span>
        <div class="inline-flex self-start rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
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
      </div>

      <!-- Row 2: Bereich/Pool-Toggle — NUR die 3 Pools (cloud/free/local).
           KEIN „Auto"/Off-Zustand: ein Pool ist immer gewählt (Default cloud).
           Eigene Pill-Reihe statt der Library-Component, die zwingend einen
           Off-Button rendert. -->
      <div>
        <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
          <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400 sm:w-24 sm:shrink-0">Bereich</span>
          <div class="inline-flex self-start rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
            <button
              *ngFor="let p of categories"
              type="button"
              (click)="setCategory(p)"
              class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
              [class.bg-slate-950]="activeCategory === p"
              [class.text-slate-50]="activeCategory === p"
              [class.dark:bg-slate-50]="activeCategory === p"
              [class.dark:text-slate-950]="activeCategory === p"
              [class.text-slate-500]="activeCategory !== p"
              [class.dark:text-slate-400]="activeCategory !== p"
            >{{ poolButtonLabel(p) }}</button>
          </div>
        </div>
        <p *ngIf="categoryHintMap[activeCategory]" class="mt-2 text-xs text-slate-500 dark:text-slate-400 sm:ml-28">{{ categoryHintMap[activeCategory] }}</p>
      </div>

      <!-- Manuell-Mode: Der Live-Wechsel auf ein konkretes Modell läuft über den
           grünen „Als aktiv"-Button pro Zeile in der Modell-Tabelle unten
           (gefiltert über den Bereich-Toggle). Auto-Mode: Failover-Chain-Editor
           rendert <ki-cascade-mode-panel> oben. Hier kein eigener Picker mehr. -->
    </div>
  `,
})
export class ModePanelComponent {
  @Input() mode: 'manual' | 'auto' = 'manual';

  /**
   * v0.7.5 — Bereich-Toggle (Cascade-Kategorie). Liste der verfügbaren Kategorien
   * (kommt aus AppComponent via Cascades-API). Leer = Toggle wird ausgeblendet.
   *
   * Im Manuell-Mode filtert das Toggle den Picker. Im Auto-Mode steuert es
   * welcher Cascade-Bereich das Failover macht (Setting im cascade-Backend
   * via POST /api/preferred-category).
   */
  @Input() categories: string[] = [];
  /** Aktuell gewählte Kategorie. Leer = Semantic Routing (kein Override). */
  @Input() activeCategory: string = '';
  /**
   * Kurze Button-Titel pro Kategorie (z.B. „Cloud — Premium-Modelle" /
   * „Free Only — kostenfrei"). Werden vom Library-Component bevorzugt vor
   * categoryHintMap genutzt — sodass die Bereich-Toggle-Buttons dieselbe
   * Bezeichnung tragen wie die Cascade-Bereich-Cards unten.
   */
  @Input() categoryTitles: Record<string, string> = {};
  /** Optional: lange Hint-Strings pro Kategorie (kommt aus cascades-view) */
  @Input() categoryHintMap: Record<string, string> = {};
  /** v2: Supermodell-Modus aktiv? (Orchestrierung-Achse, unabhängig vom Bereich/Pool). */
  @Input() supermodel = false;

  @Output() modeChanged = new EventEmitter<'manual' | 'auto'>();
  @Output() promoteRequested = new EventEmitter<void>();
  /** v0.7.5 — User klickt einen Bereich-Tab → AppComponent ruft
   *  POST /api/preferred-category. Empty-String bedeutet „zurück zu Semantic Routing". */
  @Output() categoryChanged = new EventEmitter<string>();
  /** v2: Supermodell an/aus → AppComponent ruft POST /api/supermodel. */
  @Output() supermodelChanged = new EventEmitter<boolean>();

  setMode(m: 'manual' | 'auto'): void {
    if (this.mode === m) return;
    this.modeChanged.emit(m);
  }

  /** Supermodell-Achse: an/aus. Propagiert nach oben (AppComponent → POST /api/supermodel). */
  setSupermodel(on: boolean): void {
    if (this.supermodel === on) return;
    this.supermodelChanged.emit(on);
  }

  /**
   * Library-Component `<ki-cascade-mode-panel>` ruft das hier wenn der
   * User einen Bereich-Tab klickt. Wir propagieren nur nach oben — App-
   * Component persistiert via POST /api/preferred-category.
   */
  setCategory(c: string): void {
    if (this.activeCategory === c) return;
    this.categoryChanged.emit(c);
  }

  /** Kurzes Button-Label pro Pool (Cloud / Free / Lokal). */
  poolButtonLabel(p: string): string {
    const short: Record<string, string> = { cloud: 'Cloud', free: 'Free', local: 'Lokal' };
    return short[p] || this.categoryTitles[p] || p;
  }
}
