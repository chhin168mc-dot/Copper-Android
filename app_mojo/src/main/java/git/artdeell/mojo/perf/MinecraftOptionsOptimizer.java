package git.artdeell.mojo.perf;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a conservative, opt-in Minecraft video profile.
 * Only video/performance keys are changed; controls, accounts, servers and worlds are untouched.
 * The original options.txt is backed up once per game directory and can be restored.
 */
public final class MinecraftOptionsOptimizer {
    private static final String TAG = "MCOptionsOptimizer";
    private static final String PREF_ENABLED = "nokor_mc_auto_optimize";
    private static final String BACKUP_NAME = "options.txt.nokor-backup";
    private static final String OPTIONS_NAME = "options.txt";

    private MinecraftOptionsOptimizer() {}

    public static boolean isEnabled(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    /** Applies only when the user enabled the setting. Safe to call on every game launch. */
    public static boolean applyIfEnabled(@NonNull File gameDir, @NonNull SharedPreferences prefs) {
        if (!isEnabled(prefs)) return false;
        return apply(gameDir);
    }

    /** Creates a backup before the first change; subsequent runs preserve that original backup. */
    public static boolean apply(@NonNull File gameDir) {
        return apply(gameDir, 6);
    }

    /** Applies the common conservative profile with a device-selected render distance. */
    public static boolean apply(@NonNull File gameDir, int renderDistance) {
        File options = new File(gameDir, OPTIONS_NAME);
        File backup = new File(gameDir, BACKUP_NAME);
        if (!options.isFile()) return false;
        try {
            if (!backup.exists() && !copy(options, backup)) return false;

            LinkedHashMap<String, String> values = readOptions(options);
            // Values are intentionally conservative and supported by common Java Edition versions.
            put(values, "graphics", "0");                 // Fast graphics
            put(values, "renderDistance", String.valueOf(Math.max(2, Math.min(12, renderDistance)))); // Adaptive chunk distance
            put(values, "simulationDistance", "5");       // Lower ticking distance (newer versions)
            put(values, "entityDistanceScaling", "0.75"); // Fewer distant entities
            put(values, "particles", "2");                // Decreased particles
            put(values, "clouds", "0");                   // Clouds off
            put(values, "biomeBlendRadius", "0");         // Less biome color blending
            put(values, "entityShadows", "false");
            put(values, "mipmapLevels", "2");
            put(values, "ao", "0");                       // Ambient occlusion off
            return writeOptions(options, values);
        } catch (Exception e) {
            Log.e(TAG, "Could not optimize Minecraft options", e);
            return false;
        }
    }

    /** Restores the original options.txt and removes the backup after a successful copy. */
    public static boolean restore(@NonNull File gameDir) {
        File options = new File(gameDir, OPTIONS_NAME);
        File backup = new File(gameDir, BACKUP_NAME);
        if (!backup.isFile()) return false;
        if (!copy(backup, options)) return false;
        // Keep the backup so the user can restore again after another optimization.
        return true;
    }

    private static void put(Map<String, String> map, String key, String value) {
        map.put(key, value);
    }

    private static LinkedHashMap<String, String> readOptions(File file) throws Exception {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf(':');
                if (split > 0) map.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return map;
    }

    private static boolean writeOptions(File target, Map<String, String> values) {
        File temp = new File(target.getParentFile(), OPTIONS_NAME + ".nokor-tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                out.write((entry.getKey() + ":" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "Could not write temporary options file", e);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            Log.e(TAG, "Could not replace options.txt with optimized file");
            return false;
        }
        return true;
    }

    private static boolean copy(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.getFD().sync();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not copy " + from.getName(), e);
            return false;
        }
    }
}
