# Claude Code Switcher -- Setup Skill

Installiert und konfiguriert den Claude Code Switcher vollständig.
Idempotent: kann jederzeit erneut aufgerufen werden (Update, Repair, Neustart).

Aufruf in Claude Code: `/setup-switcher`

## Was dieser Skill tut

1. Prüft Voraussetzungen (Docker, Claude Code)
2. Erkennt Architektur (amd64/arm64) und handelt entsprechend
3. Lädt Setup-Skript und extrahiert Source
4. Behebt amd64-Inkompatibilität (llm-cascade aus Source bauen, solange noch kein Multi-Arch-Image)
5. Startet alle Docker-Container
6. Installiert Supermodell-Agent nach `~/.claude/agents/`
7. Führt dich durch API-Key-Konfiguration
8. Aktiviert Supermodell-Modus auf Wunsch

---

## Ausführung

Führe die folgenden Schritte der Reihe nach aus. Nach jedem Schritt Status prüfen, bei Fehler stoppen und erklären.

### Schritt 1 -- Voraussetzungen prüfen

```bash
docker --version && \
docker compose version && \
claude --version && \
echo "Architektur: $(uname -m)"
```

Wenn Docker fehlt: Anleitung für die erkannte Distro ausgeben (Ubuntu: `sudo apt install docker.io docker-compose-plugin`).

### Schritt 2 -- Bereits installiert?

```bash
docker ps --format "{{.Names}}" | grep -q "claude-switcher" && echo "ALREADY_RUNNING" || echo "FRESH_INSTALL"
```

- Bei `ALREADY_RUNNING`: Status ausgeben, fragen ob Update/Neustart oder Abbruch.
- Bei `FRESH_INSTALL`: weiter mit Schritt 3.

### Schritt 3 -- Setup-Skript laden und Source extrahieren

```bash
cd ~ && \
curl -sO https://raw.githubusercontent.com/4dataclub/claude-code-switcher/main/setup.sh && \
chmod +x setup.sh && \
bash setup.sh
```

Das Skript ist self-extracting und idempotent für `~/.claude/CLAUDE.md`, `~/.claude/settings.json` und Hooks.

### Schritt 4 -- amd64-Workaround: llm-cascade aus Source bauen

Das offizielle Image `ghcr.io/4dataclub/llm-cascade:0.2.0` ist aktuell nur für `linux/arm64` veröffentlicht. Auf `x86_64`-Hosts bricht der Setup-Build beim Docker-Pull mit `no matching manifest for linux/amd64`.

Prüfen ob betroffen:

```bash
uname -m
```

Wenn `x86_64`:

```bash
cd ~/claude-switcher && \
git clone https://github.com/4dataclub/llm-cascade.git && \
sed -i 's|image: ghcr.io/4dataclub/llm-cascade:.*|build: ./llm-cascade|' docker-compose.yml
```

Bei `arm64` (Apple Silicon, ARM-Server): Schritt 4 überspringen, Originalimage funktioniert.

### Schritt 5 -- Docker-Container bauen und starten

```bash
cd ~/claude-switcher && docker compose up -d --build
```

Im Hintergrund starten und auf Abschluss warten. Erfolgskriterium: alle Container `Up`:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep "claude-switcher"
```

Erwartete Container:
- `claude-switcher-backend-1`
- `claude-switcher-frontend-1`
- `claude-switcher-router-1`
- `claude-switcher-llm-cascade-1`
- `claude-switcher-db-1`

Bei Fehler: `docker compose logs <service-name> 2>&1 | tail -30` ausgeben.

### Schritt 6 -- Supermodell-Agent installieren (optional)

```bash
mkdir -p ~/.claude/agents && \
curl -sL https://raw.githubusercontent.com/4dataclub/claude-code-switcher/main/agents/supermodel.md -o ~/.claude/agents/supermodel.md
```

### Schritt 7 -- Health-Check

```bash
sleep 30  # Spring-Boot braucht ~60s zum Hochfahren
curl -s http://localhost:2000/api/status | python3 -m json.tool || echo "Backend noch nicht bereit"
```

### Schritt 8 -- API-Keys und Konfiguration

Dem User mitteilen:

**Öffne im Browser: http://localhost:2000**

API-Keys eintragen (Abschnitt "API Keys" in der UI):

| Key | Wo holen |
|---|---|
| Google AI Studio | https://aistudio.google.com/apikey |
| OpenRouter | https://openrouter.ai/keys |
| Anthropic | Leer lassen (OAuth via Claude Desktop) |

Wenn der User nur Claude-Modelle nutzen will (kein externer Failover):
- Failover-Chain in der UI auf nur Anthropic-Stufe reduzieren
- Oder Auto-Failover deaktivieren (Modus: Manuell)

### Schritt 9 -- Supermodell-Modus aktivieren (optional)

```bash
curl -s -X POST http://localhost:2000/api/mode \
  -H "Content-Type: application/json" \
  -d '{"pool":"cloud","supermodel":true}'
```

Pool-Optionen:
- **`cloud`**: Opus plant, günstigere Cloud-Modelle führen aus
- **`free`**: Opus plant, nur kostenlose Modelle (DeepSeek free, Llama)
- **`local`**: alles lokal, kein Cloud-Leak (braucht Ollama, fail-closed)

Details siehe `SUPERMODELL.md` im Repo-Root.

### Abschluss

Status-Zusammenfassung ausgeben:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep "claude-switcher"
curl -s http://localhost:2000/api/status | python3 -m json.tool || echo "UI noch startend"
```

Dem User mitteilen:
- UI erreichbar unter **http://localhost:2000**
- `claude`-Befehl im Terminal neu laden: `source ~/.bashrc` (Linux) oder `source ~/.zshrc` (macOS)
- Supermodell-Agent liegt unter `~/.claude/agents/supermodel.md`
