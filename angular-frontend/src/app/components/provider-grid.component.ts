import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface ProviderModel { id: string; name: string; free?: boolean }

/** Hard-coded Provider-Modell-Listen (gleich wie Vanilla — keine Library für Display-Namen). */
export const PROVIDER_MODELS: Record<string, ProviderModel[]> = {
  anthropic: [
    { id: 'claude-opus-4-7',            name: 'Claude Opus 4.7' },
    { id: 'claude-sonnet-4-6',          name: 'Claude Sonnet 4.6' },
    { id: 'claude-haiku-4-5-20251001',  name: 'Claude Haiku 4.5' },
    { id: 'claude-3-5-sonnet-20241022', name: 'Claude 3.5 Sonnet' },
  ],
  google: [
    { id: 'gemini-2.5-pro',         name: 'Gemini 2.5 Pro' },
    { id: 'gemini-2.5-flash',       name: 'Gemini 2.5 Flash' },
    { id: 'gemini-2.5-flash-lite',  name: 'Gemini 2.5 Flash Lite' },
    { id: 'gemini-3-pro-preview',   name: 'Gemini 3 Pro (Preview)' },
    { id: 'gemini-3-flash-preview', name: 'Gemini 3 Flash (Preview)' },
  ],
  openrouter: [
    { id: 'anthropic/claude-sonnet-4.5',            name: 'Claude Sonnet 4.5' },
    { id: 'google/gemini-2.5-pro',                  name: 'Gemini 2.5 Pro' },
    { id: 'google/gemini-2.5-flash',                name: 'Gemini 2.5 Flash' },
    { id: 'meta-llama/llama-3.3-70b-instruct:free', name: 'Llama 3.3 70B', free: true },
    { id: 'openai/gpt-oss-120b:free',               name: 'GPT-OSS 120B',  free: true },
    { id: 'nvidia/nemotron-nano-9b-v2:free',        name: 'Nemotron Nano 9B', free: true },
    { id: 'deepseek/deepseek-chat-v3.1',            name: 'DeepSeek Chat V3.1' },
  ],
};

/**
 * Provider-Cards + Models-Grid + Switch-Action (manueller Modus).
 *
 * Klick auf Provider-Card → wechselt zur Provider-Auswahl, zeigt Models darunter.
 * Klick auf Model-Card → emittiert `(switchTo)` mit `{provider, modelId}` —
 * der Parent ruft Backend `/api/switch` auf.
 */
@Component({
  selector: 'sw-provider-grid',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="provider-grid">
      <div *ngFor="let p of providers"
           class="provider-card"
           [class.active]="activeProvider === p.id"
           (click)="setProvider(p.id)">
        <div class="p-top">
          <div class="p-icon" [class]="p.iconClass">{{ p.icon }}</div>
          <div class="p-check" *ngIf="activeProvider === p.id">✓</div>
        </div>
        <div class="p-name">{{ p.name }}</div>
        <div class="p-desc">{{ p.desc }}</div>
      </div>
    </div>

    <div class="model-section">
      <h3 class="model-title">Modell — {{ providerNameOf(selectedProvider) }}</h3>
      <div class="model-grid">
        <div *ngFor="let m of modelsFor(selectedProvider)"
             class="model-card"
             [class.active]="isActiveModel(m.id)"
             (click)="onModelClick(m.id)">
          <div class="m-name">{{ m.name }}</div>
          <div class="m-id">{{ m.id }}</div>
          <div class="m-tag" *ngIf="m.free">FREE</div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .provider-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0.75rem; margin-bottom: 1.5rem; }
    .provider-card {
      padding: 1rem; background: #161616; border: 1px solid #2a2a2a; border-radius: 0.75rem;
      cursor: pointer; transition: border-color 0.15s, background 0.15s;
    }
    .provider-card:hover { border-color: #555; }
    .provider-card.active { border-color: #38bdf8; background: #0a1a2a; }
    .p-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.6rem; }
    .p-icon { width: 2rem; height: 2rem; display: grid; place-items: center; font-size: 1.2rem; background: #1f1f1f; border-radius: 0.5rem; }
    .p-check { color: #38bdf8; font-weight: 800; }
    .p-name { color: #e5e5e5; font-weight: 700; font-size: 0.9rem; margin-bottom: 0.2rem; }
    .p-desc { color: #888; font-size: 0.7rem; }
    .model-section { margin-bottom: 1rem; }
    .model-title { color: #e5e5e5; font-size: 0.75rem; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; margin: 0 0 0.6rem; }
    .model-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.5rem; }
    .model-card {
      padding: 0.7rem; background: #161616; border: 1px solid #2a2a2a; border-radius: 0.5rem;
      cursor: pointer; position: relative;
    }
    .model-card:hover { border-color: #555; }
    .model-card.active { border-color: #10b981; background: #0a2418; }
    .m-name { color: #e5e5e5; font-weight: 700; font-size: 0.8rem; }
    .m-id { color: #888; font-size: 0.65rem; font-family: ui-monospace, monospace; margin-top: 0.2rem; }
    .m-tag {
      position: absolute; top: 0.4rem; right: 0.4rem;
      padding: 0.1rem 0.3rem; background: #064e3b; color: #6ee7b7;
      font-size: 0.55rem; font-weight: 800; letter-spacing: 0.08em; border-radius: 3px;
    }
  `],
})
export class ProviderGridComponent {
  @Input() activeProvider: string | null = null;
  @Input() activeModel: string | null = null;
  @Output() switchTo = new EventEmitter<{ provider: string; modelId: string }>();

  selectedProvider = 'anthropic';

  readonly providers = [
    { id: 'anthropic',  name: 'Anthropic',         desc: 'OAuth via Claude Desktop',     icon: '🟠', iconClass: 'ant' },
    { id: 'google',     name: 'Google AI Studio',  desc: 'Direkt · Gemini-Modelle',      icon: '🔷', iconClass: 'go' },
    { id: 'openrouter', name: 'OpenRouter',        desc: 'Gateway · viele Modelle',      icon: '🟢', iconClass: 'or' },
  ];

  ngOnChanges(): void {
    if (this.activeProvider && this.selectedProvider !== this.activeProvider) {
      this.selectedProvider = this.activeProvider;
    }
  }

  setProvider(id: string): void {
    this.selectedProvider = id;
  }

  modelsFor(provider: string): ProviderModel[] {
    return PROVIDER_MODELS[provider] || [];
  }

  providerNameOf(id: string): string {
    return this.providers.find((p) => p.id === id)?.name || id;
  }

  isActiveModel(id: string): boolean {
    return this.activeProvider === this.selectedProvider && this.activeModel === id;
  }

  onModelClick(modelId: string): void {
    this.switchTo.emit({ provider: this.selectedProvider, modelId });
  }
}
