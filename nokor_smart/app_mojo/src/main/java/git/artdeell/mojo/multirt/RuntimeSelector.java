package git.artdeell.mojo.multirt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules that pick a runtime for a Java version. It only works on the list it is given, so the
 * rules can be checked without a device. {@link RuntimeManager} gives it the runtimes that are
 * installed and complete.
 */
public final class RuntimeSelector {
    private static final String TAG = "RuntimeSelector";

    private static final Pattern MC_VERSION = Pattern.compile("(?<![\\d.])1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?!\\d)");
    private static final Pattern YEAR_VERSION = Pattern.compile("^(\\d{2})\\.(\\d+)");

    private RuntimeSelector() {}

    public static final class Resolution {
        /** The runtime to start */
        @NonNull public final Runtime runtime;
        /** Why it was chosen, for the log and the report */
        @NonNull public final String reason;
        /** Other usable runtimes that can run the game, the best first */
        @NonNull public final List<Runtime> fallbacks;

        Resolution(@NonNull Runtime runtime, @NonNull String reason, @NonNull List<Runtime> fallbacks) {
            this.runtime = runtime;
            this.reason = reason;
            this.fallbacks = fallbacks;
        }
    }

    /**
     * @param usable          the runtimes that are installed and complete
     * @param failed          names of the runtimes that did not start the last time
     * @param instanceRuntime the runtime name set on the instance, null for none
     * @param defaultRuntime  the default runtime of the settings, null or empty for none
     * @param requiredJava    the Java version the game asks for
     * @param auto            automatic mode, the default runtime is ignored
     * @return the choice, null when no runtime is new enough
     */
    @Nullable
    public static Resolution resolveFrom(@NonNull List<Runtime> usable, @NonNull Set<String> failed,
                                  @Nullable String instanceRuntime, @Nullable String defaultRuntime,
                                  int requiredJava, boolean auto) {
        List<Runtime> candidates = new ArrayList<>();
        for (Runtime runtime : usable) {
            if (runtime.javaVersion >= requiredJava) candidates.add(runtime);
        }
        if (candidates.isEmpty()) return null;

        // A runtime that failed to start goes behind the others, but stays when nothing else can run the game
        List<Runtime> healthy = new ArrayList<>();
        for (Runtime runtime : candidates) {
            if (!failed.contains(runtime.name)) healthy.add(runtime);
        }
        if (!healthy.isEmpty() && healthy.size() < candidates.size()) {
            Log.w(TAG, "Skipping the runtimes that failed to start: " + failed);
            candidates = healthy;
        }

        // Exact version first, then the lowest version above it
        Collections.sort(candidates, (a, b) -> {
            boolean aExact = a.javaVersion == requiredJava;
            boolean bExact = b.javaVersion == requiredJava;
            if (aExact != bExact) return aExact ? -1 : 1;
            return Integer.compare(a.javaVersion, b.javaVersion);
        });

        Runtime chosen = null;
        String reason = null;
        Runtime fromInstance = find(candidates, instanceRuntime);
        if (fromInstance != null) {
            chosen = fromInstance;
            reason = "set on the instance";
        } else if (!auto) {
            Runtime fromDefault = find(candidates, defaultRuntime);
            if (fromDefault != null) {
                chosen = fromDefault;
                reason = "default runtime of the settings";
            }
        }
        if (chosen == null) {
            chosen = candidates.get(0);
            reason = chosen.javaVersion == requiredJava
                    ? "installed runtime with Java " + requiredJava
                    : "lowest installed Java above " + requiredJava + " (no Java " + requiredJava + " installed)";
        }
        List<Runtime> fallbacks = new ArrayList<>(candidates);
        fallbacks.remove(chosen);
        return new Resolution(chosen, reason, fallbacks);
    }

    @Nullable
    private static Runtime find(List<Runtime> runtimes, @Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        for (Runtime runtime : runtimes) {
            if (runtime.name.equals(name)) return runtime;
        }
        return null;
    }

    /**
     * The Java version a Minecraft version needs, for versions whose JSON does not say it. The
     * JSON of the version always wins over this table.
     *
     * @return 8 when nothing can be read from the id
     */
    public static int javaForMinecraft(@Nullable String versionId) {
        if (versionId == null) return 8;
        Matcher year = YEAR_VERSION.matcher(versionId);
        if (year.find()) {
            // The versions named after the year (26.1 and later) need Java 25
            if (Integer.parseInt(year.group(1)) >= 26) return 25;
        }
        Matcher matcher = MC_VERSION.matcher(versionId);
        if (!matcher.find()) return 8;
        int minor = Integer.parseInt(matcher.group(1));
        int patch = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (minor <= 16) return 8;
        if (minor == 17) return 16;
        if (minor < 20 || (minor == 20 && patch <= 4)) return 17;
        return 21;
    }

}
