package git.artdeell.mojo.perf;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Optimize game": works out which launcher settings do not suit this device and changes only
 * those, after the player confirmed. The previous values are saved, so one tap restores them.
 *
 * <p>Only settings that are safe to change are touched: memory, the render resolution on weak
 * devices, the sustained performance mode on devices with many cores, and the runtime options of
 * this change. The renderer, the experimental options and the Java arguments are never changed
 * here, a renderer that is switched behind the player's back is a common reason for a game that
 * does not start.
 */
public final class GameOptimizer {
    public static final String KEY_MEMORY = "allocation";
    public static final String KEY_RESOLUTION = "resolutionRatio";
    public static final String KEY_SUSTAINED = "sustainedPerformance";
    public static final String KEY_RUNTIME_SELECTION = "runtimeSelection";
    public static final String KEY_RUNTIME_PRELOAD = "runtimePreload";

    private static final String PREF_BACKUP = "optimizeBackup";

    public static final int INT = 0;
    public static final int BOOL = 1;
    public static final int STRING = 2;

    private GameOptimizer() {}

    /** One setting that changes: the value now and the value after the optimization */
    public static final class Change {
        public final String key;
        public final int type;
        public final Object before;
        public final Object after;

        Change(String key, int type, Object before, Object after) {
            this.key = key;
            this.type = type;
            this.before = before;
            this.after = after;
        }
    }

    /** What the planner needs to know, the values are the ones that are in effect now */
    public static final class Inputs {
        public final int totalRamMb;
        public final int cores;
        public final boolean is32Bit;
        public final int recommendedHeapMb;
        public final int heapMb;
        public final int resolutionPercent;
        public final boolean sustained;
        public final String runtimeSelection;
        public final boolean runtimePreload;

        public Inputs(int totalRamMb, int cores, boolean is32Bit, int recommendedHeapMb, int heapMb,
                      int resolutionPercent, boolean sustained, String runtimeSelection, boolean runtimePreload) {
            this.totalRamMb = totalRamMb;
            this.cores = cores;
            this.is32Bit = is32Bit;
            this.recommendedHeapMb = recommendedHeapMb;
            this.heapMb = heapMb;
            this.resolutionPercent = resolutionPercent;
            this.sustained = sustained;
            this.runtimeSelection = runtimeSelection;
            this.runtimePreload = runtimePreload;
        }
    }

    /** A device with little RAM, few cores or a 32 bit system. Its GPU is the first limit of the frame rate. */
    public static boolean isWeakDevice(int totalRamMb, int cores, boolean is32Bit) {
        return totalRamMb < 3072 || cores < 6 || is32Bit;
    }

    /** @return the settings to change, empty when everything already suits the device */
    @NonNull
    public static List<Change> plan(@NonNull Inputs in) {
        List<Change> changes = new ArrayList<>();

        // Memory. A heap that is too big gets the game killed by Android or swapped until it hangs,
        // one that is too small makes the garbage collector run all the time.
        int target = Math.min(in.recommendedHeapMb, 2048);
        boolean tooBig = in.heapMb > in.recommendedHeapMb * 5 / 4;
        boolean tooSmall = in.heapMb < target * 3 / 4;
        if ((tooBig || tooSmall) && target != in.heapMb) {
            // Lowering never goes under what the device was recommended
            changes.add(new Change(KEY_MEMORY, INT, in.heapMb, tooBig ? in.recommendedHeapMb : target));
        }

        // Render resolution. On a weak device the GPU decides the frame rate, and drawing fewer pixels is the
        // change with the biggest effect. 75% is still sharp enough to play.
        if (isWeakDevice(in.totalRamMb, in.cores, in.is32Bit) && in.resolutionPercent > 75) {
            changes.add(new Change(KEY_RESOLUTION, INT, in.resolutionPercent, 75));
        }

        // Sustained performance keeps the clocks where the phone can hold them, so the frame rate does
        // not drop after some minutes of thermal throttling. Only for devices that have the cores for it.
        if (!in.sustained && in.cores >= 6 && !in.is32Bit) {
            changes.add(new Change(KEY_SUSTAINED, BOOL, false, true));
        }

        if (!"auto".equals(in.runtimeSelection)) {
            changes.add(new Change(KEY_RUNTIME_SELECTION, STRING, in.runtimeSelection, "auto"));
        }
        if (!in.runtimePreload) {
            changes.add(new Change(KEY_RUNTIME_PRELOAD, BOOL, false, true));
        }
        return changes;
    }

    public static boolean hasBackup(@NonNull SharedPreferences prefs) {
        return prefs.contains(PREF_BACKUP);
    }

    /**
     * Writes the new values. The old values are saved first as "key|type|value" lines, a value
     * that was never set is saved as "u" so it goes back to the default of the launcher.
     */
    public static void apply(@NonNull SharedPreferences prefs, @NonNull List<Change> changes) {
        // An older backup is kept: pressing optimize twice must not lose the original values
        Set<String> backup = hasBackup(prefs)
                ? new HashSet<>(prefs.getStringSet(PREF_BACKUP, new HashSet<>())) : new HashSet<>();
        Set<String> saved = new HashSet<>();
        for (String line : backup) saved.add(line.substring(0, line.indexOf('|')));

        SharedPreferences.Editor editor = prefs.edit();
        for (Change change : changes) {
            if (!saved.contains(change.key)) backup.add(encodeCurrent(prefs, change));
            put(editor, change.key, change.type, change.after);
        }
        editor.putStringSet(PREF_BACKUP, backup);
        editor.commit();
    }

    /** Puts the values that were saved by {@link #apply} back, and forgets the backup */
    public static void restore(@NonNull SharedPreferences prefs) {
        Set<String> backup = prefs.getStringSet(PREF_BACKUP, null);
        if (backup == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String line : backup) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            String key = parts[0];
            String kind = parts[1];
            String value = parts[2];
            switch (kind) {
                case "i":
                    editor.putInt(key, Integer.parseInt(value));
                    break;
                case "b":
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                    break;
                case "s":
                    editor.putString(key, value);
                    break;
                default:
                    editor.remove(key);
            }
        }
        editor.remove(PREF_BACKUP);
        editor.commit();
    }

    private static String encodeCurrent(SharedPreferences prefs, Change change) {
        if (!prefs.contains(change.key)) return change.key + "|u|";
        switch (change.type) {
            case INT:
                return change.key + "|i|" + prefs.getInt(change.key, 0);
            case BOOL:
                return change.key + "|b|" + prefs.getBoolean(change.key, false);
            default:
                return change.key + "|s|" + prefs.getString(change.key, "");
        }
    }

    private static void put(SharedPreferences.Editor editor, String key, int type, Object value) {
        switch (type) {
            case INT:
                editor.putInt(key, (Integer) value);
                break;
            case BOOL:
                editor.putBoolean(key, (Boolean) value);
                break;
            default:
                editor.putString(key, (String) value);
        }
    }
}
