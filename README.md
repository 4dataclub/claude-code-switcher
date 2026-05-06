# Claude Code Switcher

Auto-Failover für [Claude Code](https://claude.com/claude-code): wenn dein Anthropic-Pro/Max-Quota leerläuft, wechselt das Tool automatisch auf Gemini (oder OpenRouter) — und springt nach Cooldown selbst wieder zurück. **Du tippst weiterhin nur `claude`**, der Chat-Verlauf bleibt erhalten.

> **Volldoku** im Brain: `02 Projekte/Claude Code Switcher.md`. Diese README ist der Setup-Guide.

## Was es macht

- Du arbeitest mit `claude` wie immer.
- **90 % Quota** → Notification + UI-Banner. Du entscheidest manuell.
- **100 % Quota** → automatischer Switch auf nächste Failover-Stufe (Default: Gemini 2.5 Pro). Claude wird mit `--resume` neu gestartet, Kontext bleibt voll erhalten.
- **Alle 30 min** wird probiert ob Anthropic wieder verfügbar ist → sobald frei, automatisch zurück (kostenlos).

Default-Chain: `Anthropic Pro/Max → Gemini 2.5 Pro → Gemini 2.5 Flash → DeepSeek free`. Im UI editierbar.

## Voraussetzungen

| | |
|---|---|
| Docker Desktop | macOS, Windows oder Linux |
| Anthropic Pro/Max | (für Stufe 0) |
| Google AI Studio API-Key | mit Billing aktiv für Paid Tier — siehe https://aistudio.google.com/apikey |
| OpenRouter Account + API-Key | (auch für die `:free`-Modelle nötig) — https://openrouter.ai/keys |
| Claude Code installiert | `claude` muss im PATH sein |

---

## Setup macOS / Linux

```bash
# 1. Repo klonen / in dieses Verzeichnis wechseln
cd ~/Downloads/claude_free_model/claude-switcher

# 2. Container bauen + starten
docker compose up -d --build

# 3. UI öffnen, Keys eintragen, Modus auf "Auto" stellen
open http://localhost:3000

# 4. Wrapper installieren (setzt Alias `claude` → `claude-auto`)
cd wrapper && ./install.sh

# 5. Terminal neu öffnen (damit der Alias greift)
```

Test:
```bash
curl -s http://localhost:3000/api/status | jq
claude   # ruft jetzt den Wrapper auf
```

---

## Setup Windows (PowerShell)

```powershell
# 1. Repo klonen / in dieses Verzeichnis wechseln
cd $env:USERPROFILE\Downloads\claude_free_model\claude-switcher

# 2. Container bauen + starten (Docker Desktop muss laufen)
docker compose up -d --build

# 3. UI öffnen, Keys eintragen, Modus auf "Auto" stellen
Start-Process "http://localhost:3000"

# 4. Wrapper installieren (setzt Funktion `claude` → claude-auto.ps1 im PowerShell-Profil)
cd wrapper
.\install.ps1

# 5. Optional für hübsche Notifications:
Install-Module -Name BurntToast -Scope CurrentUser -Force

# 6. PowerShell neu öffnen (damit die Funktion greift)
```

Test:
```powershell
Invoke-RestMethod http://localhost:3000/api/status
claude   # ruft jetzt claude-auto.ps1
```

---

## UI bedienen (http://localhost:3000)

1. **API Keys** aufklappen → alle drei Felder ausfüllen (Anthropic-Feld kann leer bleiben — OAuth via Claude Desktop wird genutzt) → **Anwenden**.
2. **Modus** auf **Auto-Failover** stellen.
3. **Failover-Chain** prüfen — Default passt für die meisten. Reihenfolge: Stufe 1 wird zuerst genutzt wenn Anthropic leer.
4. Für den manuellen Switch: Provider-Card anklicken → Modell wählen → **Anwenden**.

## Was du im Alltag tatsächlich siehst

| Situation | Was passiert |
|---|---|
| Normal arbeiten | Nichts Besonderes — wie immer mit `claude` |
| Quota fast leer (90 %) | Notification + UI-Banner („Jetzt switchen" / „Weitermachen") |
| Quota leer (100 %) | Claude hängt ~5 s, kommt wieder, du tippst weiter |
| Cooldown abgelaufen | Wieder ~5 s Restart, danach kostenlos auf Anthropic |

## Wo liegt was?

| Zweck | Pfad |
|---|---|
| Switcher-Settings | `~/.claude/settings.json` (`_switcher`-Block — vom UI gepflegt, nicht manuell editieren) |
| Router-Config | `~/.claude/router-config.json` (vom UI gepflegt) |
| Restart-Marker | `~/.claude/.switcher-restart` (Wrapper polling) |
| Session-History | `~/.claude/projects/<encoded-cwd>/<uuid>.jsonl` |
| Wrapper macOS/Linux | `~/.local/bin/claude-auto` |
| Wrapper Windows | `%LOCALAPPDATA%\claude-switcher\claude-auto.ps1` |

## Häufige Probleme

**„Provider zeigt OpenRouter/DeepSeek statt Anthropic"** — manuell im UI auf Anthropic klicken + Anwenden, oder `curl -X POST http://localhost:3000/api/chain-promote`.

**Router-Container restartet ständig** — `docker compose down && docker compose up -d --build`. Sollte mit dem aktuellen `command:` im docker-compose.yml stabil sein (Daemon-Watching via `pgrep`).

**„Quota erreicht" ohne dass etwas switcht** — Auto-Modus muss im UI aktiv sein. Status checken: `curl http://localhost:3000/api/status`.

**Windows-Notifications kommen nicht** — `Install-Module BurntToast -Scope CurrentUser` ausführen, oder im Konsolen-Output nach `▸ Switcher:`-Meldungen Ausschau halten.

**Keys sollen nicht in Git landen** — Niemals `~/.claude/settings.json` committen, niemals Keys in Notizen/Repos kopieren. Bei versehentlichem Push: Key sofort revoken, dann `git filter-repo` für die History.

## Stoppen / Aufräumen

```bash
# macOS / Linux
cd ~/Downloads/claude_free_model/claude-switcher
docker compose down

# Windows (PowerShell)
cd $env:USERPROFILE\Downloads\claude_free_model\claude-switcher
docker compose down
```

Wrapper-Alias entfernen: die Zeilen zwischen `# === claude-switcher ===` und `# === /claude-switcher ===` aus `~/.zshrc` (macOS) bzw. `$PROFILE.CurrentUserAllHosts` (Windows) löschen.
