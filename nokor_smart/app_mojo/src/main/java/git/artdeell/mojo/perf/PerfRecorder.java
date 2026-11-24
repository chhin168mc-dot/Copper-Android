package git.artdeell.mojo.perf;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Opt-in local report of how long the launcher needs to get the game started. The idea comes from
 * the performance reports of the Starling client: the file stays on the device, it holds no account,
 * chat or world names, it has a size limit and only the last few files are kept.
 *
 * <p>One file is written for every launch, one JSON object per line. The first line is the header
 * (schema, device, settings), the second one holds the time of each launcher-side stage. The time
 * between the click on Play and the start of the JVM is what the launcher can change. The time the
 * game itself needs after that is not measured here.
 */
public final class PerfRecorder {
    private static final String TAG = "PerfRecorder";
    public static final String PREF_KEY = "perfReport";

    private static final int SCHEMA = 1;
    private static final int MAX_FILES = 3;
    private static final long MAX_BYTES = 256 * 1024;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Long> sStages = new LinkedHashMap<>();
    private static long sStart = -1;
    private static boolean sActive;

    private PerfRecorder() {}

    public static boolean isEnabled() {
        return LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY, false);
    }

    /** Called when the launch starts. Does nothing when the recording is off. */
    public static void begin() {
        synchronized (LOCK) {
            sStages.clear();
            sActive = isEnabled();
            sStart = sActive ? SystemClock.elapsedRealtime() : -1;
        }
    }

    /** Remembers how long the launch is running when a stage ends */
    public static void mark(@NonNull String stage) {
        synchronized (LOCK) {
            if (!sActive) return;
            sStages.put(stage, SystemClock.elapsedRealtime() - sStart);
        }
    }

    /**
     * Writes the file of this launch. Call it right before the JVM takes over the process, the
     * process can end at any moment after that.
     */
    public static void finish(@NonNull DeviceProfile device, @NonNull Map<String, String> settings) {
        final LinkedHashMap<String, Long> stages;
        synchronized (LOCK) {
            if (!sActive) return;
            sActive = false;
            stages = new LinkedHashMap<>(sStages);
        }
        try {
            File dir = reportDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            trim(dir);

            JsonObject header = new JsonObject();
            header.addProperty("type", "header");
            header.addProperty("schema", SCHEMA);
            header.addProperty("abi", device.arch);
            header.addProperty("cores", device.cores);
            header.addProperty("ram_total_mb", device.totalRamMb);
            header.addProperty("ram_free_mb", device.freeRamMb);
            JsonObject settingsJson = new JsonObject();
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                settingsJson.addProperty(entry.getKey(), entry.getValue());
            }
            header.add("settings", settingsJson);

            JsonObject timings = new JsonObject();
            timings.addProperty("type", "launch");
            long total = 0;
            JsonObject stageJson = new JsonObject();
            for (Map.Entry<String, Long> entry : stages.entrySet()) {
                stageJson.addProperty(entry.getKey(), entry.getValue());
                total = Math.max(total, entry.getValue());
            }
            timings.add("stages_ms", stageJson);
            timings.addProperty("total_ms", total);

            File out = new File(dir, "launch-" + System.currentTimeMillis() + ".jsonl");
            String text = header + "\n" + timings + "\n";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the performance report", e);
        }
    }

    @NonNull
    public static File reportDir() {
        return new File(Tools.DIR_DATA, "performance");
    }

    /** Keeps the newest MAX_FILES - 1 files so there is room for the new one */
    private static void trim(File dir) {
        File[] files = listReports(dir);
        for (int i = 0; i < files.length - (MAX_FILES - 1); i++) {
            if (!files[i].delete()) Log.w(TAG, "Could not delete " + files[i]);
        }
    }

    @NonNull
    private static File[] listReports(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("launch-") && name.endsWith(".jsonl"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    public static void clear() {
        for (File file : listReports(reportDir())) {
            if (!file.delete()) Log.w(TAG, "Could not delete " + file);
        }
    }

    /**
     * A readable summary of the saved launches, the newest first, each one compared to the launch
     * before it. Change one setting at a time between two launches to see what it did.
     */
    @NonNull
    public static String summarize() {
        File[] files = listReports(reportDir());
        if (files.length == 0) return "";
        List<long[]> totals = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (File file : files) {
            JsonObject launch = readLaunchLine(file);
            long total = launch == null || !launch.has("total_ms") ? -1 : launch.get("total_ms").getAsLong();
            totals.add(new long[]{total});
            StringBuilder line = new StringBuilder();
            line.append(total < 0 ? "?" : total + " ms");
            if (launch != null && launch.has("stages_ms")) {
                JsonObject stages = launch.getAsJsonObject("stages_ms");
                long previous = 0;
                for (Map.Entry<String, com.google.gson.JsonElement> entry : stages.entrySet()) {
                    long at = entry.getValue().getAsLong();
                    line.append("\n    ").append(entry.getKey()).append(": +").append(at - previous).append(" ms");
                    previous = at;
                }
            }
            lines.add(line.toString());
        }
        StringBuilder out = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            out.append(lines.size() - i).append(". ").append(lines.get(i));
            long total = totals.get(i)[0];
            if (i > 0 && total >= 0 && totals.get(i - 1)[0] >= 0) {
                long delta = total - totals.get(i - 1)[0];
                out.append("\n    ").append(delta > 0 ? "+" : "").append(delta).append(" ms against the launch before");
            }
            out.append("\n");
        }
        return out.toString().trim();
    }

    @Nullable
    private static JsonObject readLaunchLine(File file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                if (obj.has("type") && "launch".equals(obj.get("type").getAsString())) return obj;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read " + file, e);
        }
        return null;
    }
}
