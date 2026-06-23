# Superpowers -- Setup Skill

Installiert das **superpowers**-Plugin für Claude Code. Optionale Erweiterung zum
Switcher: strukturierte Arbeitsmodi (Brainstorming, Test-Driven Development,
systematisches Debugging, Plan-Ausführung), die besonders im **Supermodell-Modus**
den Unterschied machen -- Opus plant sauber, günstigere Modelle führen diszipliniert aus.

Aufruf in Claude Code: `/setup-superpowers`

## Warum das zum Switcher passt

Der Switcher senkt die *Kosten* pro Token (Failover + Supermodell-Delegation).
Superpowers erhöht die *Qualität* pro Token: Skills erzwingen Workflow-Disziplin
(erst Design, dann Code; erst Test, dann Implementierung), statt dass ein günstigeres
Ausführungsmodell drauflosrät. Zusammen: billig **und** verlässlich.

Read-only Nutzung: Du brauchst keinen Schreibzugriff auf die Repos. Plugin wird aus
dem offiziellen Anthropic-Marketplace installiert, nicht aus diesem Repo.

## Ausführung

Die Plugin-Installation läuft über interaktive Slash-Befehle in Claude Code
(nicht über die Shell). Führe die folgenden Befehle der Reihe nach aus.

### Schritt 1 -- Offiziellen Marketplace registrieren

```
/plugin marketplace add anthropics/claude-plugins-official
```

Falls bereits registriert: Meldung ignorieren, weiter mit Schritt 2.

### Schritt 2 -- superpowers installieren

```
/plugin install superpowers@claude-plugins-official
```

### Schritt 3 -- Verifizieren

Claude Code neu starten (oder `/exit` und neu öffnen), dann prüfen:

```
/help
```

In der Skill-Liste sollten u. a. erscheinen:
- `superpowers:brainstorming`
- `superpowers:test-driven-development`
- `superpowers:systematic-debugging`
- `superpowers:writing-plans`
- `superpowers:using-superpowers` (lädt automatisch zu Sitzungsbeginn)

### Abschluss

Dem User mitteilen:
- Skills sind aktiv -- bei kreativer Arbeit greift `brainstorming` automatisch,
  bei Bugs `systematic-debugging`, vor Implementierung `test-driven-development`.
- Im **Supermodell-Modus** (siehe `/setup-switcher` + `SUPERMODELL.md`) sorgen die
  Skills dafür, dass das delegierte Ausführungsmodell dem Plan folgt statt zu raten.
- Skills lassen sich jederzeit per `/plugin` verwalten (deaktivieren/aktualisieren).
