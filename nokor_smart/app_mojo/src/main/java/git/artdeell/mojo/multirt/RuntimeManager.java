package git.artdeell.mojo.multirt;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.perf.DeviceProfile;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.jre.JavaRunner;

/**
 * Chooses the Java runtime for a launch, checks that the runtimes can be started and warms the
 * files of the runtime that is going to be used.
 *
 * <p>The runtimes themselves are still installed and removed by {@link MultiRTUtils}, the runtime
 * dialog of the settings and {@code NewJREUtil}. This class only decides which one runs.
 *
 * <p>Selection, in this order:
 * <ol>
 *   <li>The runtime that is set on the instance, when it is usable and new enough.</li>
 *   <li>In manual mode the default runtime of the settings, when it is usable and new enough.</li>
 *   <li>The installed runtime with exactly the Java version that the game asks for.</li>
 *   <li>The usable runtime with the lowest version above it.</li>
 * </ol>
 * The runtimes that were not chosen but could run the game are returned as fallbacks, so a runtime
 * that is broken is skipped and not reported as a failed launch.
 */
public final class RuntimeManager {
    private static final String TAG = "RuntimeManager";
    public static final String PREF_SELECTION = "runtimeSelection";
    public static final String PREF_PRELOAD = "runtimePreload";
    public static final String SELECTION_AUTO = "auto";
    public static final String SELECTION_MANUAL = "manual";

    private static final String PREF_FAILED = "runtimeFailed";

    /** The most that is read to warm up the module image, it can be much bigger than the RAM that is spare */
    private static final long WARM_MODULES_LIMIT = 64L * 1024 * 1024;
    private static final Set<String> sWarmed = Collections.synchronizedSet(new HashSet<>());

    private RuntimeManager() {}

    public static boolean isAutoSelection() {
        if (LauncherPreferences.DEFAULT_PREF == null) return true;
        return SELECTION_AUTO.equals(LauncherPreferences.DEFAULT_PREF.getString(PREF_SELECTION, SELECTION_AUTO));
    }

    /**
     * A runtime is usable when its version and architecture could be read and the JVM library is
     * in the folder. The runtime itself is not started here.
     */
    public static boolean isUsable(@Nullable Runtime runtime) {
        if (runtime == null || runtime.javaVersion <= 0 || runtime.arch == null) return false;
        try {
            File home = new File(Tools.MULTIRT_HOME, runtime.name);
            return home.isDirectory() && JavaRunner.findVmPath(home, runtime.arch) != null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not check " + runtime.name, e);
            return false;
        }
    }

    /**
     * @param instanceRuntime the runtime name set on the instance, null for none
     * @param defaultRuntime  the default runtime of the settings, null or empty for none
     * @param requiredJava    the Java version the game asks for
     * @param auto            automatic mode, the default runtime is ignored
     * @return the choice, null when no usable runtime is new enough
     */
    @Nullable
    public static RuntimeSelector.Resolution resolve(@Nullable String instanceRuntime, @Nullable String defaultRuntime,
                                     int requiredJava, boolean auto) {
        List<Runtime> usable = new ArrayList<>();
        for (Runtime runtime : MultiRTUtils.getRuntimes()) {
            if (isUsable(runtime)) usable.add(runtime);
        }
        return RuntimeSelector.resolveFrom(usable, takeFailed(), instanceRuntime, defaultRuntime, requiredJava, auto);
    }

    /**
     * Remembers that the JVM of a runtime did not start. The next launch skips it when another
     * runtime can run the game, and the one after that tries it again.
     */
    public static void markFailed(@Nullable String runtimeName) {
        if (runtimeName == null || LauncherPreferences.DEFAULT_PREF == null) return;
        Set<String> failed = new HashSet<>(LauncherPreferences.DEFAULT_PREF.getStringSet(PREF_FAILED, new HashSet<>()));
        failed.add(runtimeName);
        LauncherPreferences.DEFAULT_PREF.edit().putStringSet(PREF_FAILED, failed).apply();
    }

    /** The runtimes that failed to start, the list is empty afterwards */
    @NonNull
    private static Set<String> takeFailed() {
        if (LauncherPreferences.DEFAULT_PREF == null) return new HashSet<>();
        Set<String> failed = new HashSet<>(LauncherPreferences.DEFAULT_PREF.getStringSet(PREF_FAILED, new HashSet<>()));
        if (!failed.isEmpty()) LauncherPreferences.DEFAULT_PREF.edit().remove(PREF_FAILED).apply();
        return failed;
    }

    /** @see RuntimeSelector#javaForMinecraft(String) */
    public static int javaForMinecraft(@Nullable String versionId) {
        return RuntimeSelector.javaForMinecraft(versionId);
    }

    /**
     * Reads the files that the JVM needs at its start into the page cache of the system, so the
     * first start after a restart of the phone does not wait for the storage. It runs in the
     * background at a low priority and does nothing when the RAM is short, a warm cache that
     * pushes the game out of memory would be worse than a cold one.
     */
    public static void warmUp(@NonNull Context context, @Nullable Runtime runtime) {
        if (runtime == null || !isUsable(runtime)) return;
        if (LauncherPreferences.DEFAULT_PREF != null
                && !LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_PRELOAD, true)) return;
        if (!sWarmed.add(runtime.name)) return;
        final int freeMb = Tools.getFreeDeviceMemory(context);
        final File home = new File(Tools.MULTIRT_HOME, runtime.name);
        final String arch = runtime.arch;
        new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                File vm = JavaRunner.findVmPath(home, arch);
                List<File> files = new ArrayList<>();
                if (vm != null) {
                    files.add(vm);
                    File[] archives = vm.getParentFile().listFiles((d, n) -> n.endsWith(".jsa"));
                    if (archives != null) Collections.addAll(files, archives);
                }
                File modules = new File(home, "lib/modules");
                if (modules.isFile()) files.add(modules);

                long total = 0;
                for (File file : files) total += Math.min(file.length(), WARM_MODULES_LIMIT);
                // Needs room for the files and a game that is about to start
                if (freeMb < (total >> 20) * 2 + 2048) {
                    Log.i(TAG, "Not warming up " + runtime.name + ": " + freeMb + " MB free");
                    return;
                }
                long begin = System.currentTimeMillis();
                byte[] buffer = new byte[256 * 1024];
                for (File file : files) readInto(file, buffer, WARM_MODULES_LIMIT);
                Log.i(TAG, "Warmed up " + runtime.name + " (" + (total >> 20) + " MB) in "
                        + (System.currentTimeMillis() - begin) + " ms");
            } catch (Throwable t) {
                Log.w(TAG, "Warm-up of " + runtime.name + " failed", t);
                sWarmed.remove(runtime.name);
            }
        }, "Runtime warm-up").start();
    }

    /**
     * Warms up the runtime that is the most likely to start next: the default runtime of the
     * settings, or the newest installed one. Call it when the launcher opens.
     */
    public static void warmUpLikely(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Runtime pick = null;
                String preferred = LauncherPreferences.PREF_DEFAULT_RUNTIME;
                if (preferred != null && !preferred.isEmpty()) {
                    Runtime runtime = MultiRTUtils.read(preferred);
                    if (isUsable(runtime)) pick = runtime;
                }
                if (pick == null) {
                    for (Runtime runtime : MultiRTUtils.getRuntimes()) {
                        if (isUsable(runtime) && (pick == null || runtime.javaVersion > pick.javaVersion)) pick = runtime;
                    }
                }
                warmUp(appContext, pick);
            } catch (Throwable t) {
                Log.w(TAG, "Could not pick a runtime to warm up", t);
            }
        });
    }

    private static void readInto(File file, byte[] buffer, long limit) throws IOException {
        long left = limit;
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while (left > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, left))) != -1) {
                left -= read;
            }
        }
    }

    /** A text for the settings: the runtimes that are installed and the ones that can start */
    @NonNull
    public static String describe(@NonNull Context context) {
        StringBuilder sb = new StringBuilder();
        List<Runtime> runtimes = MultiRTUtils.getRuntimes();
        sb.append("Selection: ").append(isAutoSelection() ? "automatic" : "manual").append('\n');
        if (runtimes.isEmpty()) sb.append("No runtime is installed.\n");
        for (Runtime runtime : runtimes) {
            sb.append("• ").append(runtime.name);
            if (runtime.javaVersion > 0) {
                sb.append(": Java ").append(runtime.javaVersion);
                if (runtime.versionString != null) sb.append(" (").append(runtime.versionString).append(")");
                sb.append(", ").append(runtime.arch);
            }
            sb.append(isUsable(runtime) ? ", ready" : ", broken or not complete").append('\n');
        }
        return sb.toString().trim();
    }

    /** The device and the memory advice for the settings text */
    @NonNull
    public static String describeDevice(@NonNull Context context) {
        return DeviceProfile.detect(context).describe();
    }
}
