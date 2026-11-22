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
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;

/**
 * Checks the update feed (update/latest_version_md.json), downloads the new APK and starts the install.
 *
 * Android does not let an app install itself without a confirmation of the user, so "auto update"
 * means: the feed is checked at the start of the launcher, the APK is downloaded in the background
 * when the device is on an unmetered network, and the user only has to confirm the install.
 */
public final class UpdateManager {
    private static final String TAG = "UpdateManager";

    public static final String PREF_AUTO_CHECK = "autoUpdateCheck";
    private static final String PREF_LAST_CHECK = "updateLastCheck";
    private static final String PREF_SKIPPED_CODE = "updateSkippedCode";
    private static final String PREF_PENDING_CODE = "updatePendingCode";

    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int TIMEOUT_MS = 15000;
    private static final String APK_NAME = "Nokor-update.apk";

    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private UpdateManager() {}

    /** What the feed says about the newest version */
    private static final class Info {
        long code;
        String version = "";
        String notes = "";
        String uri;
        long size;
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
        String feedUrl = activity.getString(R.string.update_feed_url).trim();
        if (feedUrl.isEmpty() || feedUrl.contains("YOUR_")) {
            if (manual) Toast.makeText(activity, R.string.update_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        if (!sBusy.compareAndSet(false, true)) return;
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();

        MojoApplication.sExecutorService.execute(() -> {
            try {
                Info info = fetchInfo(feedUrl);
                PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                onInfo(activity, info, manual);
            } catch (Exception e) {
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
        if (!manual && prefs.getLong(PREF_SKIPPED_CODE, -1L) == info.code) return;

        File apk = apkFile(activity);
        boolean ready = isDownloaded(apk, info);
        // On Wi-Fi the file is fetched quietly, so the only thing left to do is to confirm the install
        if (!ready && !manual && !isMetered(activity)) {
            ready = downloadQuietly(activity, info, apk);
        }
        final boolean readyFinal = ready;
        runOnUi(activity, () -> showUpdateDialog(activity, info, readyFinal, manual));
    }

    private static void showUpdateDialog(Activity activity, Info info, boolean ready, boolean manual) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        StringBuilder message = new StringBuilder();
        message.append(activity.getString(R.string.update_message_versions, info.version, BuildConfig.VERSION_NAME));
        if (info.size > 0) {
            message.append('\n').append(activity.getString(R.string.update_message_size, info.size / (1024f * 1024f)));
        }
        if (!info.notes.isEmpty()) message.append("\n\n").append(info.notes);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message.toString())
                .setPositiveButton(ready ? R.string.update_action_install : R.string.update_action_download,
                        (d, w) -> startUpdate(activity, info, ready))
                .setNegativeButton(R.string.update_action_later, null);
        if (!manual) {
            builder.setNeutralButton(R.string.update_action_skip, (d, w) ->
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                            .putLong(PREF_SKIPPED_CODE, info.code).apply());
        }
        builder.show();
    }

    private static void startUpdate(Activity activity, Info info, boolean ready) {
        File apk = apkFile(activity);
        if (ready) {
            install(activity, info, apk);
            return;
        }
        // The dialog with a progress bar, the download runs in the background
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(info.size <= 0);
        TextView text = new TextView(activity);
        text.setText(R.string.update_downloading);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
                .show();

        MojoApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                ok = download(activity, info, apk, cancelled, percent -> runOnUi(activity, () -> {
                    bar.setIndeterminate(false);
                    bar.setProgress(percent);
                }));
            } catch (Exception e) {
                Log.w(TAG, "Update download failed", e);
                ok = false;
            }
            final boolean success = ok;
            runOnUi(activity, () -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) dialog.dismiss();
                if (success) install(activity, info, apk);
                else if (!cancelled.get()) Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private static void install(Activity activity, Info info, File apk) {
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

    // ---- feed and download ----

    @Nullable
    private static Info fetchInfo(String feedUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(feedUrl).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "NokorLauncher/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            String body = org.apache.commons.io.IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
            return parseInfo(body);
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Info parseInfo(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!root.has("code")) return null;
        Info info = new Info();
        info.code = root.get("code").getAsLong();
        if (root.has("version") && !root.get("version").isJsonNull()) info.version = root.get("version").getAsString();
        if (root.has("default_body") && root.get("default_body").isJsonObject()) {
            JsonObject body = root.getAsJsonObject("default_body");
            if (body.has("markdown") && !body.get("markdown").isJsonNull()) info.notes = body.get("markdown").getAsString().trim();
        }
        if (root.has("files") && root.get("files").isJsonArray()) {
            JsonObject chosen = chooseFile(root.getAsJsonArray("files"));
            if (chosen != null) {
                if (chosen.has("uri")) info.uri = chosen.get("uri").getAsString();
                if (chosen.has("size") && !chosen.get("size").isJsonNull()) info.size = chosen.get("size").getAsLong();
            }
        }
        return info;
    }

    /** Takes the file of the architecture of the device, or the universal one */
    @Nullable
    private static JsonObject chooseFile(JsonArray files) {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String wanted = abi.equals("arm64-v8a") ? "arm64" : abi.equals("armeabi-v7a") ? "arm" : "all";
        JsonObject universal = null, first = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (!file.has("uri")) continue;
            if (first == null) first = file;
            String arch = file.has("arch") ? file.get("arch").getAsString() : "all";
            if (arch.equals(wanted)) return file;
            if (arch.equals("all")) universal = file;
        }
        return universal != null ? universal : first;
    }

    private interface ProgressCallback { void onProgress(int percent); }

    private static boolean download(Context context, Info info, File target, AtomicBoolean cancelled,
                                    @Nullable ProgressCallback callback) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("No cache folder");
        File part = new File(target.getPath() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(info.uri).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "NokorLauncher/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            long total = connection.getContentLength() > 0 ? connection.getContentLength() : info.size;
            long done = 0;
            int lastPercent = -1;
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
                    done += read;
                    if (callback != null && total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent);
                        }
                    }
                }
            }
            if (info.size > 0 && part.length() != info.size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new java.io.IOException("Wrong size of the file: " + part.length());
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!part.renameTo(target)) throw new java.io.IOException("Could not move the file");
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

    // ---- small helpers ----

    private static File apkFile(Context context) {
        return new File(new File(context.getCacheDir(), "updates"), APK_NAME);
    }

    private static boolean isDownloaded(File apk, Info info) {
        return apk.isFile() && info.size > 0 && apk.length() == info.size;
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
        prefs.edit().remove(PREF_PENDING_CODE).apply();
    }

    private static void runOnUi(Activity activity, Runnable runnable) {
        activity.runOnUiThread(runnable);
    }
}
