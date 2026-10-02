package com.mau.otoservis;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.IOException;

public class MainActivity extends Activity {
    private static final int PICK_RUHSAT_IMAGE = 4021;

    private WebView webView;
    private TextRecognizer textRecognizer;

    private static final String START_URL =
            "https://masafotomotiv-glitch.github.io/MAU-Oto-Servis/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textRecognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(243, 246, 251));
        setContentView(webView);

        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });
        webView.requestApplyInsets();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Sadece MAU sayfasının çağırdığı küçük Android köprüsü:
        // ruhsat fotoğrafı seçer ve OCR sonucunu sayfaya geri verir.
        webView.addJavascriptInterface(new AndroidBridge(), "MAUAndroid");

        webView.clearCache(true);
        webView.clearHistory();
        webView.loadUrl(START_URL + "?refresh=" + System.currentTimeMillis());
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void scanRuhsat() {
            runOnUiThread(MainActivity.this::openRuhsatPicker);
        }
    }

    private void openRuhsatPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_RUHSAT_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_RUHSAT_IMAGE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            sendScanError("Fotoğraf seçimi iptal edildi.");
            return;
        }

        Uri uri = data.getData();

        try {
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (takeFlags != 0) {
                try {
                    getContentResolver()
                            .takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException ignored) {
                    // Bazı galeri sağlayıcıları kalıcı izin desteklemez.
                }
            }

            InputImage image = InputImage.fromFilePath(this, uri);

            textRecognizer
                    .process(image)
                    .addOnSuccessListener(text -> {
                        String raw = text.getText();
                        if (raw == null || raw.trim().isEmpty()) {
                            sendScanError(
                                    "Fotoğrafta okunabilir metin bulunamadı. Daha net bir ruhsat fotoğrafı deneyin."
                            );
                        } else {
                            sendScanResult(raw);
                        }
                    })
                    .addOnFailureListener(e ->
                            sendScanError(
                                    "Metin okunamadı: "
                                            + (e.getMessage() == null
                                            ? "OCR hatası"
                                            : e.getMessage())
                            )
                    );

        } catch (IOException e) {
            sendScanError(
                    "Fotoğraf açılamadı: "
                            + (e.getMessage() == null ? "dosya hatası" : e.getMessage())
            );
        }
    }

    private void sendScanResult(String rawText) {
        String quoted = JSONObject.quote(rawText == null ? "" : rawText);
        webView.post(() ->
                webView.evaluateJavascript(
                        "window.onRuhsatScanResult && window.onRuhsatScanResult("
                                + quoted + ");",
                        null
                )
        );
    }

    private void sendScanError(String message) {
        String quoted = JSONObject.quote(message == null ? "Bilinmeyen hata" : message);
        webView.post(() ->
                webView.evaluateJavascript(
                        "window.onRuhsatScanError && window.onRuhsatScanError("
                                + quoted + ");",
                        null
                )
        );
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("MAUAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
