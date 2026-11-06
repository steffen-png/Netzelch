package de.netzelch.hallowelt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Zeigt die gespeicherte Kundenkarte als QR-Code bei voller Helligkeit.
 * Der Code wird einmalig aus einem Screenshot gelesen und nur auf dem Handy gespeichert.
 */
public class CardActivity extends Activity {

    private static final int PICK_IMAGE = 1;
    private static final String PREFS = "card";
    private static final String KEY_CODE = "code";

    private ImageView qr;
    private TextView number;
    private TextView hint;
    private Button pick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card);

        // Volle Helligkeit und Display bleibt an, solange die Karte offen ist.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        qr = findViewById(R.id.qr);
        number = findViewById(R.id.number);
        hint = findViewById(R.id.hint);
        pick = findViewById(R.id.pick);
        pick.setOnClickListener(v -> pickScreenshot());

        render();
    }

    private void render() {
        String code = prefs().getString(KEY_CODE, null);
        if (code == null) {
            qr.setVisibility(View.GONE);
            number.setVisibility(View.GONE);
            hint.setText("Noch keine Karte gespeichert.\n\nMach in der Kunden-App einen Screenshot "
                    + "deiner Karte und wähle ihn hier aus.");
            pick.setText("Screenshot auswählen");
            return;
        }
        try {
            qr.setImageDrawable(pixelSharp(makeQr(code, 800)));
            qr.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            hint.setText("QR-Code konnte nicht erzeugt werden: " + e.getMessage());
            return;
        }
        String id = customerId(code);
        number.setText(id != null ? "Kundennummer: " + id : "");
        number.setVisibility(id != null ? View.VISIBLE : View.GONE);
        hint.setText("");
        pick.setText("Andere Karte wählen");
    }

    private void pickScreenshot() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Screenshot auswählen"), PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String code = readQr(uri);
            prefs().edit().putString(KEY_CODE, code).apply();
            render();
        } catch (Exception e) {
            hint.setText("Im Bild wurde kein QR-Code gefunden. Nimm einen Screenshot, "
                    + "auf dem der Code vollständig zu sehen ist.");
        }
    }

    private String readQr(Uri uri) throws Exception {
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in);
        }
        if (bmp == null) throw new IllegalArgumentException("Bild nicht lesbar");
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        return new MultiFormatReader().decode(image, hints).getText();
    }

    private static Bitmap makeQr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    private BitmapDrawable pixelSharp(Bitmap bmp) {
        BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
        d.setFilterBitmap(false);
        return d;
    }

    /** Liest die Kundennummer aus dem Code, falls er ein JWT mit "customer_id" enthält. */
    private static String customerId(String code) {
        try {
            String[] parts = code.substring(code.indexOf(':') + 1).split("\\.");
            String json = new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
            String id = new JSONObject(json).optString("customer_id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
