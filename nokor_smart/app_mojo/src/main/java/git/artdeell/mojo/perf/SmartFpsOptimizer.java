package git.artdeell.mojo.perf;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Selects a conservative Minecraft profile before each launch using hardware capability.
 * This is launch-time adaptation, not live FPS telemetry: the launcher cannot read the game's
 * in-world frame rate from this process reliably.
 */
public final class SmartFpsOptimizer {
    private static final String TAG = "SmartFpsOptimizer";
    public static final String PREF_ENABLED = "nokor_smart_fps_enabled";
    private static final String BACKUP_PRESENT = "nokor_smart_fps_backup_present";
    private static final String BACKUP_RESOLUTION = "nokor_smart_fps_backup_resolution";

    public enum Tier { LOW, BALANCED, HIGH, ULTRA }

    public static final class Profile {
        public final Tier tier;
        public final int renderDistance;
        public final int resolutionPercent;
        public final String label;

        private Profile(Tier tier, int renderDistance, int resolutionPercent, String label) {
            this.tier = tier;
            this.renderDistance = renderDistance;
            this.resolutionPercent = resolutionPercent;
            this.label = label;
        }
    }

    private SmartFpsOptimizer() {}

    @NonNull
    public static Profile chooseProfile(@NonNull DeviceProfile device) {
        // RAM, CPU and GLES are used together; unknown GPU data never grants the highest tier.
        if (device.is32Bit || device.totalRamMb < 3072 || device.cores <= 4) {
            return new Profile(Tier.LOW, 4, 60, "Low");
        }
        if (device.totalRamMb >= 8192 && device.cores >= 8 && device.glesMajor >= 3
                && !device.isOldAdreno()) {
            return new Profile(Tier.ULTRA, 10, 100, "Ultra");
        }
        if (device.totalRamMb >= 6144 && device.cores >= 8 && device.glesMajor >= 3) {
            return new Profile(Tier.HIGH, 8, 90, "High");
        }
        return new Profile(Tier.BALANCED, 6, 75, "Balanced");
    }

    /** Applies the selected profile only when the player enabled Smart FPS. */
    public static boolean applyIfEnabled(@NonNull File gameDir,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull DeviceProfile device) {
        if (!prefs.getBoolean(PREF_ENABLED, false)) return false;
        Profile profile = chooseProfile(device);
        saveResolutionBackupOnce(prefs);
        boolean optionsApplied = MinecraftOptionsOptimizer.apply(gameDir, profile.renderDistance);
        boolean resolutionApplied = prefs.edit().putInt("resolutionRatio", profile.resolutionPercent).commit();
        Log.i(TAG, "Selected " + profile.label + " profile: renderDistance="
                + profile.renderDistance + ", resolution=" + profile.resolutionPercent + "%");
        return optionsApplied || resolutionApplied;
    }

    public static boolean hasBackup(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(BACKUP_PRESENT, false);
    }

    /** Restores the launcher resolution setting saved before the first Smart FPS run. */
    public static void restore(@NonNull SharedPreferences prefs) {
        if (!prefs.getBoolean(BACKUP_PRESENT, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.contains(BACKUP_RESOLUTION)) {
            editor.putInt("resolutionRatio", prefs.getInt(BACKUP_RESOLUTION, 100));
        } else {
            editor.remove("resolutionRatio");
        }
        editor.remove(BACKUP_RESOLUTION).remove(BACKUP_PRESENT).apply();
    }

    private static void saveResolutionBackupOnce(SharedPreferences prefs) {
        if (prefs.getBoolean(BACKUP_PRESENT, false)) return;
        SharedPreferences.Editor editor = prefs.edit().putBoolean(BACKUP_PRESENT, true);
        if (prefs.contains("resolutionRatio")) {
            editor.putInt(BACKUP_RESOLUTION, prefs.getInt("resolutionRatio", 100));
        }
        editor.commit();
    }
}
