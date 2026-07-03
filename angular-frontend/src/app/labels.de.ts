/**
 * Deutsche Labels für die ki-models-ui Library-Components.
 * Switcher hat keinen i18n-Service — stattdessen statische deutsche Strings
 * die analog zu EduPros i18n-Pipe befüllt werden.
 *
 * Gleiche Wording wie EduPros `admin.aiModels.*` i18n-Keys damit beide
 * Konsumenten visuell + sprachlich konsistent sind.
 */
import {
  ModelsTableLabels,
  AddModelFormLabels,
  CascadesViewLabels,
  ApiKeysSectionLabels,
  FailoverChainLabels,
  ProviderServersLabels,
} from '@4dataclub/ki-models-ui';

export const MODELS_TABLE_LABELS_DE: Partial<ModelsTableLabels> = {
  title: 'Konfigurierte Modelle',
  refresh: '↻ Aktualisieren',
  loading: 'Lade Modelle…',
  empty: 'Keine Modelle konfiguriert. Füge unten eines hinzu.',
  colNum: '#',
  colProvider: 'Provider',
  colModelId: 'Modell-ID',
  colKey: 'Key',
  colEnabled: 'Aktiv',
  colStatus: 'Status',
  colActions: 'Aktionen',
  colServer: 'Server',
  serverDefault: 'Standard (localhost)',
  keySet: 'Key gesetzt',
  keyMissing: 'Key fehlt',
  on: 'AN',
  off: 'AUS',
  autoDisabled: 'Auto-deaktiviert',
  free: 'Frei',
  hardwareBlocked: 'Hardware reicht nicht',
  toggleHardwareBlocked: 'Server-Hardware reicht nicht — mehr RAM, externen Server nutzen oder kleineres Modell wählen',
  toggleNeedsKey: 'Key zuerst setzen',
  btnTest: 'Test',
  btnReenable: 'Reaktivieren',
  btnDelete: 'Löschen',
  btnSetActive: 'Als aktiv',
  activeBadge: 'AKTIV',
  confirmDelete: (id: string) => `Modell "${id}" wirklich löschen?`,
  btnEdit: 'Bearbeiten',
  editTitle: 'Modell bearbeiten',
  editFieldProvider: 'Provider',
  editFieldModelId: 'Modell-ID',
  editFieldDisplayName: 'Anzeigename',
  editFieldCategory: 'Kategorie',
  editFieldApiKeySettingKey: 'API-Key-Setting',
  editFieldCooldown: 'Cooldown 503 (Sek.)',
  editFieldServer: 'Inferenz-Server',
  btnSave: 'Speichern',
  btnSaving: 'Speichere…',
  btnCancel: 'Abbrechen',
  editError: 'Aktualisieren fehlgeschlagen',
};

export const ADD_MODEL_FORM_LABELS_DE: Partial<AddModelFormLabels> = {
  title: 'Neues Modell hinzufügen',
  fieldModelId: 'Modell-ID (z.B. gemini-2.5-flash)',
  fieldApiKeySettingKey: 'API-Key-Setting',
  fieldDisplayName: 'Anzeigename (optional)',
  fieldCategory: 'Kategorie',
  fieldProviderServer: 'Inferenz-Server (optional, Standard localhost)',
  fieldCooldownOverride: 'Cooldown 503 (Sek., optional)',
  btnAdd: 'Hinzufügen',
  btnAdding: 'Speichere…',
  hint: 'Der API-Key liegt im Settings-Store — siehe unten „API-Keys". Mehrere Modelle können denselben Key teilen. Kategorie steuert die Cascade: cloud für bezahlte Tier-Modelle, free-only für kostenfreie OpenRouter-Modelle.',
  errorRequired: 'Provider, Modell-ID und Setting-Key sind Pflicht',
  errorFailed: 'Speichern fehlgeschlagen',
  // categoryOptions wird in AppComponent.addModelFormLabels zustands-abhängig
  // (Pool + Supermodell) gesetzt — siehe categoryOptions().
};

export const CASCADES_VIEW_LABELS_DE: Partial<CascadesViewLabels> = {
  loading: 'Lade Cascade-Bereiche…',
  empty: 'Noch keine Cascade-Bereiche konfiguriert.',
  emptyHint: 'Füge mindestens ein Modell mit einem category-Wert hinzu, damit es hier als Karte erscheint.',
  defaultHint: 'Routing-Bereich der Cascade — Modelle werden bei Fehler/Quota/Cooldown der Reihe nach durchprobiert.',
  cooldownTitle: 'Cooldown-Status',
  statusFree: '🟢 frei',
  statusCooldown: '🟡 Cooldown',
  sectionTitle: 'Cascade-Bereiche',
  refreshTooltip: 'Cascade-Liste neu laden',
  filterPlaceholder: 'Bereich filtern…',
};

export const API_KEYS_SECTION_LABELS_DE: Partial<ApiKeysSectionLabels> = {
  title: 'API-Keys',
  subtitle: 'Ein Wert pro Setting-Key. Mehrere Modelle, die denselben Setting-Key referenzieren, teilen sich die Credential.',
  fieldSettingKey: 'Setting-Key',
  fieldValue: 'Key-Wert (leer = löschen)',
  btnShow: 'Zeigen',
  btnHide: 'Verbergen',
  btnSave: 'Speichern',
  btnSaving: 'Speichere…',
  listTitle: 'Konfigurierte Keys',
  colSettingKey: 'Setting-Key',
  colSource: 'Quelle',
  colValue: 'Wert',
  colActions: 'Aktionen',
  sourceDb: 'DB (Admin)',
  sourceEnv: 'ENV (Boot)',
  sourceMissing: 'fehlt',
  btnEdit: 'Bearbeiten',
  btnClear: 'Löschen',
  empty: 'Noch keine Keys konfiguriert. Nutze das Formular oben.',
  hint: 'Keys werden im Backend-Settings-Store gespeichert und niemals im Klartext an den Browser zurückgegeben. Der Wert wird nur server-seitig zur Provider-Authentifizierung genutzt.',
  confirmClear: (k: string) => `DB-Wert für "${k}" wirklich löschen? (ENV-Fallback kann weiter greifen.)`,
};

export const FAILOVER_CHAIN_LABELS_DE: Partial<FailoverChainLabels> = {
  title: 'Failover-Chain',
  description: 'Bei Fehler, Quota oder Cooldown probiert die Cascade die Modelle der Reihe nach durch.',
  addRow: 'Stufe hinzufügen',
  removeRowTitle: 'Entfernen',
  moveUpTitle: 'Nach oben',
  moveDownTitle: 'Nach unten',
  currentStep: 'Aktuelle Stufe:',
  positionLabel: (pos: number, provider: string, model: string) =>
    `Stufe ${pos + 1} (${provider} · ${model})`,
  promote: '↶ Zurück zu Stufe 1',
  hintToggle: 'Wie funktioniert das Failover?',
  hint: 'Failover läuft serverseitig in der llm-cascade: bei Fehler, Quota, Timeout oder wenn ein zugewiesener Server nicht erreichbar ist, wird pro Request transparent das nächste Modell der Kette genutzt; ausgefallene Modelle bekommen einen Cooldown. Kein Session-Neustart nötig. (Der klassische Wrapper-Failover mit Session-Neustart greift nur auf dem Direkt-Anbieter-Pfad, der die Cascade umgeht.)',
  emptyState: 'Keine Stufen konfiguriert. Füge eine hinzu um zu starten.',
};

export const PROVIDER_SERVERS_LABELS_DE: Partial<ProviderServersLabels> = {
  title: 'Inferenz-Server',
  subtitle: 'Benannte Server für lokale Modelle (Ollama). Ein Modell kann seine Inferenz auf einer externen Maschine laufen lassen; Standard ist localhost.',
  colName: 'Name',
  colBaseUrl: 'Basis-URL',
  colDefault: 'Standard',
  colActions: 'Aktionen',
  badgeDefault: 'STANDARD',
  btnAdd: 'Server hinzufügen',
  btnEdit: 'Bearbeiten',
  btnDelete: 'Löschen',
  btnSave: 'Speichern',
  btnCancel: 'Abbrechen',
  btnSetDefault: 'Als Standard',
  fieldName: 'Name (z.B. gpu-box) — [a-z0-9_-]',
  fieldBaseUrl: 'Basis-URL (inkl. /v1, z.B. http://gpu-box:11434/v1)',
  fieldDescription: 'Beschreibung (optional)',
  empty: 'Noch keine Server. Der Standard „localhost" wird vom Backend beim ersten Start angelegt.',
  loading: 'Lade Server…',
  hint: 'Nur relevant für lokale Provider (Ollama). Cloud-Provider (Gemini, OpenRouter…) haben feste Endpoints und ignorieren das. Der Standard-Server kann nicht gelöscht werden.',
  errorNameFormat: 'Name muss [a-z0-9_-]{1,50} entsprechen',
  errorBaseUrlRequired: 'Basis-URL ist Pflicht',
  errorDeleteDefault: 'Der Standard-Server kann nicht gelöscht werden. Setze zuerst einen anderen als Standard.',
  confirmDelete: (name: string) => `Server „${name}" wirklich löschen?`,
};
