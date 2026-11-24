package git.artdeell.mojo.utils.jre;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Removes the JVM arguments that the selected Java version does not accept. Arguments are often
 * copied from guides for another Java version, and the JVM stops with "Unrecognized option" or
 * "Failed to create the Java virtual machine" instead of ignoring them. Only the arguments that
 * are known to be gone (or not there yet) for the version are removed, everything else is kept.
 */
public final class JvmArgSanitizer {
    private static final String TAG = "JvmArgSanitizer";

    /** Options of the module system, they exist since Java 9. The next argument is their value when no "=" is used. */
    private static final String[] MODULE_OPTIONS = {
            "--add-opens", "--add-exports", "--add-modules", "--add-reads", "--limit-modules",
            "--patch-module", "--module-path", "--upgrade-module-path", "--illegal-access",
            "--enable-native-access"
    };

    /** -XX flags of the Concurrent Mark Sweep collector, which was removed in Java 14 */
    private static final String[] CMS_FLAGS = {
            "UseConcMarkSweepGC", "UseCMSInitiatingOccupancyOnly", "CMSInitiatingOccupancyFraction",
            "CMSIncrementalMode", "CMSParallelRemarkEnabled", "CMSClassUnloadingEnabled",
            "CMSScavengeBeforeRemark", "UseParNewGC"
    };

    private JvmArgSanitizer() {}

    /**
     * @param args      the JVM arguments, changed in place
     * @param javaMajor the major version of the runtime (8, 17, 21...), 0 when unknown
     * @return the arguments that were removed
     */
    @NonNull
    public static List<String> sanitize(@NonNull List<String> args, int javaMajor) {
        List<String> removed = new ArrayList<>();
        if (javaMajor <= 0) return removed;

        Iterator<String> iterator = args.iterator();
        while (iterator.hasNext()) {
            String arg = iterator.next();
            if (arg == null) continue;
            if (!isSupported(arg, javaMajor)) {
                iterator.remove();
                removed.add(arg);
                // "--add-opens a/b=c" given as two arguments: the value goes with the option
                if (isModuleOptionWithoutValue(arg) && iterator.hasNext()) {
                    String value = iterator.next();
                    if (value != null && !value.startsWith("-")) {
                        iterator.remove();
                        removed.add(value);
                    }
                }
            }
        }
        if (!removed.isEmpty()) {
            Log.w(TAG, "Removed JVM arguments that Java " + javaMajor + " does not accept: " + removed);
        }
        return removed;
    }

    private static boolean isModuleOptionWithoutValue(String arg) {
        for (String option : MODULE_OPTIONS) {
            if (option.equals(arg)) return true;
        }
        return false;
    }

    static boolean isSupported(String arg, int javaMajor) {
        if (javaMajor < 9) {
            if (arg.startsWith("-Xlog")) return false;
            for (String option : MODULE_OPTIONS) {
                if (arg.equals(option) || arg.startsWith(option + "=")) return false;
            }
            if (arg.equals("-p") || arg.equals("--module")) return false;
        }
        // The runtime ignores this one since Java 17 and prints a warning
        if (javaMajor >= 17 && arg.startsWith("--illegal-access")) return false;

        String flag = flagName(arg);
        if (flag == null) return true;

        if (javaMajor >= 14) {
            for (String cms : CMS_FLAGS) {
                if (cms.equals(flag)) return false;
            }
        }
        if (javaMajor >= 12 && flag.equals("AggressiveOpts")) return false;
        if (javaMajor >= 18 && (flag.equals("UseBiasedLocking") || flag.startsWith("BiasedLocking"))) return false;
        // The permanent generation is gone since Java 8, these options do nothing
        if (flag.equals("MaxPermSize") || flag.equals("PermSize")) return false;
        if (javaMajor < 21 && flag.equals("ZGenerational")) return false;
        return true;
    }

    /** -XX:+Name, -XX:-Name and -XX:Name=value give "Name" */
    private static String flagName(String arg) {
        if (!arg.startsWith("-XX:")) return null;
        String name = arg.substring(4);
        if (name.startsWith("+") || name.startsWith("-")) name = name.substring(1);
        int eq = name.indexOf('=');
        if (eq >= 0) name = name.substring(0, eq);
        return name;
    }
}
