package de.netzelch.reanimation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Zeitgeber für die Reanimation. Jede Kachel zählt die Zeit seit dem letzten Antippen hoch.
 * Antippen setzt sie auf 0 und zählt das Ereignis. Erreicht eine Kachel ihr Intervall,
 * wird sie orange (15 s vorher) bzw. rot und das Handy vibriert.
 * Die Zeiten überstehen das Schließen der App.
 */
public class MainActivity extends Activity {

    private static final String RELEASE =
            "https://github.com/steffen-png/Netzelch/releases/download/reanimation/";

    private static final int BG = Color.rgb(0x12, 0x14, 0x1A);
    private static final int TILE = Color.rgb(0x23, 0x27, 0x33);
    private static final int WARN = Color.rgb(0xE8, 0x8B, 0x1A);
    private static final int ALARM = Color.rgb(0xD6, 0x28, 0x39);

    /** Name, Intervall in Sekunden (0 = kein Intervall). */
    private static final Object[][] TIMERS = {
            {"Gesamtzeit", 0},
            {"Rhythmusanalyse / Schock", 120},
            {"Helferwechsel", 120},
            {"Adrenalin", 240},
    };

    /** Reversible Ursachen: Gruppe, Name, Hinweis. */
    private static final String[][] CAUSES = {
            {"4 H", "Hypoxie", "Atemweg, Beatmung, O2"},
            {"4 H", "Hypovolämie", "Blutung, Volumen"},
            {"4 H", "Hypo-/Hyperkaliämie, metabolisch", "BGA, Elektrolyte, Glukose"},
            {"4 H", "Hypo-/Hyperthermie", "Temperatur messen"},
            {"HITS", "Herzbeuteltamponade", "Sono"},
            {"HITS", "Intoxikation", "Anamnese, Antidot"},
            {"HITS", "Thrombose (Lungenembolie / Infarkt)", "Lyse erwägen"},
            {"HITS", "Spannungspneumothorax", "Entlastung"},
    };

    private FrameLayout frame;
    private static final int HITS_COLOR = Color.rgb(0xE8, 0x8B, 0x1A);
    private static final int SETTINGS_COLOR = Color.rgb(0x4A, 0x52, 0x66);
    private TextView settingsHandle;
    private View panelStripe;
    private View causesBox, settingsBox;
    private TextView updateStatus;
    private Button updateButton;
    private int latestVersion = -1;
    private View dim;
    private View panel;
    private CheckBox[] causeChecks;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView[] times, labels, counts;
    private LinearLayout[] tiles;
    private boolean[] alarmed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("timers", MODE_PRIVATE);

        int n = TIMERS.length;
        times = new TextView[n];
        labels = new TextView[n];
        counts = new TextView[n];
        tiles = new LinearLayout[n];
        alarmed = new boolean[n];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (i > 0) lp.topMargin = dp(10);
            root.addView(makeTile(i), lp);
        }

        TextView protocol = text("Protokoll anzeigen", 16, true);
        protocol.setGravity(Gravity.CENTER);
        protocol.setPadding(0, dp(12), 0, dp(4));
        protocol.setTextColor(Color.rgb(0x9E, 0xC5, 0xFF));
        protocol.setOnClickListener(v -> showProtocol());
        root.addView(protocol);

        TextView help = new TextView(this);
        help.setText("Tippen: auf 0 setzen · Gesamtzeit lange drücken: alles zurücksetzen");
        help.setTextColor(Color.rgb(0x8A, 0x90, 0xA0));
        help.setTextSize(12);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(10), 0, 0);
        root.addView(help);
        setContentView(buildFrame(root));

        checkForUpdate();
    }

    private View makeTile(int i) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(16), dp(8), dp(16), dp(8));
        tiles[i] = tile;

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text((String) TIMERS[i][0], 20, true);
        labels[i] = label;
        head.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = text("", 18, true);
        counts[i] = count;
        head.addView(count);
        tile.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView time = text("--:--", 64, true);
        time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        times[i] = time;
        tile.addView(time);

        int interval = (int) TIMERS[i][1];
        if (interval > 0) {
            TextView target = text("alle " + format(interval * 1000L), 14, false);
            target.setTextColor(Color.argb(0xB0, 0xFF, 0xFF, 0xFF));
            tile.addView(target);
        }

        tile.setOnClickListener(v -> reset(i));
        if (i == 0) {
            tile.setOnLongClickListener(v -> {
                resetAll();
                return true;
            });
        }
        return tile;
    }

    private void reset(int i) {
        // Gesamtzeit startet nur, sie wird nicht versehentlich durch Antippen zurückgesetzt.
        if (i == 0 && start(0) > 0) return;
        boolean firstTap = start(0) == 0;
        int count = prefs.getInt("count" + i, 0) + 1;
        if (firstTap) log("Reanimation gestartet");
        if (i != 0) log(TIMERS[i][0] + " #" + count);
        prefs.edit()
                .putLong("start" + i, SystemClock.elapsedRealtime())
                .putInt("count" + i, prefs.getInt("count" + i, 0) + 1)
                .apply();
        alarmed[i] = false;
        // Erster Tipp irgendwo startet auch die Gesamtzeit.
        if (i != 0 && start(0) == 0) {
            prefs.edit().putLong("start0", SystemClock.elapsedRealtime()).apply();
        }
        vibrate(40);
        update();
    }

    // ---- Seitenleiste rechts ----

    /** Hauptseite mit Griff am rechten Rand und darüber die ausklappbare Seitenleiste. */
    private View buildFrame(View main) {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);

        LinearLayout row = new LinearLayout(this);
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Farbige Griffe am rechten Rand, einer pro Bereich.
        LinearLayout handles = new LinearLayout(this);
        handles.setOrientation(LinearLayout.VERTICAL);
        TextView hits = handle("H\nI\nT\nS", HITS_COLOR, "4 H und HITS");
        hits.setOnClickListener(v -> openPanel(false));
        handles.addView(hits, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));
        settingsHandle = handle("⚙", SETTINGS_COLOR, "Einstellungen");
        settingsHandle.setOnClickListener(v -> openPanel(true));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = dp(6);
        handles.addView(settingsHandle, sl);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT);
        hl.leftMargin = dp(4);
        row.addView(handles, hl);
        frame.addView(row);

        dim = new View(this);
        // Nur leicht abdunkeln: die Zeiten links bleiben lesbar.
        dim.setBackgroundColor(Color.argb(0x40, 0, 0, 0));
        dim.setVisibility(View.GONE);
        dim.setOnClickListener(v -> closePanel());
        frame.addView(dim);

        // Die Leiste ragt nur halb ins Bild.
        int width = getResources().getDisplayMetrics().widthPixels / 2;
        panel = buildPanel();
        panel.setVisibility(View.GONE);
        frame.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        return frame;
    }

    private View buildPanel() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(8));

        LinearLayout causes = new LinearLayout(this);
        causes.setOrientation(LinearLayout.VERTICAL);
        causesBox = causes;
        list.addView(causes);
        settingsBox = buildSettings();
        list.addView(settingsBox);

        // Funktion 1: reversible Ursachen (ohne Überschrift, damit alles ohne Scrollen passt)

        causeChecks = new CheckBox[CAUSES.length];
        String group = "";
        for (int k = 0; k < CAUSES.length; k++) {
            if (!CAUSES[k][0].equals(group)) {
                group = CAUSES[k][0];
                TextView g = text(group, 15, true);
                g.setTextColor(Color.rgb(0x9E, 0xC5, 0xFF));
                g.setPadding(0, dp(k == 0 ? 4 : 10), 0, 0);
                causes.addView(g);
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(CAUSES[k][1] + "\n" + CAUSES[k][2]);
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(14);
            cb.setPadding(dp(4), dp(6), dp(6), dp(6));
            cb.setButtonTintList(ColorStateList.valueOf(Color.WHITE));
            final int idx = k;
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked == prefs.getBoolean("cause" + idx, false)) return;
                prefs.edit().putBoolean("cause" + idx, checked).apply();
                styleCause(idx, checked);
                log((checked ? "Ausgeschlossen: " : "Wieder offen: ") + CAUSES[idx][1]);
                updateCauseCount();
            });
            causeChecks[k] = cb;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            causes.addView(cb, lp);
        }
        causeCount = text("", 14, false);
        causeCount.setTextColor(Color.rgb(0x8A, 0x90, 0xA0));
        causeCount.setPadding(0, dp(8), 0, 0);
        causes.addView(causeCount);
        refreshCauses();


        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);

        // Farbiger Streifen links zeigt, welcher Bereich offen ist.
        LinearLayout wrap = new LinearLayout(this);
        wrap.setBackgroundColor(Color.rgb(0x1C, 0x20, 0x2B));
        panelStripe = new View(this);
        wrap.addView(panelStripe, new LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return wrap;
    }

    private TextView causeCount;

    private TextView handle(String label, int color, String description) {
        TextView t = text(label, 14, true);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 0.9f);
        t.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        float r = dp(10);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        t.setBackground(bg);
        return t;
    }

    private void showTab(boolean settings) {
        causesBox.setVisibility(settings ? View.GONE : View.VISIBLE);
        settingsBox.setVisibility(settings ? View.VISIBLE : View.GONE);
        panelStripe.setBackgroundColor(settings ? SETTINGS_COLOR : HITS_COLOR);
    }

    private View buildSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);

        TextView title = text("App-Update", 17, true);
        title.setTextColor(WARN);
        box.addView(title);

        TextView version = text("Installiert: " + versionName(), 14, false);
        version.setPadding(0, dp(6), 0, 0);
        box.addView(version);

        updateStatus = text("Suche nach Updates …", 14, false);
        updateStatus.setTextColor(Color.rgb(0xC8, 0xCC, 0xD8));
        updateStatus.setPadding(0, dp(4), 0, dp(10));
        box.addView(updateStatus);

        updateButton = new Button(this);
        updateButton.setText("Nach Updates suchen");
        updateButton.setAllCaps(false);
        updateButton.setOnClickListener(v -> {
            if (latestVersion > versionCode()) {
                // Android erlaubt Apps außerhalb eines Stores kein Selbst-Update,
                // daher lädt der Browser die neue Version.
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE + "Reanimation.apk")));
            } else {
                checkForUpdate();
            }
        });
        box.addView(updateButton);

        TextView how = text("Der Browser lädt die neue Version. Danach die Datei öffnen "
                + "und „Aktualisieren“ tippen. Zeiten und Protokoll bleiben erhalten.", 12, false);
        how.setTextColor(Color.rgb(0x8A, 0x90, 0xA0));
        how.setPadding(0, dp(8), 0, 0);
        box.addView(how);
        return box;
    }

    private void showUpdateState(String status, boolean available) {
        updateStatus.setText(status);
        updateStatus.setTextColor(available ? WARN : Color.rgb(0xC8, 0xCC, 0xD8));
        updateButton.setText(available ? "Update herunterladen" : "Nach Updates suchen");
        updateButton.setEnabled(true);
        // Orangener Punkt am Griff zeigt ein Update an.
        settingsHandle.setText(available ? "⚙\n●" : "⚙");
        settingsHandle.setTextColor(available ? WARN : Color.WHITE);
    }

    private long versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void refreshCauses() {
        for (int k = 0; k < causeChecks.length; k++) {
            boolean checked = prefs.getBoolean("cause" + k, false);
            causeChecks[k].setChecked(checked);
            styleCause(k, checked);
        }
        updateCauseCount();
    }

    /** Abgehakt: grüne Fläche. Offen: dunkle Fläche mit hellem Rand. */
    private void styleCause(int k, boolean checked) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (checked) {
            bg.setColor(Color.rgb(0x2E, 0x8B, 0x3E));
        } else {
            bg.setColor(Color.rgb(0x2A, 0x2F, 0x3C));
            bg.setStroke(dp(1), Color.rgb(0x4A, 0x52, 0x66));
        }
        CheckBox cb = causeChecks[k];
        cb.setBackground(bg);
        cb.setText((checked ? "✓ " : "") + CAUSES[k][1] + "\n" + (checked ? "ausgeschlossen" : CAUSES[k][2]));
        cb.setTypeface(checked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void updateCauseCount() {
        int done = 0;
        for (int k = 0; k < CAUSES.length; k++) if (prefs.getBoolean("cause" + k, false)) done++;
        causeCount.setText(done + " von " + CAUSES.length + " abgehakt");
    }

    private void openPanel(boolean settings) {
        showTab(settings);
        dim.setVisibility(View.VISIBLE);
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setDuration(200).start();
        panel.setVisibility(View.VISIBLE);
        panel.post(() -> {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0).setDuration(220).start();
        });
    }

    private void closePanel() {
        dim.animate().alpha(0f).setDuration(200).withEndAction(() -> dim.setVisibility(View.GONE)).start();
        panel.animate().translationX(panel.getWidth()).setDuration(220)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            closePanel();
        } else {
            super.onBackPressed();
        }
    }

    private void resetAll() {
        log("Neue Reanimation (alles zurückgesetzt)");
        // Das bisherige Protokoll bleibt als „vorheriges“ erhalten.
        String last = prefs.getString("log", "");
        prefs.edit().clear().putString("previousLog", last).apply();
        alarmed = new boolean[TIMERS.length];
        refreshCauses();
        vibrate(200);
        update();
    }

    private long start(int i) {
        long s = prefs.getLong("start" + i, 0);
        // Nach einem Neustart des Handys sind alte Werte ungültig.
        return s > SystemClock.elapsedRealtime() ? 0 : s;
    }

    private void update() {
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < TIMERS.length; i++) {
            long s = start(i);
            int interval = (int) TIMERS[i][1];
            int count = prefs.getInt("count" + i, 0);
            counts[i].setText(i > 0 && count > 0 ? count + "×" : "");

            int color = TILE;
            if (s == 0) {
                times[i].setText("--:--");
            } else {
                long elapsed = now - s;
                times[i].setText(format(elapsed));
                if (interval > 0) {
                    long ms = interval * 1000L;
                    if (elapsed >= ms) {
                        // Rot, blinkt im Sekundentakt
                        color = (elapsed / 500) % 2 == 0 ? ALARM : darker(ALARM);
                        if (!alarmed[i]) {
                            alarmed[i] = true;
                            vibrate(600);
                        }
                    } else if (elapsed >= ms - 15000) {
                        color = WARN;
                    }
                }
            }
            setTileColor(tiles[i], color);
        }
    }

    private void checkForUpdate() {
        updateStatus.setText("Suche nach Updates …");
        updateButton.setEnabled(false);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE + "version.txt").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setUseCaches(false);
                int latest;
                try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    latest = Integer.parseInt(in.readLine().trim());
                } finally {
                    c.disconnect();
                }
                runOnUiThread(() -> {
                    latestVersion = latest;
                    if (latest > versionCode()) {
                        showUpdateState("Neue Version 1." + latest + " verfügbar", true);
                    } else {
                        showUpdateState("Die App ist aktuell.", false);
                    }
                });
            } catch (Exception e) {
                // Ohne Internet läuft die App normal weiter.
                runOnUiThread(() -> showUpdateState("Keine Verbindung zum Update-Server.", false));
            }
        }).start();
    }

    /** Hängt eine Zeile ans Protokoll: Uhrzeit, Zeit seit Beginn, Ereignis. */
    private void log(String event) {
        long s = start(0);
        String since = s == 0 ? "00:00" : format(SystemClock.elapsedRealtime() - s);
        String clock = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(new Date());
        String line = clock + "  (+" + since + ")  " + event;
        String old = prefs.getString("log", "");
        prefs.edit().putString("log", old.isEmpty() ? line : old + "\n" + line).apply();
    }

    private String protocolText() {
        String current = prefs.getString("log", "");
        String previous = prefs.getString("previousLog", "");
        String date = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date());
        StringBuilder sb = new StringBuilder("Reanimationsprotokoll ").append(date).append("\n\n");
        sb.append(current.isEmpty() ? "Noch keine Einträge." : current);
        if (!previous.isEmpty()) {
            sb.append("\n\n--- Vorherige Reanimation ---\n").append(previous);
        }
        return sb.toString();
    }

    private void showProtocol() {
        String text = protocolText();
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(15);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("Protokoll")
                .setView(scroll)
                .setPositiveButton("Teilen", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Reanimationsprotokoll")
                            .putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, "Protokoll teilen"));
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void setTileColor(LinearLayout tile, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        tile.setBackground(bg);
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 2 / 3, Color.green(c) * 2 / 3, Color.blue(c) * 2 / 3);
    }

    private static String format(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.GERMANY, "%02d:%02d", sec / 60, sec % 60);
    }

    private void vibrate(long ms) {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
