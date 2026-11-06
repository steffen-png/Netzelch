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
