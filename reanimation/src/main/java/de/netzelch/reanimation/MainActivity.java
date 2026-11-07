package de.netzelch.reanimation;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
        root.setFitsSystemWindows(true);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (i > 0) lp.topMargin = dp(10);
            root.addView(makeTile(i), lp);
        }

        TextView help = new TextView(this);
        help.setText("Tippen: auf 0 setzen · Gesamtzeit lange drücken: alles zurücksetzen");
        help.setTextColor(Color.rgb(0x8A, 0x90, 0xA0));
        help.setTextSize(12);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(10), 0, 0);
        help.setOnLongClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE + "Reanimation.apk")));
            return true;
        });
        root.addView(help);
        setContentView(root);

        checkForUpdate(help);
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

    private void resetAll() {
        prefs.edit().clear().apply();
        alarmed = new boolean[TIMERS.length];
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

    private void checkForUpdate(TextView help) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE + "version.txt").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                int latest;
                try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    latest = Integer.parseInt(in.readLine().trim());
                } finally {
                    c.disconnect();
                }
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (latest > info.getLongVersionCode()) {
                    runOnUiThread(() -> {
                        help.setText("Update 1." + latest + " verfügbar: hier lange drücken");
                        help.setTextColor(WARN);
                    });
                }
            } catch (Exception ignored) {
                // Ohne Internet läuft die App normal weiter.
            }
        }).start();
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
