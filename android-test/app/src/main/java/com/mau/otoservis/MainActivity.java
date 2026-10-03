package com.mau.otoservis;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.net.HttpURLConnection;
import java.net.URL;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.os.Bundle;
import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;


import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final String SECURITY_KEY_ALIAS = "mau_oto_servis_local_v1";
    private static final String SECURITY_PREFIX = "AKS1.";
    private static final int PICK_RUHSAT_IMAGE = 4021;
    private static final int CREATE_CSV_FILE = 4022;
    private static final int CREATE_XLSX_FILE = 4023;
    private static final int CREATE_BINARY_FILE = 4024;
    private static final int WEB_FILE_CHOOSER = 4025;

    private WebView webView;
    private TextRecognizer textRecognizer;
    private String pendingCsvContent = "";
    private String pendingCsvName = "MAU-Rapor.csv";
    private String pendingXlsxRows = "[]";
    private String pendingXlsxName = "MAU-Rapor.xlsx";
    private File pendingBinaryFile;
    private FileOutputStream pendingBinaryStream;
    private String pendingBinaryName = "MAU-Dosya.bin";
    private String pendingBinaryMime = "application/octet-stream";
    private ValueCallback<Uri[]> pendingFileChooser;
    private UpdateManager updateManager;

    private static final String START_URL =
            "https://masafotomotiv-glitch.github.io/MAU-Oto-Servis/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textRecognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(243, 246, 251));
        setContentView(webView);
        updateManager = new UpdateManager(this);

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
        // Test döneminde GitHub Pages'teki güncel arayüz önceliklidir.
        // Normal HTTP önbellek kuralları kullanılır; eski içeriğe zorla bağlı kalınmaz.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSaveFormData(false);
        s.setSafeBrowsingEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (isAllowedAppUri(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Bağlantı açılamadı.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null
                        && "https".equalsIgnoreCase(uri.getScheme())
                        && "masafotomotiv-glitch.github.io".equalsIgnoreCase(uri.getHost())
                        && uri.getPath() != null
                        && uri.getPath().startsWith("/MAU-Oto-Servis/")
                        && !isInternetAvailable()) {
                    String assetPath = uri.getPath().substring("/MAU-Oto-Servis/".length());
                    if (assetPath.isEmpty() || assetPath.endsWith("/")) {
                        assetPath += "index.html";
                    }
                    try {
                        InputStream in = getAssets().open(assetPath);
                        return new WebResourceResponse(
                                mimeTypeFor(assetPath),
                                "UTF-8",
                                in
                        );
                    } catch (IOException ignored) {
                        // Paketlenmemis bir kaynaksa normal WebView davranisina birak.
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = filePathCallback;

                try {
                    Intent intent;
                    if (fileChooserParams != null) {
                        intent = fileChooserParams.createIntent();
                    } else {
                        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    startActivityForResult(intent, WEB_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    if (pendingFileChooser != null) {
                        pendingFileChooser.onReceiveValue(null);
                        pendingFileChooser = null;
                    }
                    Toast.makeText(
                            MainActivity.this,
                            "Dosya seçici açılamadı: "
                                    + (e.getMessage() == null ? "Android dosya seçici hatası" : e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                    return true;
                }
            }
        });

        // Android Keystore tabanlı yerel veri şifreleme köprüsü.
        webView.addJavascriptInterface(new SecurityBridge(), "MAUSecurity");

        // Sadece MAU sayfasının çağırdığı küçük Android köprüsü:
        // ruhsat fotoğrafı seçer ve OCR sonucunu sayfaya geri verir.
        webView.addJavascriptInterface(new AndroidBridge(), "MAUAndroid");

        // Internet varsa test donemindeki guncel GitHub arayuzu acilir.
        // Internet yoksa ayni URL WebViewClient tarafindan APK icindeki kopyadan servis edilir.
        loadStartPage();
        updateManager.checkForUpdates();
    }

    private void loadStartPage() {
        String url = isInternetAvailable()
                ? START_URL + "?v=" + System.currentTimeMillis()
                : START_URL;
        webView.loadUrl(url);
    }

    private boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    private String mimeTypeFor(String path) {
        String p = path == null ? "" : path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private boolean isAllowedAppUri(Uri uri) {
        if (uri == null) return false;
        if ("about".equalsIgnoreCase(uri.getScheme())) return true;
        return "https".equalsIgnoreCase(uri.getScheme())
                && "masafotomotiv-glitch.github.io".equalsIgnoreCase(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().startsWith("/MAU-Oto-Servis/");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Uygulama ikonuna yeniden basildiginda her zaman ana sayfadan basla.
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            loadStartPage();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingInstallIfAllowed();
        }
    }

    private SecretKey getOrCreateSecurityKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(SECURITY_KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
            );
            generator.init(new KeyGenParameterSpec.Builder(
                    SECURITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        KeyStore.SecretKeyEntry entry =
                (KeyStore.SecretKeyEntry) keyStore.getEntry(SECURITY_KEY_ALIAS, null);
        if (entry == null) {
            throw new IllegalStateException("Cihaz güvenlik anahtarı alınamadı.");
        }
        return entry.getSecretKey();
    }

    private class SecurityBridge {
        @JavascriptInterface
        public String encrypt(String plainText) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecurityKey());
                byte[] iv = cipher.getIV();
                byte[] encrypted = cipher.doFinal(
                        (plainText == null ? "" : plainText).getBytes(StandardCharsets.UTF_8)
                );
                return SECURITY_PREFIX
                        + Base64.encodeToString(iv, Base64.NO_WRAP)
                        + "."
                        + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public String decrypt(String blob) {
            try {
                if (blob == null || !blob.startsWith(SECURITY_PREFIX)) return null;
                String[] parts = blob.split("\\.", 3);
                if (parts.length != 3 || !"AKS1".equals(parts[0])) return null;
                byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
                byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        getOrCreateSecurityKey(),
                        new GCMParameterSpec(128, iv)
                );
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean isAvailable() {
            try {
                return getOrCreateSecurityKey() != null;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void scanRuhsat() {
            runOnUiThread(MainActivity.this::openRuhsatPicker);
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(MainActivity.this::printCurrentPage);
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> shareCurrentText(text));
        }

        @JavascriptInterface
        public void shareCsv(String fileName, String csvContent) {
            runOnUiThread(() -> shareCsvFile(fileName, csvContent));
        }

        @JavascriptInterface
        public void saveXlsx(String fileName, String rowsJson) {
            runOnUiThread(() -> saveXlsxFile(fileName, rowsJson));
        }

        @JavascriptInterface
        public void beginBinarySave(String fileName, String mimeType) {
            beginBinarySaveInternal(fileName, mimeType);
        }

        @JavascriptInterface
        public void appendBinaryChunk(String base64Chunk) {
            appendBinaryChunkInternal(base64Chunk);
        }

        @JavascriptInterface
        public void finishBinarySave() {
            finishBinarySaveInternal();
        }

        @JavascriptInterface
        public void hknPost(
                String requestId,
                String url,
                String jsonBody,
                String accessToken,
                String cdnToken,
                String appVersionCode
        ) {
            new Thread(() -> hknPostInternal(
                    requestId,
                    url,
                    jsonBody,
                    accessToken,
                    cdnToken,
                    appVersionCode
            ), "MAU-HKNSoft-Export").start();
        }
    }

    private boolean isAllowedHknPath(String path) {
        if (path == null) return false;
        return path.equals("/stok-takip/auth/login")
                || path.equals("/stok-takip/profile/me")
                || path.equals("/stok-takip/profile/company")
                || path.equals("/stok-takip/contacts/all")
                || path.equals("/stok-takip/contacts/one/ledger")
                || path.equals("/stok-takip/products/all")
                || path.equals("/stok-takip/products/one/inventory-transactions/all")
                || path.equals("/stok-takip/products/one/warehouse-stocks")
                || path.equals("/stok-takip/invoices/all")
                || path.equals("/stok-takip/invoices/one")
                || path.equals("/stok-takip/accounts/all")
                || path.equals("/stok-takip/finance-transactions/all")
                || path.equals("/stok-takip/warehouses/all")
                || path.equals("/stok-takip/categories/all");
    }

    private void hknPostInternal(
            String requestId,
            String urlText,
            String jsonBody,
            String accessToken,
            String cdnToken,
            String appVersionCode
    ) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText == null ? "" : urlText);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !"apigw.hknsoft.com".equalsIgnoreCase(url.getHost())
                    || !isAllowedHknPath(url.getPath())) {
                throw new SecurityException("İzin verilmeyen HKNSoft API adresi.");
            }

            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Z-Access-Token",
                    accessToken == null || accessToken.trim().isEmpty() ? "none" : accessToken.trim()
            );
            connection.setRequestProperty(
                    "Z-CDN-Token",
                    cdnToken == null || cdnToken.trim().isEmpty() ? "none" : cdnToken.trim()
            );
            connection.setRequestProperty("Z-Device-Type", "101");
            connection.setRequestProperty(
                    "Z-App-Version-Code",
                    appVersionCode == null || appVersionCode.trim().isEmpty()
                            ? "10125"
                            : appVersionCode.trim()
            );

            byte[] requestBytes = (jsonBody == null || jsonBody.trim().isEmpty() ? "{}" : jsonBody)
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(requestBytes);
                out.flush();
            }

            int status = connection.getResponseCode();
            InputStream in = status >= 200 && status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readUtf8Fully(in);
            sendHknResult(requestId, status, body);
        } catch (Exception e) {
            sendHknError(
                    requestId,
                    e.getMessage() == null ? "HKNSoft bağlantı hatası" : e.getMessage()
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readUtf8Fully(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        try (InputStream in = inputStream;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void sendHknResult(String requestId, int status, String body) {
        String requestQuoted = JSONObject.quote(requestId == null ? "" : requestId);
        String bodyQuoted = JSONObject.quote(body == null ? "" : body);
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.onHknApiResult && window.onHknApiResult("
                                + requestQuoted + "," + status + "," + bodyQuoted + ");",
                        null
                );
            }
        });
    }

    private void sendHknError(String requestId, String message) {
        String requestQuoted = JSONObject.quote(requestId == null ? "" : requestId);
        String messageQuoted = JSONObject.quote(message == null ? "HKNSoft bağlantı hatası" : message);
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.onHknApiError && window.onHknApiError("
                                + requestQuoted + "," + messageQuoted + ");",
                        null
                );
            }
        });
    }

    private void shareCurrentText(String text) {
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);

        Intent chooser = Intent.createChooser(sendIntent, "Raporu paylaş");
        startActivity(chooser);
    }

    private void shareCsvFile(String fileName, String csvContent) {
        pendingCsvContent = csvContent == null ? "" : csvContent;
        pendingCsvName = fileName == null || fileName.trim().isEmpty()
                ? "MAU-Rapor.csv"
                : fileName.trim();

        if (!pendingCsvName.toLowerCase().endsWith(".csv")) {
            pendingCsvName += ".csv";
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, pendingCsvName);
        startActivityForResult(intent, CREATE_CSV_FILE);
    }

    private void saveXlsxFile(String fileName, String rowsJson) {
        pendingXlsxRows = rowsJson == null || rowsJson.trim().isEmpty() ? "[]" : rowsJson;
        pendingXlsxName = fileName == null || fileName.trim().isEmpty()
                ? "MAU-Rapor.xlsx"
                : fileName.trim();

        if (!pendingXlsxName.toLowerCase().endsWith(".xlsx")) {
            pendingXlsxName += ".xlsx";
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.putExtra(Intent.EXTRA_TITLE, pendingXlsxName);
        startActivityForResult(intent, CREATE_XLSX_FILE);
    }

    private synchronized void beginBinarySaveInternal(String fileName, String mimeType) {
        try {
            if (pendingBinaryStream != null) {
                pendingBinaryStream.close();
            }
        } catch (Exception ignored) {}

        try {
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Geçici yedek klasörü oluşturulamadı.");
            }
            String safe = fileName == null || fileName.trim().isEmpty() ? "MAU-Dosya.bin" : fileName.trim();
            safe = safe.replaceAll("[\\\\/:*?\"<>|]+", "_");
            pendingBinaryName = safe;
            pendingBinaryMime = mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType.trim();
            pendingBinaryFile = new File(dir, "pending-" + System.currentTimeMillis() + "-" + safe);
            pendingBinaryStream = new FileOutputStream(pendingBinaryFile);
        } catch (Exception e) {
            pendingBinaryStream = null;
            pendingBinaryFile = null;
            runOnUiThread(() -> Toast.makeText(this,
                    "Dosya hazırlama başlatılamadı: " + (e.getMessage() == null ? "hata" : e.getMessage()),
                    Toast.LENGTH_LONG).show());
        }
    }

    private synchronized void appendBinaryChunkInternal(String base64Chunk) {
        if (pendingBinaryStream == null) {
            return;
        }
        try {
            byte[] bytes = Base64.decode(base64Chunk == null ? "" : base64Chunk, Base64.DEFAULT);
            pendingBinaryStream.write(bytes);
        } catch (Exception e) {
            try { pendingBinaryStream.close(); } catch (Exception ignored) {}
            pendingBinaryStream = null;
            runOnUiThread(() -> Toast.makeText(this,
                    "Dosya hazırlanırken hata oluştu: " + (e.getMessage() == null ? "hata" : e.getMessage()),
                    Toast.LENGTH_LONG).show());
        }
    }

    private synchronized void finishBinarySaveInternal() {
        try {
            if (pendingBinaryStream != null) {
                pendingBinaryStream.flush();
                pendingBinaryStream.close();
            }
        } catch (Exception e) {
            pendingBinaryStream = null;
            runOnUiThread(() -> Toast.makeText(this,
                    "Dosya tamamlanamadı: " + (e.getMessage() == null ? "hata" : e.getMessage()),
                    Toast.LENGTH_LONG).show());
            return;
        }
        pendingBinaryStream = null;
        if (pendingBinaryFile == null || !pendingBinaryFile.exists() || pendingBinaryFile.length() <= 0) {
            runOnUiThread(() -> Toast.makeText(this, "Kaydedilecek dosya boş veya hazırlanamadı.", Toast.LENGTH_LONG).show());
            if (pendingBinaryFile != null) {
                try { pendingBinaryFile.delete(); } catch (Exception ignored) {}
            }
            pendingBinaryFile = null;
            return;
        }
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(pendingBinaryMime);
            intent.putExtra(Intent.EXTRA_TITLE, pendingBinaryName);
            startActivityForResult(intent, CREATE_BINARY_FILE);
        });
    }

    private void printCurrentPage() {
        if (webView == null) {
            return;
        }

        PrintManager printManager =
                (PrintManager) getSystemService(Context.PRINT_SERVICE);

        if (printManager == null) {
            webView.evaluateJavascript(
                    "window.onMauPrintError && window.onMauPrintError('Android yazdırma servisi kullanılamıyor.');",
                    null
            );
            return;
        }

        String jobName = "MAU_Oto_Servis_" + System.currentTimeMillis();
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);

        printManager.print(
                jobName,
                adapter,
                new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
        );
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

        if (requestCode == WEB_FILE_CHOOSER) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                try {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                } catch (Exception e) {
                    Toast.makeText(
                            this,
                            "Seçilen dosya okunamadı: "
                                    + (e.getMessage() == null ? "dosya seçimi hatası" : e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                }
            }

            if (pendingFileChooser != null) {
                pendingFileChooser.onReceiveValue(result);
                pendingFileChooser = null;
            }
            return;
        }

        if (requestCode == CREATE_BINARY_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBinaryFile != null) {
                try (FileInputStream in = new FileInputStream(pendingBinaryFile);
                     OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new IOException("Hedef dosya açılamadı.");
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    Toast.makeText(this, "Dosya kaydedildi.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this,
                            "Dosya kaydedilemedi: " + (e.getMessage() == null ? "dosya hatası" : e.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            }
            if (pendingBinaryFile != null) {
                try { pendingBinaryFile.delete(); } catch (Exception ignored) {}
            }
            pendingBinaryFile = null;
            return;
        }

        if (requestCode == CREATE_XLSX_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) {
                        throw new IOException("Dosya açılamadı.");
                    }
                    writeXlsx(out, pendingXlsxRows);
                    Toast.makeText(this, "Excel raporu kaydedildi.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(
                            this,
                            "Excel kaydedilemedi: "
                                    + (e.getMessage() == null ? "dosya hatası" : e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
            pendingXlsxRows = "[]";
            return;
        }

        if (requestCode == CREATE_CSV_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) {
                        throw new IOException("Dosya açılamadı.");
                    }
                    out.write(pendingCsvContent.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Toast.makeText(this, "CSV raporu kaydedildi.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(
                            this,
                            "CSV kaydedilemedi: "
                                    + (e.getMessage() == null ? "dosya hatası" : e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
            pendingCsvContent = "";
            return;
        }

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

    private void writeXlsx(OutputStream outputStream, String rowsJson) throws Exception {
        JSONArray rows = new JSONArray(rowsJson == null ? "[]" : rowsJson);

        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            putZipEntry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                            + "</Types>");

            putZipEntry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                            + "</Relationships>");

            putZipEntry(zip, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                            + "<sheets><sheet name=\"Rapor\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                            + "</workbook>");

            putZipEntry(zip, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                            + "</Relationships>");

            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");

            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.optJSONArray(r);
                if (row == null) {
                    continue;
                }
                int rowNo = r + 1;
                sheet.append("<row r=\"").append(rowNo).append("\">");
                for (int col = 0; col < row.length(); col++) {
                    String ref = excelColumn(col) + rowNo;
                    String value = String.valueOf(row.opt(col) == null ? "" : row.opt(col));
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(xmlEscape(value))
                            .append("</t></is></c>");
                }
                sheet.append("</row>");
            }

            sheet.append("</sheetData></worksheet>");
            putZipEntry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
    }

    private void putZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String excelColumn(int index) {
        StringBuilder out = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            out.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return out.toString();
    }

    private String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
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
            webView.removeJavascriptInterface("MAUSecurity");
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
