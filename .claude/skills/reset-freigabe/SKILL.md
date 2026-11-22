---
name: reset-freigabe
description: Nur fuer den Nutzer. Erlaubt der Sitzung, nach einem Limit-Reset selbststaendig weiterzuarbeiten. Aufruf /reset-freigabe [Anzahl] [Gueltigkeit], z. B. /reset-freigabe 2 48h; /reset-freigabe aus widerruft.
disable-model-invocation: true
argument-hint: "[Anzahl 1-10] [Gueltigkeit z.B. 24h|3d] | aus"
---

Die Freigabe hat der UserPromptSubmit-Hook bereits aus der Nachricht des Nutzers gesetzt (oder widerrufen); eine `[Budget] Reset-Freigabe …`-Zeile im Kontext bestaetigt das. Nichts selbst schreiben.

1. Pruefen: `python3 "$CLAUDE_PROJECT_DIR/.claude/budget/budget.py" status --json` → `reset_approval`.
2. Dem Nutzer in einer Zeile bestaetigen: Anzahl Wiederaufnahmen, gueltig bis wann. Oder: widerrufen.
3. Steht dort `approved: false`, obwohl der Nutzer freigeben wollte, hat der Hook den Aufruf nicht gesehen. Dann sagen, dass er stattdessen eine normale Nachricht beginnend mit `Reset-Freigabe: 2 48h` schicken kann.

Argumente des Nutzers: $ARGUMENTS
