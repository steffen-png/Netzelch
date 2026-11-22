---
name: token-budget
description: Zeigt der laufenden Sitzung ihr eigenes Budget – Kontextfuellung und Nutzungslimits (5h/7 Tage) mit Reset-Zeitpunkt – und gibt Regeln, wie autonome Agenten daran ihr Tempo ausrichten. Verwenden vor langen/teuren Arbeitsschritten (Subagenten, Workflows, breite Suchen), bei langen autonomen Laeufen, wenn eine "[Budget]"-Zeile WRAP-UP/STOP meldet, oder wenn gefragt wird, wie viel Budget noch da ist bzw. wann das Limit zurueckgesetzt wird.
---

# Token-Budget

## Budget abfragen

```bash
python3 "$CLAUDE_PROJECT_DIR/.claude/budget/budget.py" status          # eine Zeile
python3 "$CLAUDE_PROJECT_DIR/.claude/budget/budget.py" status --json   # maschinenlesbar
```

Felder in `--json`:
- `context.used_percentage`: Fuellung des Kontextfensters, berechnet aus dem Transkript, daher immer aktuell.
- `rate_limits.five_hour` / `rate_limits.seven_day`: `used_percentage` und `resets_at` (Unix-Epoch) bzw. `resets_at_iso`. Diese Werte kommen nur ueber die Statuszeile (Pro/Max/Ultra, nach der ersten API-Antwort). Fehlen sie, ist `rate_limits` leer: Dann nicht raten, sondern konservativ arbeiten.
- `snapshot_age_s`: Alter der Rate-Limit-Daten in Sekunden.
- `advice`: `OK`, `SPARSAM`, `KONTEXT`, `WRAP-UP` oder `STOP`.

Automatisch kommt bei jeder Nutzernachricht und bei Sitzungsstart eine `[Budget] …`-Zeile in den Kontext, nach Tool-Aufrufen nur dann, wenn eine Schwelle (50/75/90/95 %) neu ueberschritten wurde. Dann ist keine eigene Abfrage noetig.

## Verhalten nach Stufe

| advice | Bedeutung | Vorgehen |
|---|---|---|
| OK | < 70 % | Normal arbeiten. |
| SPARSAM | ≥ 70 % Limit oder Kontext | Keine Fan-out-Workflows, Subagenten nur wenn noetig, gezielt lesen statt breit suchen. |
| KONTEXT | Kontext ≥ 85 % | Zwischenstand (erledigt / offen / naechster Schritt) in eine Datei oder ins Todo schreiben, bevor die Compaction kommt. |
| WRAP-UP | Limit ≥ 85 % | Nichts Grosses mehr anfangen. Laufendes fertigstellen, testen, committen und pushen. |
| STOP | Limit ≥ 95 % | Sofort sichern: commit/push, Uebergabenotiz (`HANDOFF.md` oder Commit-Nachricht) mit offenem Stand. Dann die Wiederaufnahme planen (siehe unten) und den Turn beenden. |

## Wiederaufnahme nach dem Reset (autonome Laeufe)

Bei STOP, oder bei WRAP-UP mit noch viel offener Arbeit:
1. `resets_at` des betroffenen Fensters lesen (`status --json`).
2. Eine Erinnerung kurz nach dem Reset einplanen, z. B. mit `send_later` (`at` = `resets_at_iso` plus 2 Minuten). Die Nachricht soll sagen, wo die Arbeit weitergeht (Verweis auf die Uebergabenotiz).
3. Dem Nutzer in einer Zeile sagen, dass pausiert wurde und wann es weitergeht.

Das 7-Tage-Fenster wiegt schwerer als das 5h-Fenster: Steht `seven_day` ≥ 85 %, auch bei niedrigem 5h-Wert schon auf WRAP-UP gehen.

## Vor teuren Schritten

Vor einem Workflow, mehreren parallelen Subagenten oder einem langen Build-und-Test-Lauf einmal `status` aufrufen. Bei SPARSAM oder hoeher den Schritt verkleinern oder auf nach dem Reset verschieben.
