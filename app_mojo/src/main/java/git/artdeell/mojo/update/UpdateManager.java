package git.artdeell.mojo.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.io.FileInputStream;
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
    private static final String PREF_READY_INFO = "updateReadyInfo";
    private static final String PREF_PART_CODE = "updatePartCode";
    private static final String PREF_DELTA_PART_CODE = "updateDeltaPartCode";
    private static final int DELTA_DONE = 1;
    private static final int DELTA_CANCELLED = 0;
    private static final int DELTA_FALLBACK = -1;
    private static final long RETRY_DELAY_MS = 2000L;

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
        /** The small file that turns the installed APK into the new one, null when the API has none for this version */
        @Nullable DeltaInfo delta;
    }

    /** Only the files that changed or are new, see tools/make_delta.py */
    static final class DeltaInfo {
        String uri;
        long size;
        String sha256 = "";
        String baseSha256 = "";
        long baseSize;
    }

    /** The downloaded file does not match the checksum from the API */
    static final class ChecksumException extends IOException {
        ChecksumException() { super("Checksum mismatch"); }
    }

    /** The phone has no room for the file, waiting for the internet would not help */
    static final class StorageException extends IOException {
        StorageException(IOException cause) { super("No room to save the update", cause); }
    }

    /** The server answered with an error code */
    static final class HttpStatusException extends IOException {
        final int code;
        HttpStatusException(int code) { super("HTTP " + code); this.code = code; }
    }

    interface ProgressCallback {
        void onProgress(long done, long total, long bytesPerSecond);

        /** The internet is gone or too slow: the download waits and goes on by itself, true while it waits */
        default void onWaiting(boolean waiting) {}
    }

    /** Called when the launcher starts. Checks only when the user allows it, and not more than every few hours. */
    public static void checkOnStartup(@NonNull Activity activity) {
        if (isStoreBuild()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        cleanupInstalled(activity, prefs);
        // A finished download that was not installed yet comes back, with no internet needed
        if (showReadyUpdate(activity, prefs)) return;
        if (!prefs.getBoolean(PREF_AUTO_CHECK, true)) return;
        // Check on every launcher startup so a newly published version is noticed promptly.
        check(activity, false);
    }

    /** Shows the install window again for an update that is downloaded and waits for the install */
    private static boolean showReadyUpdate(Activity activity, SharedPreferences prefs) {
        long readyCode = prefs.getLong(PREF_READY_CODE, -1L);
        if (readyCode <= BuildConfig.VERSION_CODE) return false;
        Info info = loadReadyInfo(prefs);
        if (info == null || info.code != readyCode || !isDownloaded(activity, apkFile(activity), info)) return false;
        boolean installTried = prefs.getLong(PREF_PENDING_CODE, -1L) == readyCode;
        if (!installTried) {
            if (!prefs.getBoolean(PREF_AUTO_CHECK, true)) return false;
            if (!info.mandatory && prefs.getLong(PREF_SKIPPED_CODE, -1L) == readyCode) return false;
        }
        new Handler(Looper.getMainLooper()).post(() -> UpdateDialog.show(activity, info, true, false));
        return true;
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

        if (latest.has("delta") && latest.get("delta").isJsonObject()) {
            JsonObject d = latest.getAsJsonObject("delta");
            DeltaInfo delta = new DeltaInfo();
            String deltaUri = stringOf(d, "download_url");
            if (deltaUri.startsWith("/")) deltaUri = base + deltaUri;
            delta.uri = deltaUri;
            delta.size = longOf(d, "size", 0L);
            delta.sha256 = stringOf(d, "sha256").toLowerCase(Locale.ROOT);
            delta.baseSha256 = stringOf(d, "base_sha256").toLowerCase(Locale.ROOT);
            delta.baseSize = longOf(d, "base_size", 0L);
            if (deltaUri.startsWith("https://") && delta.size > 0 && delta.size < info.size
                    && !delta.sha256.isEmpty() && !delta.baseSha256.isEmpty() && delta.baseSize > 0) {
                info.delta = delta;
            }
        }
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

    /**
     * Downloads the APK. When the internet is gone or too slow the file that is there stays, and the download
     * goes on from where it stopped as soon as the connection is back. Returns false when the user cancelled.
     */
    static boolean download(Context context, Info info, File target, AtomicBoolean cancelled,
                            @Nullable ProgressCallback callback) throws Exception {
        return download(context, info, target, cancelled, callback, callback != null);
    }

    private static boolean download(Context context, Info info, File target, AtomicBoolean cancelled,
                                    @Nullable ProgressCallback callback, boolean keepTrying) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(PREF_READY_CODE).remove(PREF_READY_INFO).apply();

        // Only the files that changed or are new are fetched when the API has an update file for this version
        if (info.delta != null) {
            int result = downloadWithDelta(context, info, target, cancelled, callback, keepTrying);
            if (result == DELTA_CANCELLED) return false;
            if (result == DELTA_FALLBACK) {
                // The whole apk is the way that always works
                if (!fetchVerified(context, info, target, cancelled, callback, keepTrying, PREF_PART_CODE)) return false;
            }
        } else if (!fetchVerified(context, info, target, cancelled, callback, keepTrying, PREF_PART_CODE)) {
            return false;
        }
        prefs.edit().putLong(PREF_READY_CODE, info.code).putString(PREF_READY_INFO, infoToJson(info)).apply();
        if (callback != null) callback.onProgress(target.length(), target.length(), 0L);
        return true;
    }

    /**
     * Downloads the small update file and builds the new APK from it and the installed APK.
     * Anything that goes wrong here (other base apk, damaged file, no such update file) is not an error of the
     * update itself: the caller then downloads the whole APK.
     */
    private static int downloadWithDelta(Context context, Info info, File target, AtomicBoolean cancelled,
                                         @Nullable ProgressCallback callback, boolean keepTrying) throws StorageException {
        DeltaInfo delta = info.delta;
        File deltaFile = new File(target.getPath() + ".delta");
        File built = new File(target.getPath() + ".new");
        try {
            File base = baseApk(context);
            if (base == null || base.length() != delta.baseSize || !sha256Of(base).equals(delta.baseSha256)) {
                Log.i(TAG, "The installed apk is not the base of the update file");
                return DELTA_FALLBACK;
            }
            Info source = new Info();
            source.code = info.code;
            source.uri = delta.uri;
            source.size = delta.size;
            source.sha256 = delta.sha256;
            if (!fetchVerified(context, source, deltaFile, cancelled, callback, keepTrying, PREF_DELTA_PART_CODE)) {
                return DELTA_CANCELLED;
            }
            File dir = target.getParentFile();
            if (info.size > 0 && dir != null && dir.getUsableSpace() < info.size) {
                throw new StorageException(new IOException("Not enough free space"));
            }
            DeltaPatcher.apply(base, deltaFile, built, info.sha256, info.size);
            //noinspection ResultOfMethodCallIgnored
            deltaFile.delete();
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!built.renameTo(target)) throw new IOException("Could not move the file");
            return DELTA_DONE;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "The update file did not work, the whole apk is downloaded", e);
            //noinspection ResultOfMethodCallIgnored
            built.delete();
            //noinspection ResultOfMethodCallIgnored
            deltaFile.delete();
            return DELTA_FALLBACK;
        }
    }

    /** The installed APK, null for a split install that can not be the base of an update file */
    @Nullable
    private static File baseApk(Context context) {
        android.content.pm.ApplicationInfo app = context.getApplicationInfo();
        if (app.splitSourceDirs != null && app.splitSourceDirs.length > 0) return null;
        File base = new File(app.sourceDir);
        return base.isFile() ? base : null;
    }

    /** Downloads a file (it goes on from where it stopped), checks size and checksum, and puts it at the target */
    private static boolean fetchVerified(Context context, Info info, File target, AtomicBoolean cancelled,
                                         @Nullable ProgressCallback callback, boolean keepTrying,
                                         String partPref) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("No folder for the update");
        File part = new File(target.getPath() + ".part");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // A half file of another version is of no use
        if (prefs.getLong(partPref, -1L) != info.code) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        prefs.edit().putLong(partPref, info.code).apply();

        boolean resumed = part.isFile() && part.length() > 0;
        while (true) {
            if (!fetchAll(context, info, part, cancelled, callback, keepTrying)) return false;
            if (info.size > 0 && part.length() != info.size) {
                long length = part.length();
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("Wrong size of the file: " + length);
            }
            if (info.sha256.isEmpty() || sha256Of(part).equals(info.sha256)) break;
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            // The half file from before could be the damaged part, so once more from the start
            if (resumed) {
                resumed = false;
                continue;
            }
            throw new ChecksumException();
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!part.renameTo(target)) throw new IOException("Could not move the file");
        prefs.edit().remove(partPref).apply();
        return true;
    }

    /** Fetches what is missing of the file, again and again until it is whole. False when cancelled. */
    private static boolean fetchAll(Context context, Info info, File part, AtomicBoolean cancelled,
                                    @Nullable ProgressCallback callback, boolean keepTrying) throws IOException {
        while (true) {
            if (cancelled.get()) return false;
            IOException failure;
            try {
                return fetchRange(info, part, cancelled, callback);
            } catch (StorageException e) {
                throw e;
            } catch (HttpStatusException e) {
                if (!isRetryable(e.code)) throw e;
                failure = e;
            } catch (IOException e) {
                failure = e;
            }
            Log.w(TAG, "The download stopped, it goes on from where it was", failure);
            if (!keepTrying) throw failure;
            if (callback != null) callback.onWaiting(true);
            waitBeforeRetry(context, cancelled);
        }
    }

    private static boolean isRetryable(int code) {
        return code >= 500 || code == 408 || code == 425 || code == 429;
    }

    /** Waits a moment, and as long as the phone has no internet at all */
    private static void waitBeforeRetry(Context context, AtomicBoolean cancelled) {
        long until = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
        while (!cancelled.get()) {
            if (isOnline(context) && SystemClock.elapsedRealtime() >= until) return;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled.set(true);
                return;
            }
        }
    }

    /** One connection: asks only for the bytes after the ones that are already in the file */
    private static boolean fetchRange(Info info, File part, AtomicBoolean cancelled,
                                      @Nullable ProgressCallback callback) throws IOException {
        long offset = part.isFile() ? part.length() : 0L;
        if (info.size > 0 && offset > info.size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            offset = 0L;
        }
        if (info.size > 0 && offset == info.size) return true;

        HttpURLConnection connection = open(info.uri);
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            // The API answers with a redirect to a fresh signed Backblaze link, the connection follows it
            int status = connection.getResponseCode();
            boolean append;
            if (status == 206) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + offset + "-")) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IOException("The server answered with other bytes than asked");
                }
                append = true;
            } else if (status == 200) {
                // The server does not do ranges: the file starts again
                append = false;
            } else if (status == 416) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("HTTP 416");
            } else {
                throw new HttpStatusException(status);
            }

            long base = append ? offset : 0L;
            long length = connection.getContentLength();
            long total = info.size > 0 ? info.size : (length > 0 ? length + base : 0L);
            File dir = part.getParentFile();
            long needed = total > 0 ? total - base : 0L;
            if (needed > 0 && dir != null && dir.getUsableSpace() < needed) {
                throw new StorageException(new IOException("Not enough free space"));
            }
            if (callback != null) callback.onWaiting(false);

            InputStream in = connection.getInputStream();
            OutputStream out;
            try {
                out = new FileOutputStream(part, append);
            } catch (IOException e) {
                in.close();
                throw new StorageException(e);
            }
            long done = base;
            long startedAt = System.nanoTime();
            long lastReport = 0L;
            try (InputStream input = in; OutputStream output = out) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    // The half file stays, so the next start goes on from it
                    if (cancelled.get()) return false;
                    try {
                        output.write(buffer, 0, read);
                    } catch (IOException e) {
                        throw new StorageException(e);
                    }
                    done += read;
                    long now = System.nanoTime();
                    if (callback != null && now - lastReport > 200_000_000L) {
                        lastReport = now;
                        double seconds = Math.max((now - startedAt) / 1e9, 0.001);
                        callback.onProgress(done, total, (long) ((done - base) / seconds));
                    }
                }
            }
            if (total > 0 && done < total) throw new IOException("The connection closed before the end");
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    private static boolean downloadQuietly(Context context, Info info, File target) {
        try {
            // No waiting for the internet here: what is downloaded stays, the window goes on with it later
            return download(context, info, target, new AtomicBoolean(false), null, false);
        } catch (Exception e) {
            Log.w(TAG, "Quiet download of the update failed", e);
            return false;
        }
    }

    private static String infoToJson(Info info) {
        JsonObject o = new JsonObject();
        o.addProperty("code", info.code);
        o.addProperty("version", info.version);
        o.addProperty("notes", info.notes);
        o.addProperty("uri", info.uri);
        o.addProperty("sha256", info.sha256);
        o.addProperty("size", info.size);
        o.addProperty("mandatory", info.mandatory);
        return o.toString();
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Info loadReadyInfo(SharedPreferences prefs) {
        String json = prefs.getString(PREF_READY_INFO, null);
        if (json == null) return null;
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            Info info = new Info();
            info.code = longOf(o, "code", 0L);
            info.version = stringOf(o, "version");
            info.notes = stringOf(o, "notes");
            info.uri = stringOf(o, "uri");
            info.sha256 = stringOf(o, "sha256");
            info.size = longOf(o, "size", 0L);
            info.mandatory = o.has("mandatory") && o.get("mandatory").getAsBoolean();
            return info;
        } catch (RuntimeException e) {
            return null;
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
        // Not the cache folder: the cleaners of the phone empty it, and the downloaded file must survive them
        return new File(new File(context.getFilesDir(), "updates"), APK_NAME);
    }

    /** The file is whole and it was checked against the checksum when it was downloaded */
    private static boolean isDownloaded(Context context, File apk, Info info) {
        if (!apk.isFile() || apk.length() == 0) return false;
        if (info.size > 0 && apk.length() != info.size) return false;
        return PreferenceManager.getDefaultSharedPreferences(context).getLong(PREF_READY_CODE, -1L) == info.code;
    }

    @SuppressWarnings("deprecation")
    private static boolean isOnline(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo net = manager.getActiveNetworkInfo();
            return net != null && net.isConnected();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean isMetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager == null || manager.isActiveNetworkMetered();
    }

    /** Removes the downloaded file once the launcher has been updated to (or past) that version */
    private static void cleanupInstalled(Context context, SharedPreferences prefs) {
        long pending = prefs.getLong(PREF_PENDING_CODE, -1L);
        long ready = prefs.getLong(PREF_READY_CODE, -1L);
        boolean installedPending = pending >= 0 && BuildConfig.VERSION_CODE >= pending;
        boolean installedReady = ready >= 0 && BuildConfig.VERSION_CODE >= ready;
        if (!installedPending && !installedReady) return;
        File apk = apkFile(context);
        for (String suffix : new String[]{"", ".part", ".delta", ".delta.part", ".new"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(apk.getPath() + suffix).delete();
        }
        prefs.edit().remove(PREF_PENDING_CODE).remove(PREF_READY_CODE).remove(PREF_READY_INFO)
                .remove(PREF_PART_CODE).remove(PREF_DELTA_PART_CODE).apply();
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
