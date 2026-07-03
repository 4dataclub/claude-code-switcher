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
  /** 2-Achsen-Supermodell: Pool + Schalter + Local-Orchestrator-Pending. */
  pool?: string;
  supermodel?: boolean;
  localOrchestratorPending?: boolean;
  fallback_chain?: ChainEntry[];
  chain_position?: number;
  activeRoute?: { model?: string; topModel?: string };
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
  /** v0.7.5: Cascade-Kategorie ('cloud', 'free-only', null=general).
   *  Mode-Panel filtert die Manuell-Picker-Liste danach. */
  category?: string | null;
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

  // ─── v0.7.5: Cascade-Bereiche + Preferred-Category Toggle ────────────────

  /** Liste der konfigurierten Cascade-Bereiche (für das Bereich-Toggle im
   *  Modus-Panel). Proxy zum llm-cascade `/api/cascades`. */
  listCascades(): Observable<any[]> {
    return this.http.get<any[]>('/api/cascades');
  }

  /** Aktuell vom User gewählter Cascade-Override.
   *  Leerer String = Semantic Routing aktiv. */
  getPreferredCategory(): Observable<{ category: string; active: boolean; note?: string }> {
    return this.http.get<{ category: string; active: boolean; note?: string }>('/api/preferred-category');
  }

  /** Setzt den Cascade-Override. `""` = zurück zu Semantic Routing. */
  setPreferredCategory(category: string): Observable<{ ok: boolean; category: string }> {
    return this.http.post<{ ok: boolean; category: string }>(
      '/api/preferred-category',
      { category }
    );
  }

  // ─── 2-Achsen-Modus: Pool (cloud|free|local) × Supermodell ───────────────

  /** Aktueller Supermodell-State inkl. Pool + Local-Orchestrator-Pending-Flag. */
  getSupermodel(): Observable<{ enabled: boolean; pool?: string; localOrchestratorPending?: boolean }> {
    return this.http.get<{ enabled: boolean; pool?: string; localOrchestratorPending?: boolean }>('/api/supermodel');
  }

  /** Back-compat: nur die Supermodell-Achse togglen (Pool unverändert). */
  setSupermodel(enabled: boolean): Observable<{ success: boolean; enabled: boolean; restart: boolean }> {
    return this.http.post<{ success: boolean; enabled: boolean; restart: boolean }>(
      '/api/supermodel',
      { enabled }
    );
  }

  /**
   * Pool-bewusster 2-Achsen-Modus. Beide Felder optional (partielles Update).
   * `pool=local` ist fail-closed (kein Cloud-Ausweich) — siehe Backend.
   */
  setMode(body: { pool?: string; supermodel?: boolean }): Observable<{
    success: boolean; pool: string; supermodel: boolean; restart: boolean;
    localOrchestratorPending?: boolean; note?: string;
  }> {
    return this.http.post<any>('/api/mode', body);
  }

  /** Kategorie-Metadaten (displayName/description/orderIdx) — Quelle für die
   *  dynamischen categoryTitles (Renames propagieren ohne Hardcode). */
  listCategoryMetas(): Observable<{ name: string; displayName?: string | null; description?: string | null; orderIdx?: number | null }[]> {
    return this.http.get<any[]>('/api/categories');
  }
}
