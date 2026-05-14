import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ModelsTableComponent,
  AddModelFormComponent,
  CascadeCooldownComponent,
  ApiKeysSectionComponent,
} from '@dataclub/ki-models-ui';

/**
 * Switcher Angular-App (Phase L.4 MVP).
 *
 * Skeleton-Shell:
 * - Status-Header (current model via /api/whoami)
 * - 4 Library-Tags für Cascade-Verwaltung (Models-Table, Add-Form, Cooldown,
 *   API-Keys)
 *
 * **Out-of-Scope für MVP** (Vanilla-Frontend hat das noch):
 * - Mode-Toggle (Manual / Auto-Failover)
 * - Chain-Editor
 * - Provider-Cards mit Switch-Action (ccr-Restart-Marker)
 * - Quota-Banner + Recheck-Banner
 * - Restart-Button
 *
 * Diese Features kommen in L.4-Followup-Commits. Aktuell liefert dieser MVP
 * eine bedienbare KI-Modelle-Verwaltungs-UI für Switcher, ähnlich wie der
 * KI-Modelle-Tab in EduPro — alles über die geteilte Library.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    ModelsTableComponent,
    AddModelFormComponent,
    CascadeCooldownComponent,
    ApiKeysSectionComponent,
  ],
  template: `
    <main class="shell">
      <header class="hdr">
        <h1>Claude Code Switcher</h1>
        <div class="whoami" *ngIf="whoami() as w">{{ w }}</div>
      </header>

      <section class="section">
        <h2>Cascade-Cooldown</h2>
        <ki-cascade-cooldown></ki-cascade-cooldown>
      </section>

      <section class="section">
        <h2>Cascade-Modelle</h2>
        <ki-models-table (modelChanged)="bumpReload()"></ki-models-table>
        <ki-add-model-form (modelCreated)="bumpReload()"></ki-add-model-form>
      </section>

      <section class="section">
        <ki-api-keys-section (keyChanged)="bumpReload()"></ki-api-keys-section>
      </section>

      <footer class="ftr">
        <p>Phase L.4 MVP — Library-Komponenten live. Mode-Toggle / Chain-Editor / Provider-Switch / Banner folgen.</p>
      </footer>
    </main>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; background: #0a0a0a; color: #e5e5e5; font-family: ui-sans-serif, system-ui, sans-serif; }
    .shell { max-width: 1100px; margin: 0 auto; padding: 1.5rem; }
    .hdr { display: flex; justify-content: space-between; align-items: baseline; padding: 1rem 0 2rem; border-bottom: 1px solid #2a2a2a; margin-bottom: 1.5rem; }
    .hdr h1 { font-size: 1.5rem; font-weight: 800; letter-spacing: -0.02em; margin: 0; }
    .whoami { font-family: ui-monospace, monospace; font-size: 0.85rem; color: #9ca3af; }
    .section { background: #ffffff; color: #0f172a; padding: 1.5rem 2rem; border-radius: 1rem; margin-bottom: 1.5rem; }
    .section h2 { font-size: 0.875rem; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; color: #475569; margin: 0 0 1rem; }
    .ftr { padding: 1rem 0; color: #6b7280; font-size: 0.75rem; text-align: center; }
  `],
})
export class AppComponent {
  private readonly http = inject(HttpClient);
  readonly whoami = signal<string | null>(null);
  readonly reloadKey = signal(0);

  ngOnInit(): void {
    this.http.get('/api/whoami', { responseType: 'text' }).subscribe({
      next: (text) => this.whoami.set(text),
      error: () => this.whoami.set('(switcher unreachable)'),
    });
  }

  bumpReload(): void {
    this.reloadKey.update((n) => n + 1);
  }
}
