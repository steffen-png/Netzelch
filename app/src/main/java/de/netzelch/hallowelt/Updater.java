package de.netzelch.hallowelt;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Lädt neue Versionen von GitHub und übergibt sie an den Android-Installer. */
final class Updater {

    private static final String BASE =
            "https://github.com/steffen-png/Netzelch/releases/download/hallo-welt/";
    static final String VERSION_URL = BASE + "version.txt";
    static final String APK_URL = BASE + "HalloWelt.apk";

    static final String ACTION_INSTALL_STATUS = "de.netzelch.hallowelt.INSTALL_STATUS";

    private Updater() {}

    /** Neueste Versionsnummer auf GitHub. Blockiert, nicht im UI-Thread aufrufen. */
    static int fetchLatestVersion() throws IOException {
        HttpURLConnection conn = open(VERSION_URL);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return Integer.parseInt(in.readLine().trim());
        } finally {
            conn.disconnect();
        }
    }

    /** Lädt die APK in eine Installer-Sitzung und startet die Installation. */
    static void downloadAndInstall(Context context, ProgressListener progress) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = installer.createSession(params);
        HttpURLConnection conn = open(APK_URL);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, total)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) progress.onProgress((int) (done * 100 / total));
                }
                session.fsync(out);
            }

            // Das Ergebnis kommt als Intent zurück an die MainActivity.
            Intent intent = new Intent(context, MainActivity.class)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("Server antwortet mit " + code);
        }
        return conn;
    }

    interface ProgressListener {
        void onProgress(int percent);
    }
}
