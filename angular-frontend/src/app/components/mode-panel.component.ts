import { Component, EventEmitter, Input, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

/**
 * Mode-Toggle (Manuell / Auto-Failover) + Aktiv-Picker (Manuell) +
 * Chain-Editor (Auto) — Switcher-Spezifisch.
 *
 * Look-and-Feel: Tailwind, hell auf weißem Card-Background (slate-50/slate-900
 * dark) — passt zu EduPro-Style des umgebenden Cards in `AppComponent`.
 *
 * - **Manuell-Mode**: zeigt den aktuellen aktiven Provider+Modell plus einen
 *   Picker zum Wechseln. Der „Aktiv"-Toggle in der Modell-Tabelle aktiviert
 *   nur die Cascade-Slot, nicht den Live-Provider — deshalb braucht der
 *   Manuell-Mode einen eigenen Wechsel-Button (siehe `(switchTo)`-Event).
 * - **Auto-Mode**: die Failover-Chain ist editierbar. Bei Quota-Erreichung
 *   wechselt der Wrapper automatisch zur nächsten Stufe.
 *
 * Events:
 * - `(modeChanged)` — `'manual' | 'auto'`
 * - `(chainChanged)` — neue Chain-Liste
 * - `(promoteRequested)` — User klickt „Zurück zu Stufe 1"
 * - `(switchTo)` — Manuell-Mode: User klickt „Wechseln" → AppComponent ruft
 *   `/api/switch` mit `{provider, model}`. Triggered Wrapper-Restart von
 *   Claude Code mit dem neuen Provider.
 */
@Component({
  selector: 'sw-mode-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div>
      <!-- Row 1: Switching (Manuell / Auto-Failover) — primärer Mode-Toggle -->
      <div class="flex items-center gap-3 flex-wrap">
        <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">Switching</span>
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
      </div>

      <!-- Row 2: Bereich-Toggle (Cascade-Kategorie) — filtert Manuell-Picker
           und steuert in Auto-Mode welcher Cascade-Bereich das Failover macht -->
      <div *ngIf="categories.length > 0" class="mt-3 flex items-center gap-3 flex-wrap">
        <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">Bereich</span>
        <div class="inline-flex rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
          <button *ngFor="let c of categories"
                  type="button"
                  (click)="setCategory(c)"
                  class="px-4 py-1.5 text-xs font-bold tracking-wide rounded-full transition"
                  [class.bg-slate-950]="activeCategory === c"
                  [class.text-slate-50]="activeCategory === c"
                  [class.dark:bg-slate-50]="activeCategory === c"
                  [class.dark:text-slate-950]="activeCategory === c"
                  [class.text-slate-500]="activeCategory !== c"
                  [class.dark:text-slate-400]="activeCategory !== c">
            {{ categoryLabel(c) }}
          </button>
        </div>
        <span class="text-xs text-slate-500 dark:text-slate-400 italic">
          {{ activeCategoryHint() }}
        </span>
      </div>

      <!-- Manuell-Mode: Picker mit gefilterten Modellen (nach activeCategory) -->
      <div *ngIf="mode === 'manual'" class="mt-4 rounded-2xl bg-slate-50 dark:bg-slate-800 p-4 sm:p-5 ring-1 ring-slate-200 dark:ring-slate-700">
        <p class="text-sm text-slate-600 dark:text-slate-300 mb-3">
          <strong class="font-semibold text-slate-900 dark:text-slate-100">Aktiver Provider</strong>
          — Claude Code läuft auf
          <span class="font-mono text-slate-900 dark:text-slate-100">{{ activeProvider || '–' }}</span><span *ngIf="activeModel"> · <span class="font-mono text-slate-900 dark:text-slate-100">{{ activeModel }}</span></span>.
        </p>

        <!-- Single-Select Combobox: filtered by activeCategory wenn gesetzt -->
        <div *ngIf="availableModelsForCategory().length > 0; else noActive" class="flex flex-wrap items-center gap-2">
          <span class="text-xs font-bold text-slate-500 dark:text-slate-400">Wechseln zu:</span>
          <select
            [(ngModel)]="pickerModelKey"
            class="flex-1 min-w-[16rem] rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-2.5 py-1.5 text-sm text-slate-900 dark:text-slate-100"
          >
            <option *ngFor="let m of availableModelsForCategory()"
                    [value]="m.provider + ':' + m.modelId">
              {{ providerLabel(m.provider) }} · {{ m.displayName }}
            </option>
          </select>
          <button
            type="button"
            (click)="emitSwitch()"
            [disabled]="!pickerModelKey || isAlreadyActiveKey()"
            class="px-4 py-1.5 text-xs font-bold rounded-lg bg-slate-950 dark:bg-slate-50 text-slate-50 dark:text-slate-950 hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed transition"
          >Wechseln</button>
        </div>

        <ng-template #noActive>
          <p class="text-sm text-slate-500 dark:text-slate-400 italic">
            <span *ngIf="!activeCategory; else noActiveInCategory">
              Keine aktiven Modelle. Aktiviere ein Modell in der Tabelle unten (Toggle „Aktiv" pro Zeile),
              dann kannst du es hier auswählen.
            </span>
            <ng-template #noActiveInCategory>
              Keine aktiven Modelle im Bereich „{{ categoryLabel(activeCategory) }}".
              Aktiviere eines in der Tabelle unten oder wechsle den Bereich.
            </ng-template>
          </p>
        </ng-template>

        <p class="mt-3 text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          Nach Klick auf „Wechseln" startet der Wrapper Claude Code mit dem neuen Provider neu
          (Kontext via <code class="px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300">--resume</code> erhalten).
          Schneller geht's über den grünen „Als aktiv"-Button direkt in der Modell-Tabelle.
        </p>
      </div>

      <!-- Auto-Mode: Info-Card statt Chain-Editor — die Cascade-Bereich-Card
           unten ist die SoT für Reihenfolge + Cooldown -->
      <div *ngIf="mode === 'auto'" class="mt-4 rounded-2xl bg-slate-50 dark:bg-slate-800 p-4 sm:p-5 ring-1 ring-slate-200 dark:ring-slate-700">
        <ng-container *ngIf="activeCategory; else autoNoCategory">
          <p class="text-sm text-slate-700 dark:text-slate-200 mb-3">
            <strong class="font-semibold text-slate-900 dark:text-slate-100">Auto-Failover läuft</strong>
            via Cascade-Bereich
            <span class="font-mono text-slate-900 dark:text-slate-100">{{ categoryLabel(activeCategory) }}</span>.
            Reihenfolge + Cooldown-Konfiguration siehe Card unten.
          </p>
          <button
            type="button"
            (click)="scrollToCascades()"
            class="px-4 py-1.5 text-xs font-bold rounded-lg bg-slate-900 dark:bg-slate-100 text-slate-50 dark:text-slate-900 hover:opacity-90 transition"
          >↓ Zur Cascade-Konfiguration</button>
        </ng-container>
        <ng-template #autoNoCategory>
          <p class="text-sm text-slate-600 dark:text-slate-300">
            <strong class="font-semibold text-slate-900 dark:text-slate-100">Semantic Routing aktiv</strong>
            — die Cascade entscheidet pro Generate-Call welcher Bereich genutzt wird.
            Wähle oben einen Bereich (Cloud / Free Only) wenn du das explizit überschreiben willst.
          </p>
        </ng-template>
      </div>
    </div>
  `,
})
export class ModePanelComponent {
  @Input() mode: 'manual' | 'auto' = 'manual';
  /** Aktueller Provider (anthropic|google|openrouter), für Manuell-Picker-Defaults. */
  @Input() activeProvider: string | null = null;
  /** Aktuelles Modell, für Manuell-Picker-Defaults. */
  @Input() activeModel: string | null = null;
  /**
   * Liste der **aktiven** Cascade-Modelle (enabled + Key gesetzt). Wird nach
   * `activeCategory` gefiltert für den Manuell-Picker.
   * `category` kommt aus dem cascade-Modell — wird hier zur Filterung benutzt.
   */
  @Input() availableModels: { provider: string; modelId: string; displayName: string; category?: string | null }[] = [];

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
  /** Optional: Hint-Strings pro Kategorie (kommt aus cascades-view) */
  @Input() categoryHintMap: Record<string, string> = {};

  @Output() modeChanged = new EventEmitter<'manual' | 'auto'>();
  @Output() promoteRequested = new EventEmitter<void>();
  /** Manuell-Mode: User klickt „Wechseln" → AppComponent ruft `/api/switch`. */
  @Output() switchTo = new EventEmitter<{ provider: string; model: string }>();
  /** v0.7.5 — User klickt einen Bereich-Tab → AppComponent ruft
   *  POST /api/preferred-category. Empty-String bedeutet „zurück zu Semantic Routing". */
  @Output() categoryChanged = new EventEmitter<string>();

  /** Composite key `provider:modelId` für die Single-Select-Combobox.
   *  Wird beim Submit gesplittet und als {provider, model} emittiert. */
  pickerModelKey = '';

  ngOnChanges(changes: SimpleChanges): void {
    // Picker-State refreshen wenn die gefilterte Liste sich geändert hat
    // (availableModels-Input ODER activeCategory geändert).
    if (changes['availableModels'] || changes['activeProvider'] || changes['activeModel'] || changes['activeCategory']) {
      const filtered = this.availableModelsForCategory();
      if (filtered.length === 0) {
        this.pickerModelKey = '';
        return;
      }
      // Bevorzugt: aktives Modell wenn es in der gefilterten Liste ist
      const activeKey = this.activeProvider && this.activeModel
        ? `${this.activeProvider}:${this.activeModel}` : '';
      const activeStillInList = activeKey && filtered.some((m) => `${m.provider}:${m.modelId}` === activeKey);
      const currentStillInList = this.pickerModelKey && filtered.some((m) => `${m.provider}:${m.modelId}` === this.pickerModelKey);
      if (activeStillInList) {
        this.pickerModelKey = activeKey;
      } else if (!currentStillInList) {
        // Vorherige Wahl ist nach Filter-Wechsel nicht mehr drin → erste Option
        const first = filtered[0];
        this.pickerModelKey = `${first.provider}:${first.modelId}`;
      }
    }
  }

  /**
   * Aktive Modelle gefiltert nach `activeCategory`. Wenn `activeCategory`
   * leer ist (Semantic Routing): alle Modelle. Sonst: nur die mit passender
   * `category` ODER ohne Category-Marker (general/null = überall sichtbar).
   */
  availableModelsForCategory(): { provider: string; modelId: string; displayName: string; category?: string | null }[] {
    if (!this.activeCategory) return this.availableModels;
    return this.availableModels.filter((m) => m.category === this.activeCategory);
  }

  /** Human-readable Provider-Label. */
  providerLabel(p: string): string {
    switch (p) {
      case 'anthropic':  return 'Anthropic';
      case 'google':     return 'Google AI Studio';
      case 'openrouter': return 'OpenRouter';
      case 'ollama':     return 'Ollama (lokal)';
      case 'gemini':     return 'Google Gemini';
      default:           return p;
    }
  }

  /**
   * Picker-Submit: splittet den composite Key zurück in {provider, model}
   * und propagiert nach oben (App-Component ruft `/api/switch`).
   */
  emitSwitch(): void {
    if (!this.pickerModelKey) return;
    const idx = this.pickerModelKey.indexOf(':');
    if (idx < 0) return;
    const provider = this.pickerModelKey.substring(0, idx);
    const model = this.pickerModelKey.substring(idx + 1);
    if (!provider || !model) return;
    this.switchTo.emit({ provider, model });
  }

  /** True wenn das gewählte Picker-Modell schon das aktive Modell ist. */
  isAlreadyActiveKey(): boolean {
    if (!this.activeProvider || !this.activeModel) return false;
    return this.pickerModelKey === `${this.activeProvider}:${this.activeModel}`;
  }

  setMode(m: 'manual' | 'auto'): void {
    if (this.mode === m) return;
    this.modeChanged.emit(m);
  }

  /**
   * v0.7.5 — User klickt einen Bereich-Tab. Idempotent (kein Re-Emit wenn schon
   * aktiv). AppComponent fängt das Event und ruft das Backend
   * (POST /api/preferred-category).
   */
  setCategory(c: string): void {
    if (this.activeCategory === c) return;
    this.categoryChanged.emit(c);
  }

  /**
   * Lesbares Label pro Kategorie. Wir lookup'en zuerst {@link categoryHintMap}
   * (Konsument-konfigurierbar via labels.de.ts), Fallback auf einen
   * capitalized-Slug ("free-only" → "Free Only").
   */
  categoryLabel(c: string): string {
    const hint = this.categoryHintMap?.[c];
    if (hint) return hint;
    return c.split(/[-_]/).map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
  }

  /**
   * Untertitel-Hint rechts vom Toggle, erklärt was der aktive Bereich tut.
   */
  activeCategoryHint(): string {
    if (!this.activeCategory) {
      return 'Semantic Routing — Cascade entscheidet pro Call.';
    }
    return `Override: alle Generate-Calls gehen an „${this.categoryLabel(this.activeCategory)}".`;
  }

  /**
   * v0.7.5 — Scroll zur Cascade-Bereiche-Section (Auto-Mode-Info-Card-Button).
   * Die Section bekommt id="cascade-bereiche-section" im AppComponent-Template.
   */
  scrollToCascades(): void {
    if (typeof document === 'undefined') return;
    const el = document.getElementById('cascade-bereiche-section');
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
