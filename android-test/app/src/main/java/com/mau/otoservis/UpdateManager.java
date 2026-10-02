package com.mau.otoservis;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateManager {
    private static final String UPDATE_MANIFEST_URL =
            "https://masafotomotiv-glitch.github.io/MAU-Oto-Servis/update.json";
    private static final String PREFS = "mau_updater";
    private static final String PREF_PENDING_APK = "pending_update_apk";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void checkForUpdates() {
        if (!checking.compareAndSet(false, true)) {
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject info = fetchJson(
                        UPDATE_MANIFEST_URL + "?v=" + System.currentTimeMillis()
                );

                if (!info.optBoolean("enabled", false)) {
                    return;
                }

                int remoteCode = info.optInt("versionCode", 0);
                if (remoteCode <= getCurrentVersionCode()) {
                    return;
                }

                String versionName = info.optString("versionName", "").trim();
                String apkUrl = info.optString("apkUrl", "").trim();
                String sha256 = info.optString("sha256", "").trim().toLowerCase(Locale.ROOT);

                if (!apkUrl.startsWith("https://") || !sha256.matches("[0-9a-f]{64}")) {
                    showToast("Güncelleme bilgisi geçersiz. Kurulum yapılmadı.");
                    return;
                }

                showToast("MAU Oto Servis " +
                        (versionName.isEmpty() ? "yeni sürüm" : versionName) +
                        " indiriliyor...");

                File apk = downloadAndVerify(apkUrl, sha256, remoteCode);
                if (apk == null) {
                    return;
                }

                activity.runOnUiThread(() -> prepareInstall(apk));
            } catch (Exception ignored) {
                // İnternet yoksa veya güncelleme servisine ulaşılamazsa
                // uygulama normal şekilde çalışmaya devam eder.
            } finally {
                checking.set(false);
            }
        });
    }

    public void resumePendingInstallIfAllowed() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String path = prefs.getString(PREF_PENDING_APK, "");
        if (path == null || path.isEmpty()) {
            return;
        }

        File apk = new File(path);
        if (!apk.exists() || apk.length() <= 0) {
            prefs.edit().remove(PREF_PENDING_APK).apply();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }

        prefs.edit().remove(PREF_PENDING_APK).apply();
        commitInstall(apk);
    }

    private long getCurrentVersionCode() throws Exception {
        android.content.pm.PackageInfo info = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        //noinspection deprecation
        return info.versionCode;
    }

    private JSONObject fetchJson(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }

            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                byte[] data = readAll(in, 512 * 1024);
                return new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private File downloadAndVerify(String apkUrl, String expectedSha, int remoteCode) {
        HttpURLConnection connection = null;
        File temp = null;

        try {
            File base = activity.getExternalFilesDir(null);
            if (base == null) {
                base = activity.getFilesDir();
            }

            File dir = new File(base, "updates");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Güncelleme klasörü oluşturulamadı.");
            }

            temp = new File(dir, "MAU-Oto-Servis-" + remoteCode + ".apk.part");
            File finalApk = new File(dir, "MAU-Oto-Servis-" + remoteCode + ".apk");

            connection = (HttpURLConnection) new URL(apkUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("APK indirilemedi: HTTP " + status);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[65536];
                int read;
                long total = 0;

                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }

                out.flush();

                if (total < 100000) {
                    throw new IllegalStateException("İndirilen APK beklenenden küçük.");
                }
            }

            String actualSha = toHex(digest.digest());
            if (!actualSha.equalsIgnoreCase(expectedSha)) {
                throw new SecurityException("APK SHA-256 doğrulaması başarısız.");
            }

            if (finalApk.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalApk.delete();
            }

            if (!temp.renameTo(finalApk)) {
                copyFile(temp, finalApk);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }

            return finalApk;
        } catch (Exception e) {
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            showToast("Güncelleme indirilemedi/doğrulanamadı: " +
                    (e.getMessage() == null ? "hata" : e.getMessage()));
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void prepareInstall(File apk) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        prefs.edit().putString(PREF_PENDING_APK, apk.getAbsolutePath()).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(
                    activity,
                    "İlk güncelleme için MAU Oto Servis'e uygulama yükleme izni verin.",
                    Toast.LENGTH_LONG
            ).show();

            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(intent);
            return;
        }

        prefs.edit().remove(PREF_PENDING_APK).apply();
        commitInstall(apk);
    }

    private void commitInstall(File apk) {
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            int sessionId = installer.createSession(params);

            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(apk));
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {

                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }

                Intent resultIntent = new Intent(activity, UpdateInstallReceiver.class);
                resultIntent.setAction("com.mau.otoservis.UPDATE_INSTALL_RESULT");

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        activity,
                        sessionId,
                        resultIntent,
                        flags
                );

                session.commit(pendingIntent.getIntentSender());
            }
        } catch (Exception e) {
            showToast("Güncelleme kurulumu başlatılamadı: " +
                    (e.getMessage() == null ? "hata" : e.getMessage()));
        }
    }

    private void showToast(String message) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        );
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;

        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException("Yanıt çok büyük.");
            }
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
