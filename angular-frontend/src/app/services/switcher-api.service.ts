import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Switcher-spezifischer API-Service. Sprächt die Endpoints an, die NICHT
 * über die ki-models-ui Library laufen (Status, Switch, Auto-Failover,
 * Banner, Restart). Library-Endpoints (`/api/ai-models`, `/api/api-keys`,
 * `/api/cascade-config`) werden direkt von der Library aufgerufen.
 */

export interface SwitcherStatus {
  provider: string;
  model?: string | null;
  mode: 'manual' | 'auto';
  fallback_chain?: ChainEntry[];
  chain_position?: number;
  activeRoute?: { model?: string };
  hasAnthropicKey?: boolean;
  hasGoogleKey?: boolean;
  hasOpenrouterKey?: boolean;
  anthropicKeyMasked?: string;
  googleKeyMasked?: string;
  openrouterKeyMasked?: string;
  lastWarn?: { at: number; percent: number; project: string };
}

export interface ChainEntry {
  provider: string;
  model: string;
}

export interface SwitchRequest {
  provider: string;
  model: string;
  apiKeys?: Record<string, string>;
}

export interface AutoConfig {
  mode: 'manual' | 'auto';
  fallback_chain?: ChainEntry[];
  chain_position?: number;
}

export interface SwitcherAiModel {
  id: number;
  provider: string;        // 'gemini' | 'anthropic' | 'openrouter' (cascade-Namensraum)
  modelId: string;
  displayName: string | null;
  apiKeySettingKey: string;
  enabled: boolean;
  keyConfigured: boolean;
  autoDisabled?: boolean;
}

@Injectable({ providedIn: 'root' })
export class SwitcherApiService {
  private readonly http = inject(HttpClient);

  status(): Observable<SwitcherStatus> {
    return this.http.get<SwitcherStatus>('/api/status');
  }

  /** Modell-Liste vom Cascade-Backend, identisch zu dem was die ki-models-ui-Library sieht. */
  listAiModels(): Observable<SwitcherAiModel[]> {
    return this.http.get<SwitcherAiModel[]>('/api/ai-models');
  }

  whoami(): Observable<string> {
    return this.http.get('/api/whoami', { responseType: 'text' });
  }

  switchProvider(body: SwitchRequest): Observable<any> {
    return this.http.post('/api/switch', body);
  }

  setAuto(body: AutoConfig): Observable<any> {
    return this.http.post('/api/auto', body);
  }

  chainPromote(): Observable<any> {
    return this.http.post('/api/chain-promote', {});
  }

  chainReset(): Observable<any> {
    return this.http.post('/api/chain-reset', {});
  }

  restart(): Observable<any> {
    return this.http.post('/api/restart', {});
  }

  banner(): Observable<string> {
    return this.http.get('/api/banner', { responseType: 'text' });
  }

  /** SSE-Stream — wird von einem Component direkt mit EventSource konsumiert (nicht über HttpClient). */
  eventsUrl(): string {
    return '/api/events';
  }
}
