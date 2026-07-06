# SWITCHER-WAHRHEIT — kanonische Beschreibung

> **Einzige gültige Beschreibung des Switcher-Verhaltens (Stand 2026-07-06).**
> Ersetzt ALLE früheren Aussagen in README/SUPERMODELL/Notizen. **Bei Konflikt gewinnt der Code.**
> Jede Aussage ist mit Code-Anker versehen; ⟨?⟩ markiert, was nur ein Live-Test beweist.

## Zweck
Failover (nie von Anthropic-Limit ausgebremst) · Supermodell (Delegation an günstige/lokale Modelle) · Local/Privatsphäre.

## Verhaltens-Matrix (Supermodell × Verbindung)

| Modus·Verbindung | Wer entscheidet | Ziel-Bereich | Verbindung | Loop geloggt | Arbeit geloggt | Daten |
|---|---|---|---|:--:|:--:|---|
| AN·OAuth | Opus (Kopf) | AN-Rollen | direkt | ❌ | ✅ | außen |
| AN·Anthropic-Key | Opus | AN-Rollen | Router | ✅ | ✅ | außen |
| AN·Gemini-Key | Gemini | AN-Rollen | Router | ✅ | ✅ | außen |
| AN·local | ollama (Opus weg) | AN-Rollen·local | Router | ✅ | ✅ | im Haus 🔒 |
| AUS·OAuth | Opus = Semantiker | PURPOSE | direkt | ❌ | ✅ | außen |
| AUS·Key | Cascade ③ | PURPOSE | Router | ✅ | ✅ | außen |
| AUS·local | Cascade ③ | PURPOSE·local | Router | ✅ | ✅ | im Haus 🔒 |

- **AN-Rollen** = orchestrator/implement/review/research/dispatch · **PURPOSE** = content/dev/general/utility · **kein Cross-over**.
- "Loop geloggt" = Planungs-/Klassifikations-Traffic sichtbar via ccr (`[ROUTER]`). "Arbeit geloggt" = Modell-Arbeit via `[DELEG]`.

## Mechanik (code-verankert)
- **Verbindung:** OAuth = einzige Direkt-Ausnahme; jeder Key + local = Router. `RouterService.writeRouterConfig` (cascadeModel = supermodelOn?`orchestrator-{pool}`:`{pool}`), `ApiController.pinTopForPool`. `--bare` nur im Router-Modus (`claude-auto`).
- **Delegation:** agentenloser curl → `POST localhost:8091/api/generate {"category":"{kind|purpose}-{pool}",...}`, Erfolg = `.text`. **Kein `@supermodel`-Agent** (entfernt).
- **Policy-Zustellung:** Wrapper `claude-auto` hängt die Policy via `--append-system-prompt-file` an (überlebt `--bare`; ersetzt den alten SessionStart-Hook, der unter `--bare` tot war — `claude --help`: "skip hooks").
- **Logging:** `[DELEG]` = `switcher-watch` pollt `:8091/api/stats/calls`; `[ROUTER]` = ccr-Container-Log.
- **local fail-closed:** `*-local`-Kategorien enthalten nur ollama (`DataInitializer`); `buildProvidersForPool(local)` gibt nur ollama-Keys. Failover nur ollama→ollama. Kein Cloud-Ausweich.
- **Ports:** `:3456` ccr · `:2000` UI/API · `:8091` llm-cascade.

## Fehler-Verhalten bei Delegation
cloud/free = **fail-open** (Kopf macht die Teilaufgabe selbst); local = **fail-closed** (STOPP, nie Cloud).

## Status (Stand 2026-07-06)
- ✅ **Umgesetzt:** agentenlose curl-Delegation + Policy-Injektion im Wrapper (AN + AUS-direkt); `@supermodel`-Agent restlos entfernt; PURPOSE-local-Beschreibungen befüllt.
- ✅ **Umgesetzt (Teil C), unverifiziert ohne Key:** `doSwitch(anthropic)` routet über Router/Cascade, wenn ein echter `sk-ant-api03`-Key in DB `app_settings.anthropicApiKey` liegt (dort liest die Cascade); ohne solchen Key (OAuth) bleibt es direkt. Backend gebaut+deployed; der echte Key-Flow ist mangels Cloud-Key noch nicht getestet.
- ⟨?⟩ **Nur per Live-Test:** greift die Cascade-③-Beschränkung auf PURPOSE (nicht AN-Rollen)? cloud/free-Beschreibungen befüllt? Key→Cascade erreichbar?
