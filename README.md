# Hallo Welt – Android-App

Eine minimale Android-App: Sie zeigt „Hallo Welt!“ und darunter das Gerätemodell.

## Auf dem Handy installieren

1. Auf GitHub unter **Releases** den Eintrag „Hallo Welt (neuester Build)“ öffnen.
2. `HalloWelt.apk` antippen und herunterladen.
3. Die Datei öffnen. Android fragt einmal, ob der Browser Apps installieren darf: erlauben.
4. Installieren und die App „Hallo Welt“ starten.

Die APK baut GitHub Actions automatisch bei jedem Push (`.github/workflows/android.yml`).

## Selbst bauen

Mit installiertem Android-SDK und Gradle 8.9+: `gradle assembleDebug`

## Automatische Updates

Beim Start prüft die App `version.txt` im Release `hallo-welt`. Ist die Nummer höher als die
installierte Version, bietet sie „Jetzt aktualisieren“ an, lädt die neue APK und öffnet den
Android-Installationsdialog. Die Versionsnummer ist die Nummer des GitHub-Actions-Laufs.

Alle Builds werden mit `app/netzelch.keystore` signiert, damit Android Updates akzeptiert.
Der Schlüssel liegt öffentlich im Repo. Für eine App, die du weitergibst, gehört er in ein
GitHub-Secret.

# Reanimation – zweite App (Modul `reanimation`)

Zeitgeber für eine Reanimation mit großen Kacheln:

| Kachel | Intervall |
|---|---|
| Gesamtzeit | – (startet mit dem ersten Tipp) |
| Rhythmusanalyse / Schock | alle 2:00 |
| Helferwechsel | alle 2:00 |
| Adrenalin | alle 4:00 (Leitlinie: 3–5 min) |

- Antippen setzt eine Kachel auf 0 und zählt mit (z. B. „3×“ Adrenalin).
- 15 s vor Ablauf wird die Kachel orange, bei Ablauf rot blinkend und das Handy vibriert.
- Gesamtzeit lange drücken setzt alles für eine neue Reanimation zurück.
- Display bleibt an, die Zeiten überstehen das Schließen der App.
- Seitenleiste rechts (Griff am Rand), Tab „4 H / HITS“: Checkliste der reversiblen Ursachen, Abhaken wird protokolliert.
- Tab „Einstellungen“: Version und Update-Button. Ein oranger Punkt am Griff zeigt ein Update an.
- „Protokoll anzeigen“ listet jeden Tipp mit Uhrzeit und Zeit seit Beginn, „Teilen“ verschickt es als Text.

Download: https://github.com/steffen-png/Netzelch/releases/download/reanimation/Reanimation.apk

Die App ist eine Merkhilfe und ersetzt keine Leitlinie oder Ausbildung.
