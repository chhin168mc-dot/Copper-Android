package git.artdeell.mojo.utils;

import android.content.*;
import android.system.*;
import android.util.*;

import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.util.*;

import git.artdeell.mojo.Logger;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.plugins.LibraryPlugin;
import git.artdeell.mojo.prefs.*;

public class JREUtils {
    private static final int LOGCAT_MAX_RESTARTS = 10;
    private static final int LOGCAT_BATCH_SIZE = 4096;

    public static void redirectAndPrintJRELog() {
        Log.v("jrelog","Log starts here");
        // A daemon thread with a low priority, so the log never competes with the game and never
        // keeps the process alive after the game is gone
        Thread logThread = new Thread(JREUtils::pumpLogcat, "JRE logcat");
        logThread.setDaemon(true);
        logThread.setPriority(Thread.MIN_PRIORITY);
        logThread.start();
        Log.i("jrelog-logcat","Logcat thread started");
    }

    private static void clearLogcat() {
        java.lang.Process clear = null;
        try {
            clear = new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
            // Collect the exit code, otherwise every call leaves a zombie process behind
            clear.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            Log.w("jrelog-logcat", "Failed to clear logcat", e);
        } finally {
            if (clear != null) clear.destroy();
        }
    }

    private static void flushLogBatch(StringBuilder batch) {
        int length = batch.length();
        if (length == 0) return;
        // appendToLog adds its own line break
        if (batch.charAt(length - 1) == '\n') batch.setLength(length - 1);
        Logger.appendToLog(batch.toString());
        batch.setLength(0);
    }

    private static void pumpLogcat() {
        for (int failTime = 0; failTime <= LOGCAT_MAX_RESTARTS; failTime++) {
            java.lang.Process logcat = null;
            try {
                Log.i("jrelog-logcat", "Clearing logcat");
                clearLogcat();
                Log.i("jrelog-logcat", "Starting logcat");
                // No filtering by tag anymore as that relied on incorrect log levels set in log.h
                logcat = new ProcessBuilder()
                        .command("logcat", "-v", "brief", "-s", "jrelog", "LIBGL", "NativeInput")
                        .redirectErrorStream(true).start();

                // Lines are read as UTF-8 text, so a multi-byte character is never cut in half,
                // and they are sent in batches so a flood of messages does not flood the JNI
                StringBuilder batch = new StringBuilder(LOGCAT_BATCH_SIZE + 256);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcat.getInputStream(), java.nio.charset.StandardCharsets.UTF_8), 8192)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        batch.append(line).append('\n');
                        if (batch.length() >= LOGCAT_BATCH_SIZE || !reader.ready()) {
                            flushLogBatch(batch);
                        }
                    }
                    flushLogBatch(batch);
                }

                int exitCode = logcat.waitFor();
                if (exitCode == 0) return;
                Log.e("jrelog-logcat", "Logcat exited with code " + exitCode);
                if (failTime < LOGCAT_MAX_RESTARTS) {
                    Log.i("jrelog-logcat", "Restarting logcat (attempt " + (failTime + 1) + "/" + LOGCAT_MAX_RESTARTS + ")");
                    // Do not restart in a tight loop if logcat keeps failing
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                Log.e("jrelog-logcat", "Exception on logging thread", e);
                Logger.appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                return;
            } finally {
                if (logcat != null) logcat.destroy();
            }
        }
        Logger.appendToLog("ERROR: Unable to get more log.");
    }

    private static void overrideEnvVars(Map<String, String> envMap) throws IOException {
        File customEnvFile = new File(Tools.DIR_GAME_HOME, "custom_env.txt");
        if(!customEnvFile.exists() || !customEnvFile.isFile()) return;
        BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
        String line;
        while ((line = reader.readLine()) != null) {
            // Not use split() as only split first one
            int index = line.indexOf("=");
            envMap.put(line.substring(0, index), line.substring(index + 1));
        }
        reader.close();
    }

    public static void setupFfmpegEnv(Context ctx, Map<String, String> envMap) {
        LibraryPlugin ffmpeg = LibraryPlugin.discoverPlugin(ctx, LibraryPlugin.ID_FFMPEG_PLUGIN);
        if(ffmpeg == null) return;
        envMap.put("POJAV_FFMPEG_PATH", ffmpeg.resolveAbsolutePath("libffmpeg.so"));
    }
    public static void setGameEnvironment(Context context, Map<String, String> versionSpecificEnv) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();
        if(versionSpecificEnv != null) envMap.putAll(versionSpecificEnv);
		// This is currently required for YSM mod to function
		File modRuntimeDir = new File(Tools.DIR_CACHE, "app_runtime_mod");
		envMap.put("MOD_ANDROID_RUNTIME", modRuntimeDir.getAbsolutePath());

        setupFfmpegEnv(context, envMap);

        if(LauncherPreferences.PREF_BIG_CORE_AFFINITY) envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        if(LauncherPreferences.PREF_ALSOFT_FORCE_OPENSL) envMap.put("ALSOFT_DRIVERS", "opensl");

        overrideEnvVars(envMap);

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            }catch (NullPointerException exception){
                Log.e("JREUtils", exception.toString());
            }
        }
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        String[] separators = new String[]{"-XX:-","-XX:+", "-XX:","--", "-D", "-X", "-javaagent:", "-verbose"};
        for(String prefix : separators){
            while (true){
                int start = args.indexOf(prefix);
                if(start == -1) break;
                //Get the end of the current argument by checking the nearest separator
                int end = -1;
                for(String separator: separators){
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if(tempEnd == -1) continue;
                    if(end == -1){
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                //Fallback
                if(end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if(parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if(arraySize > 0){
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if(lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")){
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                // --a-b= handling (with multiple signs so we don't accidentally remove mandatory args)
                else if(parsedSubString.startsWith("--") && parsedSubString.indexOf('=') != -1) {
                    parsedArguments.add(parsedSubString);
                }
                else Log.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    public static int getDetectedVersion() {
        return GpuUtils.getGlInfo().glesMajorVersion;
    }
    public static native int chdir(String path);

    public static native void setLdLibraryPath(String ldLibraryPath);
    // See the redirect hook on the JNI side
    public static native void setLibraryOverrides(String basePath, String overridelibs);
    static {
        System.loadLibrary("mojort");
        System.loadLibrary("mojoawt");
    }
}
