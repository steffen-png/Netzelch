package de.netzelch.hallowelt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String KEY_SELF_UPDATE_BLOCKED = "self_update_blocked";

    private TextView status;
    private Button updateButton;
    private int latestVersion = -1;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView device = findViewById(R.id.device);
        device.setText(Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid " + Build.VERSION.RELEASE
                + "\nApp-Version " + BuildConfigInfo.versionName(this));

        status = findViewById(R.id.status);
        updateButton = findViewById(R.id.update);
        updateButton.setOnClickListener(v -> onUpdateClicked());

        findViewById(R.id.card).setOnClickListener(v ->
                startActivity(new Intent(this, CardActivity.class)));

        checkForUpdate();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            handleInstallStatus(intent);
        }
    }

    private void checkForUpdate() {
        busy = true;
        showStatus("Suche nach Updates …", false);
        new Thread(() -> {
            try {
                int latest = Updater.fetchLatestVersion();
                int current = BuildConfigInfo.versionCode(this);
                runOnUiThread(() -> {
                    busy = false;
                    latestVersion = latest;
                    if (latest > current) {
                        showStatus("Neue Version 1." + latest + " verfügbar", true);
                        updateButton.setText("Jetzt aktualisieren");
                    } else {
                        showStatus("Die App ist aktuell.", true);
                        updateButton.setText("Erneut prüfen");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    showStatus("Update-Prüfung fehlgeschlagen: " + e.getMessage(), true);
                    updateButton.setText("Erneut prüfen");
                });
            }
        }).start();
    }

    private void onUpdateClicked() {
        if (busy) return;
        if (latestVersion <= BuildConfigInfo.versionCode(this)) {
            checkForUpdate();
            return;
        }
        // Android verlangt einmalig die Erlaubnis, Apps aus dieser Quelle zu installieren.
        if (!getPackageManager().canRequestPackageInstalls()) {
            showStatus("Bitte „Apps aus dieser Quelle erlauben“ einschalten und zurückkehren.", true);
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        // Manche Android-Versionen verbieten Apps außerhalb eines Stores, sich selbst
        // zu installieren. Dann laden wir die neue Version über den Browser.
        if (selfUpdateBlocked()) {
            openInBrowser();
            return;
        }
        busy = true;
        showStatus("Lade Update …", false);
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(this, percent ->
                        runOnUiThread(() -> status.setText("Lade Update … " + percent + " %")));
                runOnUiThread(() -> status.setText("Installation wird gestartet …"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    showStatus("Download fehlgeschlagen: " + e.getMessage(), true);
                });
            }
        }).start();
    }

    private void handleInstallStatus(Intent intent) {
        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android zeigt jetzt den Dialog „Diese App aktualisieren?“.
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (code == PackageInstaller.STATUS_SUCCESS) {
            busy = false;
            showStatus("Update installiert.", true);
        } else {
            busy = false;
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (msg != null && (msg.contains("Self update") || msg.contains("unknown source"))) {
                prefs().edit().putBoolean(KEY_SELF_UPDATE_BLOCKED, true).apply();
                openInBrowser();
                return;
            }
            showStatus("Installation abgebrochen" + (msg != null ? ": " + msg : "."), true);
        }
    }

    private void openInBrowser() {
        showStatus("Der Browser lädt die neue Version. Danach die Datei öffnen und "
                + "„Aktualisieren“ tippen.", true);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL)));
    }

    private boolean selfUpdateBlocked() {
        return prefs().getBoolean(KEY_SELF_UPDATE_BLOCKED, false);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("update", MODE_PRIVATE);
    }

    private void showStatus(String text, boolean buttonEnabled) {
        status.setText(text);
        updateButton.setEnabled(buttonEnabled);
        updateButton.setVisibility(View.VISIBLE);
    }
}
