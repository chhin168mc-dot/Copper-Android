package git.artdeell.mojo.perf;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.GpuUtils;

/**
 * What the device offers: CPU architecture and cores, RAM and the GPU. The values are measured
 * once per call, nothing here changes any setting.
 */
public final class DeviceProfile {
    private static final String TAG = "DeviceProfile";

    public final String arch;
    public final boolean is32Bit;
    public final int cores;
    /** Cores that run at (nearly) the highest clock of the device, 0 when the clocks are not readable */
    public final int bigCores;
    public final int totalRamMb;
    public final int freeRamMb;
    public final String gpuVendor;
    public final String gpuRenderer;
    public final int glesMajor;

    private DeviceProfile(String arch, boolean is32Bit, int cores, int bigCores, int totalRamMb,
                          int freeRamMb, String gpuVendor, String gpuRenderer, int glesMajor) {
        this.arch = arch;
        this.is32Bit = is32Bit;
        this.cores = cores;
        this.bigCores = bigCores;
        this.totalRamMb = totalRamMb;
        this.freeRamMb = freeRamMb;
        this.gpuVendor = gpuVendor;
        this.gpuRenderer = gpuRenderer;
        this.glesMajor = glesMajor;
    }

    @NonNull
    public static DeviceProfile detect(@NonNull Context context) {
        int cores = Math.max(1, java.lang.Runtime.getRuntime().availableProcessors());
        String vendor = "";
        String renderer = "";
        int gles = 0;
        try {
            GpuUtils.GLInfo info = GpuUtils.getGlInfo();
            if (info != null) {
                vendor = info.vendor == null ? "" : info.vendor;
                renderer = info.renderer == null ? "" : info.renderer;
                gles = info.glesMajorVersion;
            }
        } catch (Throwable t) {
            Log.w(TAG, "The GPU info is not available", t);
        }
        return new DeviceProfile(
                Architecture.archAsString(Architecture.getDeviceArchitecture()),
                Architecture.is32BitsDevice(),
                cores,
                countBigCores(cores),
                Tools.getTotalDeviceMemory(context),
                Tools.getFreeDeviceMemory(context),
                vendor, renderer, gles);
    }

    /** A core is "big" when its highest clock is at least 80% of the highest clock of all cores */
    private static int countBigCores(int cores) {
        long[] max = new long[cores];
        long best = 0;
        for (int i = 0; i < cores; i++) {
            max[i] = readLong(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"));
            if (max[i] > best) best = max[i];
        }
        if (best <= 0) return 0;
        int big = 0;
        for (long freq : max) {
            if (freq * 10 >= best * 8) big++;
        }
        return big;
    }

    private static long readLong(File file) {
        try {
            if (!file.canRead()) return -1;
            return Long.parseLong(Tools.read(file.getAbsolutePath()).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isAdreno() {
        return gpuRenderer.contains("Adreno") && gpuVendor.contains("Qualcomm");
    }

    public boolean isOldAdreno() {
        return gpuVendor.contains("Qualcomm") && (gpuRenderer.contains("Adreno (TM) 5")
                || gpuRenderer.contains("Adreno (TM) 4") || gpuRenderer.contains("Adreno (TM) 3")
                || gpuRenderer.contains("Adreno (TM) 2"));
    }

    /**
     * The heap that is safe to ask for on this device, in MB. The game needs RAM outside of the
     * heap too (GL buffers, code cache, stacks), and Android itself needs some. It is a starting
     * point for the memory setting, {@code JvmTuner} still lowers the real heap at launch when
     * the free RAM is lower than this.
     */
    public int recommendedHeapMb() {
        int reserve = totalRamMb < 3072 ? 800 : 1024;
        int usable = totalRamMb - reserve;
        int heap = (int) (usable * 0.6f);
        int cap = is32Bit ? 1024 : 3072;
        if (heap > cap) heap = cap;
        if (heap < 512) heap = Math.min(512, Math.max(256, usable));
        return heap / 128 * 128 > 0 ? heap / 128 * 128 : heap;
    }

    @NonNull
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(arch).append(", ").append(cores).append(" cores");
        if (bigCores > 0 && bigCores < cores) sb.append(" (").append(bigCores).append(" fast)");
        sb.append('\n');
        sb.append("RAM: ").append(totalRamMb).append(" MB total, ").append(freeRamMb).append(" MB free\n");
        sb.append("GPU: ").append(gpuRenderer.isEmpty() ? "unknown" : gpuRenderer);
        if (glesMajor > 0) sb.append(" (OpenGL ES ").append(glesMajor).append(")");
        sb.append('\n');
        sb.append("Recommended heap: ").append(recommendedHeapMb()).append(" MB");
        return sb.toString();
    }
}
