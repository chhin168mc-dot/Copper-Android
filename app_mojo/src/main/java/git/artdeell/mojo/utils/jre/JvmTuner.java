package git.artdeell.mojo.utils.jre;

import android.util.Log;

import java.util.List;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Picks the Java heap size and the garbage collector / JIT flags for the game JVM.
 *
 * The defaults of HotSpot are made for desktops and servers. On a phone they let the young
 * generation grow to more than half of the heap, start the GC worker threads on every little core,
 * and run a full stop-the-world collection on every System.gc() call. All of this ends with a lot
 * of resident memory, long pauses (the game hangs for a second or more) and, on devices with
 * little free RAM, with Android killing the game process.
 *
 * The flags added here keep the pauses shorter than one game tick (50 ms), cap the young generation
 * so the resident memory stays close to what the game really uses, and limit the number of
 * GC/JIT threads so they do not fight with the render and server threads.
 *
 * Every flag is skipped when the user (or the version JSON) already sets it. Add
 * {@code -Dnokor.tune=off} to the Java arguments to switch the whole tuning off.
 */
public final class JvmTuner {
    private static final String TAG = "JvmTuner";
    private static final String OFF_SWITCH = "-Dnokor.tune=off";

    /** The heap is never lowered under this value because of a low amount of free RAM */
    private static final int MIN_HEAP_MB = 512;
    /** RAM that the game needs outside of the Java heap (GL buffers, code cache, metaspace, stacks) */
    private static final int NATIVE_RESERVE_MB = 500;
    /**
     * Free RAM reported by Android does not count the memory of cached background apps, which
     * the system kills on demand. So the free value is scaled up a bit before it is used as a limit.
     */
    private static final float RECLAIM_FACTOR = 1.5f;
    /** Safety margin kept free in the address space of a 32 bit process */
    private static final int ADDRESS_SPACE_MARGIN_MB = 192;

    private static final String[] GC_SELECTORS = {
            "UseG1GC", "UseSerialGC", "UseParallelGC", "UseParallelOldGC",
            "UseConcMarkSweepGC", "UseZGC", "UseShenandoahGC", "UseEpsilonGC"
    };

    private static final String[] YOUNG_GEN_FLAGS = {"Xmn", "NewSize", "MaxNewSize", "NewRatio"};

    private static volatile int sHeapMb = -1;

    private JvmTuner() {}

    /**
     * @return the heap size (in MB) that the game JVM uses. This is the size chosen by
     * {@link #resolveHeap(int, int, int)}, or the value of the memory setting if nothing was chosen yet.
     */
    public static int getHeapMb() {
        int heap = sHeapMb;
        if (heap > 0) return heap;
        int pref = LauncherPreferences.PREF_RAM_ALLOCATION;
        return pref >= 256 ? pref : 256;
    }

    /**
     * Lowers the requested heap size when the device does not have enough free RAM (or address
     * space on a 32 bit device) for it. A heap that is too big for the device makes Android kill the
     * game or swap it until it hangs.
     *
     * @param requestedMb    the heap size from the memory setting
     * @param freeDeviceMb   the free RAM of the device, or -1 if unknown
     * @param addressSpaceMb the largest continuous free address space, or -1 if it is not limited
     * @return the heap size to use, never bigger than requestedMb
     */
    public static int resolveHeap(int requestedMb, int freeDeviceMb, int addressSpaceMb) {
        // Never allow 0 or tiny heap – that produces "-Xmx0M" / invalid args and the JVM
        // answers with "Failed to create the Java virtual machine: Bad arguments".
        if (requestedMb < 256) requestedMb = 256;
        int heap = requestedMb;
        int floor = Math.min(MIN_HEAP_MB, requestedMb);

        if (freeDeviceMb > 0) {
            int limit = (int) (freeDeviceMb * RECLAIM_FACTOR) - NATIVE_RESERVE_MB;
            if (limit < heap) heap = Math.max(floor, limit);
        }
        if (addressSpaceMb > 0) {
            int limit = addressSpaceMb - ADDRESS_SPACE_MARGIN_MB;
            if (limit < heap) heap = Math.max(floor, limit);
        }
        if (heap < 256) heap = 256;

        Log.i(TAG, "Heap: requested " + requestedMb + " MB, free RAM " + freeDeviceMb
                + " MB, address space " + addressSpaceMb + " MB -> " + heap + " MB");
        sHeapMb = heap;
        return heap;
    }

    /**
     * Adds the GC and JIT flags to the argument list of the JVM.
     *
     * @param args the list of JVM arguments that were already collected
     */
    public static void addTuningArgs(List<String> args) {
        for (String arg : args) {
            if (OFF_SWITCH.equals(arg)) {
                Log.i(TAG, "Tuning is switched off by " + OFF_SWITCH);
                return;
            }
        }

        final int heapMb = getHeapMb();
        final int cores = Math.max(1, java.lang.Runtime.getRuntime().availableProcessors());

        boolean userChoseGc = hasAny(args, GC_SELECTORS);
        // G1 needs a few cores and some heap to pay off, and the 32 bit runtimes may not have it at all
        boolean useG1 = !userChoseGc && cores > 2 && heapMb >= 1024 && !Architecture.is32BitsDevice();

        if (useG1) {
            // GC workers on little cores are slow and a pause lasts as long as the slowest worker
            int gcThreads = Math.max(2, Math.min(4, cores / 2));
            int concThreads = Math.max(1, gcThreads / 2);
            // Bigger regions keep the large arrays of the game out of the "humongous" objects
            int regionMb = heapMb >= 2048 ? 4 : 2;

            addIfAbsent(args, "UnlockExperimentalVMOptions", "-XX:+UnlockExperimentalVMOptions");
            addIfAbsent(args, "UseG1GC", "-XX:+UseG1GC");
            addIfAbsent(args, "MaxGCPauseMillis", "-XX:MaxGCPauseMillis=50");
            if (!hasAny(args, YOUNG_GEN_FLAGS)) {
                addIfAbsent(args, "G1NewSizePercent", "-XX:G1NewSizePercent=20");
                addIfAbsent(args, "G1MaxNewSizePercent", "-XX:G1MaxNewSizePercent=35");
            }
            addIfAbsent(args, "G1HeapRegionSize", "-XX:G1HeapRegionSize=" + regionMb + "M");
            addIfAbsent(args, "G1ReservePercent", "-XX:G1ReservePercent=15");
            addIfAbsent(args, "G1HeapWastePercent", "-XX:G1HeapWastePercent=5");
            addIfAbsent(args, "G1MixedGCCountTarget", "-XX:G1MixedGCCountTarget=4");
            addIfAbsent(args, "InitiatingHeapOccupancyPercent", "-XX:InitiatingHeapOccupancyPercent=25");
            addIfAbsent(args, "ParallelRefProcEnabled", "-XX:+ParallelRefProcEnabled");
            // System.gc() is called by the game and by mods. A full stop-the-world collection
            // on a phone freezes the game for a long time, a concurrent one does not.
            addIfAbsent(args, "ExplicitGCInvokesConcurrent", "-XX:+ExplicitGCInvokesConcurrent");
            addIfAbsent(args, "ParallelGCThreads", "-XX:ParallelGCThreads=" + gcThreads);
            addIfAbsent(args, "ConcGCThreads", "-XX:ConcGCThreads=" + concThreads);
            Log.i(TAG, "Using G1 (heap " + heapMb + " MB, " + gcThreads + "+" + concThreads
                    + " GC threads, region " + regionMb + " MB)");
        } else if (!userChoseGc) {
            addIfAbsent(args, "UseSerialGC", "-XX:+UseSerialGC");
            Log.i(TAG, "Using the serial GC (heap " + heapMb + " MB, " + cores + " cores)");
        }

        // The default metaspace threshold is tiny and triggers a full GC again and again while
        // the game and the mods load their classes
        addIfAbsent(args, "MetaspaceSize", "-XX:MetaspaceSize=" + (heapMb >= 2048 ? 192 : 128) + "m");
        // The performance counters are memory mapped to a file in the (slow) storage
        addIfAbsent(args, "UsePerfData", "-XX:-UsePerfData");
        // Compiler threads steal the CPU from the game when there are only few cores
        if (cores <= 4) addIfAbsent(args, "CICompilerCount", "-XX:CICompilerCount=2");

        // A runtime that does not know one of the flags below must still start the game
        addIfAbsent(args, "IgnoreUnrecognizedVMOptions", "-XX:+IgnoreUnrecognizedVMOptions");
        // Every class of the game and of the mods is checked by the verifier when it loads. The
        // jars come from the version manifest and the mod folder, so on a phone the check only
        // adds seconds to the start and to the first load of a world.
        addIfAbsent(args, "BytecodeVerificationRemote", "-XX:-BytecodeVerificationRemote");
        // World generation and chunk code have very long methods. HotSpot keeps running those in
        // the interpreter by default, which is what makes a world load slowly.
        addIfAbsent(args, "DontCompileHugeMethods", "-XX:-DontCompileHugeMethods");
    }

    private static void addIfAbsent(List<String> args, String key, String flag) {
        if (hasAny(args, key)) return;
        args.add(flag);
    }

    private static boolean hasAny(List<String> args, String... keys) {
        for (String arg : args) {
            String key = flagKey(arg);
            if (key == null) continue;
            for (String wanted : keys) {
                if (wanted.equals(key)) return true;
            }
        }
        return false;
    }

    /**
     * Turns an argument like -XX:+Name, -XX:-Name, -XX:Name=value or -Xmn512m into the bare flag name.
     * @return the name, or null if the argument is not a HotSpot flag
     */
    private static String flagKey(String arg) {
        if (arg == null) return null;
        if (arg.startsWith("-XX:")) {
            String name = arg.substring(4);
            if (name.startsWith("+") || name.startsWith("-")) name = name.substring(1);
            int eq = name.indexOf('=');
            if (eq >= 0) name = name.substring(0, eq);
            return name;
        }
        if (arg.startsWith("-Xmn")) return "Xmn";
        if (arg.startsWith("-Xss")) return "Xss";
        return null;
    }
}
