import { Component, EventEmitter, Input, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CascadeModePanelComponent } from '@4dataclub/ki-models-ui';

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
  imports: [CommonModule, FormsModule, CascadeModePanelComponent],
  template: `
    <div>
      <!-- Row 0: Supermodell (Orchestrierung an/aus) — die 2. Achse, gilt in JEDEM Bereich/Pool -->
      <div class="flex items-center gap-3 flex-wrap mb-3">
        <span class="text-[10px] font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">Supermodell</span>
        <div class="inline-flex rounded-full bg-slate-100 dark:bg-slate-800 p-1 ring-1 ring-slate-200 dark:ring-slate-700">
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
          >An — Opus orchestriert</button>
        </div>
        <span *ngIf="supermodel" class="text-xs text-slate-500 dark:text-slate-400">Opus plant &amp; verteilt im gewählten Bereich, prüft am Ende.</span>
      </div>

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

      <!-- Row 2: Bereich-Toggle (Library-Component v0.13.0). Filtert
           Manuell-Picker UND steuert in Auto-Mode welcher Cascade-Bereich
           das Failover macht. Auto-Info-Card wird per [autoMode]-Input
           gated und nutzt scrollTargetId für den ↓-Button. -->
      <div class="mt-3">
        <ki-cascade-mode-panel
          [categories]="categories"
          [activeCategory]="activeCategory"
          [categoryTitles]="categoryTitles"
          [categoryHintMap]="categoryHintMap"
          [autoMode]="mode === 'auto'"
          scrollTargetId="cascade-bereiche-section"
          [labels]="cascadeModePanelLabels"
          (categoryChanged)="setCategory($event)">
        </ki-cascade-mode-panel>
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

      <!-- Auto-Mode Info-Card wird jetzt von <ki-cascade-mode-panel> oben
           gerendert (siehe [autoMode]="mode === 'auto'"). Hier keine eigene
           Info-Card mehr nötig. -->
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
  /** Manuell-Mode: User klickt „Wechseln" → AppComponent ruft `/api/switch`. */
  @Output() switchTo = new EventEmitter<{ provider: string; model: string }>();
  /** v0.7.5 — User klickt einen Bereich-Tab → AppComponent ruft
   *  POST /api/preferred-category. Empty-String bedeutet „zurück zu Semantic Routing". */
  @Output() categoryChanged = new EventEmitter<string>();
  /** v2: Supermodell an/aus → AppComponent ruft POST /api/supermodel. */
  @Output() supermodelChanged = new EventEmitter<boolean>();

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

  /**
   * Lesbares Kategorie-Label für die „Keine Modelle im Bereich"-Meldung.
   * Identisch zu der Logik in `<ki-cascade-mode-panel>` damit die UX
   * konsistent bleibt.
   */
  categoryLabel(c: string): string {
    const hint = this.categoryHintMap?.[c];
    if (hint) return hint;
    return c.split(/[-_]/).map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
  }

  /**
   * Deutsche Labels für die Library-Component `<ki-cascade-mode-panel>`.
   * Stabil als readonly damit es nicht bei jedem Change-Detection-Tick
   * neue Referenzen gibt (Performance + verhindert ngOnChanges-Schleifen).
   */
  readonly cascadeModePanelLabels = {
    toggleLegend: 'Bereich',
    hintSemanticRouting: 'Auto-Routing — Cascade entscheidet pro Call welcher Bereich.',
    hintOverrideTemplate: 'Override: alle Generate-Calls gehen an „{cat}".',
    autoCardActiveTemplate: 'Auto-Failover läuft via Cascade-Bereich „{cat}". Reihenfolge + Cooldown siehe Card unten.',
    autoCardSemanticHint: 'Auto-Routing aktiv — wähle einen Bereich oben für gezielten Override.',
    btnScrollToCascade: '↓ Zur Cascade-Konfiguration',
    offButtonLabel: 'Auto',
  };
}
