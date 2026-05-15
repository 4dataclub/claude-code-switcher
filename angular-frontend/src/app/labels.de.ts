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
  CascadeCooldownLabels,
  ApiKeysSectionLabels,
} from '@4dataclub/ki-models-ui';

export const MODELS_TABLE_LABELS_DE: Partial<ModelsTableLabels> = {
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
  keySet: 'Key gesetzt',
  keyMissing: 'Key fehlt',
  on: 'AN',
  off: 'AUS',
  autoDisabled: 'Auto-deaktiviert',
  free: 'Frei',
  toggleNeedsKey: 'Key zuerst setzen',
  btnTest: 'Test',
  btnReenable: 'Reaktivieren',
  btnDelete: 'Löschen',
  confirmDelete: (id: string) => `Modell "${id}" wirklich löschen?`,
};

export const ADD_MODEL_FORM_LABELS_DE: Partial<AddModelFormLabels> = {
  title: 'Neues Modell hinzufügen',
  fieldModelId: 'Modell-ID (z.B. gemini-2.5-flash)',
  fieldApiKeySettingKey: 'API-Key-Setting',
  fieldDisplayName: 'Anzeigename (optional)',
  fieldCooldownOverride: 'Cooldown 503 (Sek., optional)',
  btnAdd: 'Hinzufügen',
  btnAdding: 'Speichere…',
  hint: 'Der API-Key liegt im Settings-Store — siehe unten „API-Keys". Mehrere Modelle können denselben Key teilen.',
  errorRequired: 'Provider, Modell-ID und Setting-Key sind Pflicht',
  errorFailed: 'Speichern fehlgeschlagen',
};

export const CASCADE_COOLDOWN_LABELS_DE: Partial<CascadeCooldownLabels> = {
  title: 'Cascade-Cooldown',
  subtitle: 'Globales Override für das Cooldown-Verhalten pro Modell.',
  default: 'Standard',
  forceOn: 'Erzwingen',
  forceOff: 'Deaktivieren',
  effectiveOn: 'Effektiv: AN',
  effectiveOff: 'Effektiv: AUS',
  hint: 'Standard = jedes Modell entscheidet selbst. Erzwingen = Cooldown bleibt auch wenn ein Modell ihn überspringen würde. Deaktivieren = Cooldowns global aus (nützlich für Tests).',
  loading: 'Lade Cascade-Config…',
  errorLoad: 'Cascade-Config konnte nicht geladen werden.',
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
