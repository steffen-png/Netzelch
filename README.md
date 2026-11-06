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
