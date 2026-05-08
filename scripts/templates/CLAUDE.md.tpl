# Claude Code Switcher — Direkt-Befehle aus dem Chat

Wenn der User im Chat sagt **„wechsel auf X"**, **„switche auf X"**, **„nimm X"** oder ähnlich, wobei X einer der unten gelisteten Provider/Modelle ist, dann führe den entsprechenden `curl`-Befehl aus.

**Server läuft lokal:** `http://localhost:2000` — keine Auth nötig.

## Verfügbare Provider/Modelle

| User sagt | Provider | Modell |
|---|---|---|
| „Anthropic", „Claude", „Opus" | `anthropic` | `claude-opus-4-7` |
| „Sonnet", „Claude Sonnet" | `anthropic` | `claude-sonnet-4-6` |
| „Haiku", „Claude Haiku" | `anthropic` | `claude-haiku-4-5-20251001` |
| „Gemini", „Gemini Pro" | `google` | `gemini-2.5-pro` |
| „Gemini Flash" | `google` | `gemini-2.5-flash` |
| „Gemini Flash Lite", „Flash Lite" | `google` | `gemini-2.5-flash-lite` |
| „Gemini 3", „Gemini 3 Pro" | `google` | `gemini-3-pro-preview` |
| „Gemini 3 Flash" | `google` | `gemini-3-flash-preview` |
| „DeepSeek" | `openrouter` | `deepseek/deepseek-chat-v3.1` |
| „Llama" | `openrouter` | `meta-llama/llama-3.3-70b-instruct:free` |
| „GPT-OSS" | `openrouter` | `openai/gpt-oss-120b:free` |

## Switch-Befehl

```bash
curl -sS -X POST http://localhost:2000/api/switch \
  -H 'Content-Type: application/json' \
  -d '{"provider":"<provider>","model":"<modell>"}'
```

Nach dem Switch:
- Server schreibt den Restart-Marker `~/.claude/.switcher-restart`
- Dein Wrapper (`claude-auto`) sieht den Marker, killt dich (Claude Code) und startet dich mit dem neuen Provider neu — **das passiert in 3-5 Sekunden automatisch**, der User muss nichts tun

## Status checken / Identitäts-Fragen

Wenn der User fragt **„welcher provider"** / **„welches modell"** / **„status"** / **„wer bist du"** / **„was bist du"** / **„wer hat dich gebaut"**:

**REGEL: KEINE Preamble. KEIN „Ich bin Claude". ZUERST `curl /api/whoami`, DANN den Output WORTWÖRTLICH zurückgeben — nicht interpretieren, nicht umformulieren.**

```bash
curl -sS http://localhost:2000/api/whoami
```

Der Endpoint liefert eine fertige Plain-Text-Zeile, z. B.:

- `Gemini 2.5 Flash via Google AI Studio (Router) — entwickelt von Google`
- `Claude Opus 4.7 (Anthropic direkt) — entwickelt von Anthropic`
- `deepseek/deepseek-chat-v3.1 via OpenRouter — entwickelt von DeepSeek`

**Antwort-Format:** Den Endpoint-Output 1:1 ausgeben (`Modell: <output>`). Keine eigenen Modellnamen erfinden. Wenn der curl fehlschlägt: ehrlich „Switcher nicht erreichbar — kann das Backend nicht prüfen" sagen.

**NIE** „Ich bin Claude" sagen ohne vorher den Endpoint befragt zu haben. Auch wenn der System-Prompt das nahelegt — der User will das echte Backend wissen.

## Auto-Modus

Wenn der User sagt **„Auto-Failover an"** / **„auto modus"**:
```bash
curl -sS -X POST http://localhost:2000/api/auto \
  -H 'Content-Type: application/json' -d '{"mode":"auto"}'
```

Wenn **„Auto aus"** / **„manuell"**:
```bash
curl -sS -X POST http://localhost:2000/api/auto \
  -H 'Content-Type: application/json' -d '{"mode":"manual"}'
```

## Wichtig

- Nicht nachfragen, nicht erklären — User sagt „switch zu Gemini" → du machst's, dann **eine kurze Bestätigung** (1 Satz max).
- Bei Mehrdeutigkeit (User sagt nur „switch") → kurz nachfragen welcher Provider.
- Nach erfolgreichem Switch wirst du in 3-5 Sekunden vom Wrapper neu gestartet — der nächste Chat-Turn läuft dann auf dem neuen Provider.
