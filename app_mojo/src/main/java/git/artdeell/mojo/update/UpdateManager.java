package git.artdeell.mojo.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;

/**
 * Asks the update API (Node.js on Wispbyte, data in Supabase, APK in Backblaze B2) if a newer build exists,
 * downloads the APK and starts the install.
 *
 * Android does not let an app install itself without a confirmation of the user, so "auto update"
 * means: the API is asked at the start of the launcher, the APK is downloaded in the background
 * when the device is on an unmetered network, and the user only has to confirm the install.
 */
public final class UpdateManager {
    private static final String TAG = "UpdateManager";

    public static final String PREF_AUTO_CHECK = "autoUpdateCheck";
    private static final String PREF_LAST_CHECK = "updateLastCheck";
    static final String PREF_SKIPPED_CODE = "updateSkippedCode";
    private static final String PREF_PENDING_CODE = "updatePendingCode";
    private static final String PREF_READY_CODE = "updateReadyCode";

    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final String APK_NAME = "Nokor-update.apk";

    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private UpdateManager() {}

    /** What the API says about the newest version */
    static final class Info {
        long code;
        String version = "";
        String notes = "";
        String uri;
        String sha256 = "";
        long size;
        boolean mandatory;
    }

    /** The downloaded file does not match the checksum from the API */
    static final class ChecksumException extends IOException {
        ChecksumException() { super("Checksum mismatch"); }
    }

    interface ProgressCallback {
        void onProgress(long done, long total, long bytesPerSecond);
    }

    /** Called when the launcher starts. Checks only when the user allows it, and not more than every few hours. */
    public static void checkOnStartup(@NonNull Activity activity) {
        if (isStoreBuild()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        cleanupInstalled(activity, prefs);
        if (!prefs.getBoolean(PREF_AUTO_CHECK, true)) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        check(activity, false);
    }

    /** The "Check for updates" button of the settings */
    public static void checkNow(@NonNull Activity activity) {
        if (isStoreBuild()) {
            Toast.makeText(activity, R.string.update_store_build, Toast.LENGTH_LONG).show();
            return;
        }
        check(activity, true);
    }

    private static boolean isStoreBuild() {
        // The store updates its own apps, and it does not allow an app to install another apk
        return BuildConfig.BUILD_TYPE.equals("gplay");
    }

    private static void check(@NonNull Activity activity, boolean manual) {
        String apiUrl = activity.getString(R.string.update_api_url).trim();
        if (apiUrl.isEmpty() || apiUrl.contains("YOUR_")) {
            if (manual) Toast.makeText(activity, R.string.update_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        if (!sBusy.compareAndSet(false, true)) return;
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();

        MojoApplication.sExecutorService.execute(() -> {
            try {
                Info info = fetchInfo(apiUrl);
                PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                onInfo(activity, info, manual);
            } catch (Throwable e) {
                // The update check must never be able to close the launcher
                Log.w(TAG, "Update check failed", e);
                if (manual) runOnUi(activity, () ->
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show());
            } finally {
                sBusy.set(false);
            }
        });
    }

    private static void onInfo(Activity activity, @Nullable Info info, boolean manual) {
        if (info == null || info.uri == null || info.code <= BuildConfig.VERSION_CODE) {
            if (manual) runOnUi(activity, () ->
                    Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show());
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!manual && !info.mandatory && prefs.getLong(PREF_SKIPPED_CODE, -1L) == info.code) return;

        File apk = apkFile(activity);
        boolean ready = isDownloaded(activity, apk, info);
        // On Wi-Fi the file is fetched quietly, so the only thing left to do is to confirm the install
        if (!ready && !manual && !isMetered(activity)) {
            ready = downloadQuietly(activity, info, apk);
        }
        final boolean readyFinal = ready;
        runOnUi(activity, () -> UpdateDialog.show(activity, info, readyFinal, manual));
    }

    // ---- API and download ----

    @Nullable
    private static Info fetchInfo(String apiUrl) throws Exception {
        if (!apiUrl.startsWith("https://")) throw new IOException("The update API must use https");
        String base = apiUrl.replaceAll("/+$", "");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String url = base + "/api/v1/update/check?versionCode=" + BuildConfig.VERSION_CODE
                + "&abi=" + URLEncoder.encode(abi, "UTF-8") + "&channel=release";

        HttpURLConnection connection = open(url);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            String body = org.apache.commons.io.IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
            return parseInfo(body, base);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "NokorLauncher/" + BuildConfig.VERSION_NAME);
        return connection;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Info parseInfo(String json, String base) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!root.has("update_available") || !root.get("update_available").getAsBoolean()) return null;
        if (!root.has("latest") || !root.get("latest").isJsonObject()) return null;
        JsonObject latest = root.getAsJsonObject("latest");

        Info info = new Info();
        info.code = longOf(latest, "version_code", 0L);
        info.version = stringOf(latest, "version_name");
        info.notes = stringOf(latest, "changelog").trim();
        info.sha256 = stringOf(latest, "sha256").toLowerCase(Locale.ROOT);
        info.size = longOf(latest, "size", 0L);
        info.mandatory = root.has("mandatory") && root.get("mandatory").getAsBoolean();

        String uri = stringOf(latest, "download_url");
        if (uri.startsWith("/")) uri = base + uri;
        // Only https, a plain http link could be swapped on the way
        info.uri = uri.startsWith("https://") ? uri : null;
        return info;
    }

    private static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    /** Downloads the APK into the cache. Returns false when the user cancelled. */
    static boolean download(Context context, Info info, File target, AtomicBoolean cancelled,
                            @Nullable ProgressCallback callback) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("No cache folder");
        File part = new File(target.getPath() + ".part");
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_READY_CODE).apply();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = open(info.uri);
        try {
            // The API answers with a redirect to a fresh signed Backblaze link, the connection follows it
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            long total = connection.getContentLength() > 0 ? connection.getContentLength() : info.size;
            long done = 0;
            long startedAt = System.nanoTime();
            long lastReport = 0L;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled.get()) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return false;
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    long now = System.nanoTime();
                    if (callback != null && now - lastReport > 200_000_000L) {
                        lastReport = now;
                        double seconds = Math.max((now - startedAt) / 1e9, 0.001);
                        callback.onProgress(done, total, (long) (done / seconds));
                    }
                }
            }
            if (info.size > 0 && part.length() != info.size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("Wrong size of the file: " + part.length());
            }
            if (!info.sha256.isEmpty() && !toHex(digest.digest()).equals(info.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new ChecksumException();
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!part.renameTo(target)) throw new IOException("Could not move the file");
            PreferenceManager.getDefaultSharedPreferences(context).edit().putLong(PREF_READY_CODE, info.code).apply();
            if (callback != null) callback.onProgress(done, total, 0L);
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean downloadQuietly(Context context, Info info, File target) {
        try {
            return download(context, info, target, new AtomicBoolean(false), null);
        } catch (Exception e) {
            Log.w(TAG, "Quiet download of the update failed", e);
            return false;
        }
    }

    static void install(Activity activity, Info info, File apk) {
        if (!apk.isFile()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            // The file stays here, the next press of the button installs it without downloading again
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putLong(PREF_PENDING_CODE, info.code).apply();
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start the installer", e);
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---- small helpers ----

    static File apkFile(Context context) {
        return new File(new File(context.getCacheDir(), "updates"), APK_NAME);
    }

    /** The file is whole and it was checked against the checksum when it was downloaded */
    private static boolean isDownloaded(Context context, File apk, Info info) {
        if (!apk.isFile() || info.size <= 0 || apk.length() != info.size) return false;
        return PreferenceManager.getDefaultSharedPreferences(context).getLong(PREF_READY_CODE, -1L) == info.code;
    }

    private static boolean isMetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager == null || manager.isActiveNetworkMetered();
    }

    /** Removes the downloaded file once the launcher has been updated to (or past) that version */
    private static void cleanupInstalled(Context context, SharedPreferences prefs) {
        long pending = prefs.getLong(PREF_PENDING_CODE, -1L);
        if (pending < 0 || BuildConfig.VERSION_CODE < pending) return;
        //noinspection ResultOfMethodCallIgnored
        apkFile(context).delete();
        prefs.edit().remove(PREF_PENDING_CODE).remove(PREF_READY_CODE).apply();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    static void runOnUi(Activity activity, Runnable runnable) {
        activity.runOnUiThread(runnable);
    }
}
