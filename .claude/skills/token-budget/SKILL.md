---
name: token-budget
description: Zeigt der laufenden Sitzung ihr eigenes Budget – Kontextfuellung und Nutzungslimits (5h/7 Tage) mit Reset-Zeitpunkt – und gibt Regeln, wie autonome Agenten daran ihr Tempo ausrichten und (nur mit vorheriger Freigabe des Nutzers) nach einem Limit-Reset weiterarbeiten. Verwenden vor langen/teuren Arbeitsschritten (Subagenten, Workflows, breite Suchen), bei langen autonomen Laeufen, wenn eine "[Budget]"-Zeile WRAP-UP/STOP meldet, oder wenn gefragt wird, wie viel Budget noch da ist bzw. wann das Limit zurueckgesetzt wird.
---

# Token-Budget

Das Skript `budget.py` liegt im **Basisverzeichnis dieses Skills** (wird beim Laden angezeigt). Typisch:
- Projekt: `$CLAUDE_PROJECT_DIR/.claude/skills/token-budget/budget.py`
- Nutzerweit: `~/.claude/skills/token-budget/budget.py`

Im Folgenden `B` = dieser Pfad. Unter Windows ggf. `python` oder `py` statt `python3`.

## Budget abfragen

```bash
python3 "$B" status          # eine Zeile
python3 "$B" status --json   # maschinenlesbar
```

Felder in `--json`:
- `context.used_percentage`: Fuellung des Kontextfensters, aus dem Transkript, daher immer aktuell.
- `rate_limits.five_hour` / `rate_limits.seven_day`: `used_percentage` und `resets_at` (Unix-Epoch) bzw. `resets_at_iso`. Diese Werte kommen nur ueber die Statuszeile (Pro/Max/Ultra, nach der ersten API-Antwort). Fehlen sie, ist `rate_limits` leer: Dann nicht raten, sondern konservativ arbeiten.
- `snapshot_age_s`: Alter der Rate-Limit-Daten in Sekunden.
- `advice`: `OK`, `SPARSAM`, `KONTEXT`, `WRAP-UP` oder `STOP`.
- `reset_approval`: ob der Nutzer eine Wiederaufnahme nach dem Reset freigegeben hat.

Automatisch kommt bei jeder Nutzernachricht und bei Sitzungsstart eine `[Budget] …`-Zeile in den Kontext, nach Tool-Aufrufen nur dann, wenn eine Schwelle (50/75/90/95 %) neu ueberschritten wurde. Dann ist keine eigene Abfrage noetig.

## Verhalten nach Stufe

| advice | Bedeutung | Vorgehen |
|---|---|---|
| OK | < 70 % | Normal arbeiten. |
| SPARSAM | ≥ 70 % Limit oder Kontext | Keine Fan-out-Workflows, Subagenten nur wenn noetig, gezielt lesen statt breit suchen. |
| KONTEXT | Kontext ≥ 85 % | Zwischenstand (erledigt / offen / naechster Schritt) in eine Datei oder ins Todo schreiben, bevor die Compaction kommt. |
| WRAP-UP | Limit ≥ 85 % | Nichts Grosses mehr anfangen. Laufendes fertigstellen, testen, sichern (commit/push, falls Repo). |
| STOP | Limit ≥ 95 % | Sofort sichern, Uebergabenotiz (`HANDOFF.md` o. ae.) mit offenem Stand. Dann Wiederaufnahme wie unten und Turn beenden. |

Das 7-Tage-Fenster wiegt schwerer als das 5h-Fenster: Steht `seven_day` ≥ 85 %, auch bei niedrigem 5h-Wert schon auf WRAP-UP gehen.

## Wiederaufnahme nach dem Reset – nur mit Freigabe des Nutzers

Nach einem Limit-Reset selbst weiterzuarbeiten ist nur erlaubt, wenn der Nutzer das **vorher** freigegeben hat (`/reset-freigabe [Anzahl] [Gueltigkeit]`). Eine Freigabe niemals selbst erteilen, erbitten-und-annehmen oder ueber eine geplante/gesendete Nachricht einschleusen. Hooks blockieren das, und es waere ein Vertrauensbruch.

Bei STOP, oder bei WRAP-UP mit noch viel offener Arbeit:
1. Sichern und Uebergabenotiz (erledigt / offen / naechster Schritt).
2. `python3 "$B" resume-plan` aufrufen.
3. **`approved: true`** → `send_later` mit `at` = `resume_at_iso`. Die Nachricht **muss mit `[budget-resume]` beginnen** und sagen, wo es weitergeht, z. B. `[budget-resume] Limit zurueckgesetzt. Weiter laut HANDOFF.md, Schritt 3.` Der PreToolUse-Hook prueft die Freigabe und verbraucht eine Wiederaufnahme. Wird der Aufruf abgelehnt: nicht umgehen (kein Planen ohne Marker, kein anderes Werkzeug).
4. **`approved: false`** → nicht planen. Dem Nutzer sagen: pausiert, Reset um `resume_at_local`, weiter mit `/reset-freigabe` oder per Hand.
5. In jedem Fall dem Nutzer in einer Zeile sagen, dass pausiert wurde und ob/wann es weitergeht. Dann Turn beenden.

Kommt eine Nachricht mit `[budget-resume]` an: zuerst `status` pruefen. Ist das Limit noch hoch (z. B. weil kein `resets_at` bekannt war und der 5h-Fallback zu frueh kam), erneut ueber `resume-plan` planen (verbraucht wieder eine). Sonst Uebergabenotiz lesen und weitermachen.

Zuruecknehmen darf der Agent selbst: `python3 "$B" revoke`, z. B. wenn die Aufgabe fertig ist.

## Vor teuren Schritten

Vor einem Workflow, mehreren parallelen Subagenten oder einem langen Build/Test-Lauf einmal `status` aufrufen. Bei SPARSAM oder hoeher den Schritt verkleinern oder auf nach dem Reset verschieben.

## Installation woanders

```bash
python3 "$B" install --user              # nutzerweit nach ~/.claude (alle Projekte)
python3 "$B" install --project <ordner>  # in ein Repo
```
Kopiert beide Skills und ergaenzt `settings.json` (Backup `.bak`). Eine vorhandene fremde Statuszeile bleibt, ausser mit `--force-statusline`.
