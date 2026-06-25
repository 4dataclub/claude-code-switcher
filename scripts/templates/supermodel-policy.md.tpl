# Supermodell-Modus — Delegation an den `@supermodel`-Agent

**Aktiv nur, wenn der Supermodell-Modus im Switcher AN ist** — prüfbar via
`curl -s localhost:2000/api/supermodel` → `{"enabled":true,"pool":"cloud|free|local"}`.
Bei `{"enabled":false}` ist dieser Block **inaktiv** (normales Claude-Code-Verhalten).

Wenn aktiv, bleibt das Orchestrator-Modell (Opus bzw. der aktive Dirigent) **Planer und finale
Synthese** und delegiert selbst-enthaltene Teilaufgaben an den **einen** Subagenten `@supermodel`
(kein Agent-Zoo — einer, der intern zum passenden Pool-Modell der llm-cascade routet):

| Aufgabe | Wohin |
|---|---|
| Planung / Architektur / komplexe Tradeoffs | **selbst behalten** (nicht delegieren) |
| Bulk-Implementierung / Backend / Boilerplate | `@supermodel` · kind=implement |
| Code-Review / Tests | `@supermodel` · kind=review |
| Web-/Doku-Recherche | `@supermodel` · kind=research (nutzt Gemini-MCP) |
| Triviales (Commit-Messages, Summaries, simple Edits) | `@supermodel` · kind=dispatch |

**Regeln:**
- Aufgabe **selbst-enthalten** übergeben (Pfade/Signaturen/Kontext inline) — der Agent läuft
  isoliert, dadurch bleibt der Orchestrator-Kontext schlank (= der Spar-Effekt).
- **Finale Synthese ist Pflicht:** nach der Delegation übernimmt der Orchestrator IMMER wieder,
  prüft die Ergebnisse gegen den Plan und integriert. Nie delegieren-und-vergessen.
- **Lokaler Pool = fail-closed:** im Lokal-Modus geht nichts automatisch in die Cloud. Meldet der
  Agent „Delegation nicht möglich", entscheidet der Orchestrator (kein stilles Rausgehen).

Der Agent routet `kind` × aktiven Pool zur lokalen llm-cascade (`localhost:8091`,
Kategorie `{kind}-{pool}`); `research` geht über die Gemini-MCP. Voraussetzung: die Cascade läuft
und gültige Keys sind im Switcher-UI (`localhost:2000`) eingetragen.
