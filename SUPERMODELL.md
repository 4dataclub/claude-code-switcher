# Supermodell-Modus — Claude Code mit günstigen Kollegen-Modellen

> **Kanonische technische Wahrheit + vollständige Matrix: [docs/SWITCHER-WAHRHEIT.md](docs/SWITCHER-WAHRHEIT.md)** (mit Code-Ankern). Diese Datei erklärt das Konzept; bei Konflikt gewinnt SWITCHER-WAHRHEIT.md + der Code.

## Worum geht's?

Claude Code läuft sonst für **jede** Aufgabe auf dem teuersten Modell (Opus). Der **Supermodell-Modus** lässt einen **Kopf** planen und die **Fleißarbeit an günstige/lokale Modelle delegieren** — viele Modelle, die sich wie **ein** überlegenes verhalten.

Es gibt **zwei Modi** (Toggle im UI `http://localhost:2000` oder per Chat „supermodell an/aus"):

| | Was passiert |
|---|---|
| **AN** | Der **Orchestrator** (Kopf) plant und **delegiert strikt** jede Rollen-Aufgabe (`implement/review/research/dispatch`) an günstige Modelle; er behält nur Planung/Synthese/Ausführung-mit-Tools. |
| **AUS** | **Kein Kopf.** Die **semantische Auto-Auflösung** schickt jede Anfrage ans passende Modell (PURPOSE-Kategorien `content/dev/general/utility`). |

## Wie delegiert wird (agentenlos)

Es gibt **keinen `@supermodel`-Agenten** (entfernt). Delegation läuft **agentenlos**:
- Der Wrapper `claude-auto` injiziert bei AN die Delegations-Policy per `--append-system-prompt-file` (überlebt `--bare`).
- Der Orchestrator delegiert per **direktem curl** an die llm-cascade: `POST localhost:8091/api/generate` mit `category={kind}-{pool}`. Ergebnis (`.text`) sammelt er ein, prüft und integriert.
- **Nur Generierung** wird delegiert (Worker liefert Text/Code); **Ausführung** (Tools/Bash/Dateien/Dienste) macht der Orchestrator selbst.
- **Fehler:** cloud/free = fail-open (Kopf macht's selbst), local = fail-closed (Stopp, nie Cloud).

## Verbindung (wichtig)

- **OAuth (Abo)** = die **einzige Direkt-Ausnahme** — der Orchestrator umgeht die Cascade (Opus nativ). Sein Loop wird nicht geloggt, die delegierte Arbeit schon.
- **Jeder API-Key** (Anthropic/Gemini/…) und **local** = über den **Router → Cascade** (Loop geloggt, tool-fähig via Route A).

## Pools — cloud / free / local

Der User wählt **einen** Pool (fix, nie automatisch gewechselt):
- **cloud** — bezahlte Modelle, Daten gehen nach außen.
- **free** — Gratis-Modelle, nach außen, fail-open.
- **local** — nur Ollama, **fail-closed**: nichts verlässt das interne Netz; kein Cloud-Ausweich (`*-local`-Kategorien enthalten strukturell nur Ollama). Failover nur ollama→ollama.

## Wann lohnt AN?

Je stärker der Kopf gegenüber den Workern ist und je mehr stumpfe Fleißarbeit anfällt. Auf **schwacher local-Hardware** (Kopf = Worker) ist Orchestrierung Overhead; auf **Config B+** (72b-Kopf, 32b-Spezialisten) lohnt sie sich. Auf **OAuth (flat, Opus = bester)** delegiert der Kopf nur das, was ein Worker zuverlässig + billig prüfbar liefert.

---
*Modell-/Rollen-Zuordnung ist Daten (DB-Kategorien, im UI editierbar) — kein Code-Eingriff nötig. Vollständige Verhaltens-Matrix: [docs/SWITCHER-WAHRHEIT.md](docs/SWITCHER-WAHRHEIT.md).*
