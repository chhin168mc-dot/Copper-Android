package git.artdeell.mojo.utils.jre;

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import git.artdeell.mojo.Architecture;
import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Logger;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.skins.LocalSkinServer;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.game.renderer.impl.GLESRenderSpec;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.lifecycle.LifecycleAwareAlertDialog;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.RuntimeManager;
import git.artdeell.mojo.multirt.RuntimeSelector;
import git.artdeell.mojo.perf.DeviceProfile;
import git.artdeell.mojo.perf.PerfRecorder;
import git.artdeell.mojo.perf.MinecraftOptionsOptimizer;
import git.artdeell.mojo.perf.SmartFpsOptimizer;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.game.renderer.GameRenderer;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.utils.DateUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.GameOptionsUtils;
import git.artdeell.mojo.utils.JREUtils;
import git.artdeell.mojo.utils.JSONUtils;
import git.artdeell.mojo.utils.MCOptionUtils;
import git.artdeell.mojo.utils.OldVersionsUtils;
import git.artdeell.mojo.utils.plugins.LibraryPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.R;

public class GameRunner {
    private static final String FILEDIALOG_BRIDGE_ASSET = "components/filedialog_bridge/filedialog_bridge.jar";
    /**
     * Optimization mods based on Sodium can mitigate the render distance issue. Check if Sodium
     * or its derivative is currently installed to skip the render distance check.
     * @param gameDir current game directory
     * @return whether sodium or a sodium-based mod is installed
     */
    private static boolean hasSodium(File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if(mods == null) return false;
        for(File file : mods) {
            String name = file.getName();
            if(name.contains("sodium") ||
                    name.contains("embeddium") ||
                    name.contains("rubidium")) return true;
        }
        return false;
    }

    /**
     * Check if Angelica is currently installed to allow usage of LTW
     * @param gameDir current game directory
     * @return whether Angelica is installed
     */
    public static boolean hasAngelica(File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if(mods == null) return false;
        for(File file : mods) {
            String name = file.getName();
            if(name.contains("angelica")) return true;
        }
        return false;
    }

    /**
     * Initialize OpenGL and do checks to see if the GPU of the device is affected by the render
     * distance issue.

     * Currently only checks whether the user has an Adreno GPU capable of OpenGL ES 3.

     * This issue is caused by a very severe limit on the amount of GL buffer names that could be allocated
     * by the Adreno properietary GLES driver.

     * @return whether the GPU is affected by the Large Thin Wrapper render distance issue on vanilla
     */

    private static boolean affectedByRenderDistanceIssue(JVersionList.Version version) throws ParseException {
        if(LauncherPreferences.PREF_USE_ANGLE || LauncherPreferences.PREF_USE_SYSTEM_ANGLE) return false;
        GpuUtils.GLInfo info = GpuUtils.getGlInfo();
        return info.isAdreno() &&
                info.glesMajorVersion >= 3 &&
                // 1.21.5 fixes the RD issue, released on march 25 2025
                DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2025, 2, 25);
    }

    private static boolean checkRenderDistance(JVersionList.Version version, File gamedir) throws ParseException {
        if(!affectedByRenderDistanceIssue(version)) return false;
        if(hasSodium(gamedir)) return false;
        try {
            MCOptionUtils.load();
        }catch (Exception e) {
            Log.e("Tools", "Failed to load config", e);
        }
        int renderDistance = GameOptionsUtils.parseIntDefault(MCOptionUtils.get("renderDistance"),12);
        // 7 is the render distance "magic number" above which MC creates too many buffers
        // for Adreno's OpenGL ES implementation
        return renderDistance > 7;
    }

    private static boolean isGl4esCompatible(JVersionList.Version version) throws Exception{
        return DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2025, 1, 7);
    }

    private static boolean isCompatContext(JVersionList.Version version) throws Exception{
        // Day before the release date of 21w10a, the first OpenGL 3 Core Minecraft version
        return DateUtils.dateBefore(DateUtils.getOriginalReleaseDate(version), 2021, 3, 9);
    }

    private static boolean showDialog(AppCompatActivity activity, int message) throws InterruptedException {
        LifecycleAwareAlertDialog.DialogCreator dialogCreator = ((alertDialog, dialogBuilder) ->
                dialogBuilder.setMessage(activity.getString(message))
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w)->{}));
        return LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator);
    }

    // Autoswitch to provided renderer if supported, otherwise - crash with resId dialog message
    private static void switchRendererIfSupported(boolean support,
                                                  RenderSpec renderer,
                                                  GameRenderer gameRenderer,
                                                  Instance instance,
                                                  AppCompatActivity activity,
                                                  int resId) throws InterruptedException, IOException {
        if(support) {
            instance.renderer = renderer.tag();
            instance.write();
            gameRenderer.setCurrentRenderer(renderer);
        }else {
            showDialog(activity, resId);
            System.exit(0);
        }
    }

    public static void launchGame(final AppCompatActivity activity, Account account,
                                  Instance instance, String versionId, File[] classpath, GameRenderer gameRenderer) throws Throwable {
        PerfRecorder.begin();
        if(!performAuthPing(activity, account)) account = account.toLocal();
        PerfRecorder.mark("auth_checked");
        git.artdeell.mojo.skins.DefaultSkins.init(activity);

        int freeDeviceMemory = Tools.getFreeDeviceMemory(activity);
        int freeAddressSpace = Architecture.is32BitsDevice() ? Tools.getMaxContinuousAddressSpaceSize() : -1;
        Log.i("MemStat", "Free RAM: " + freeDeviceMemory + " Addressable: " + freeAddressSpace);
        // A 32 bit process can only map the heap into a continuous free part of its address space
        boolean addressSpaceLimited = freeAddressSpace != -1 && freeDeviceMemory > freeAddressSpace;

        // Do not give the game more memory than the device can spare. A heap that is too big makes
        // Android kill the game or swap it until it hangs. Instead of a warning that the player has
        // to click away, the heap is lowered and the player gets a short notice.
        final int requestedHeap = LauncherPreferences.PREF_RAM_ALLOCATION;
        final int heapMb = JvmTuner.resolveHeap(requestedHeap, freeDeviceMemory, addressSpaceLimited ? freeAddressSpace : -1);
        if(heapMb < requestedHeap && (addressSpaceLimited || LauncherPreferences.PREF_SHOW_MEMORY_WARNING_DIALOG)) {
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    activity.getString(R.string.boost_heap_reduced, heapMb, requestedHeap), Toast.LENGTH_LONG).show());
        }
        File gamedir = instance.getGameDirectory();
        // Apply the player's opt-in Minecraft video profile to this instance only.
        // The original options.txt is backed up before the first modification.
        if (LauncherPreferences.DEFAULT_PREF != null) {
            DeviceProfile deviceProfile = DeviceProfile.detect(activity);
            boolean smartFpsEnabled = LauncherPreferences.DEFAULT_PREF.getBoolean(
                    SmartFpsOptimizer.PREF_ENABLED, false);
            if (smartFpsEnabled) {
                SmartFpsOptimizer.applyIfEnabled(gamedir, LauncherPreferences.DEFAULT_PREF, deviceProfile);
            } else {
                // If Smart FPS was turned off, put back the user's original resolution and options.
                if (SmartFpsOptimizer.hasBackup(LauncherPreferences.DEFAULT_PREF)) {
                    MinecraftOptionsOptimizer.restore(gamedir);
                    SmartFpsOptimizer.restore(LauncherPreferences.DEFAULT_PREF);
                }
                MinecraftOptionsOptimizer.applyIfEnabled(gamedir, LauncherPreferences.DEFAULT_PREF);
            }
        }
        JVersionList.Version versionInfo = Tools.getVersionInfo(versionId);
        // We don't need the library list, the asset index, client download info for the code below
        versionInfo.libraries = null;
        versionInfo.downloads = null;

        RenderSpec renderer = gameRenderer.getCurrentRenderer();

        if(!versionInfo.disableRendererChecks) {
            // Switch renderer to GL4ES when running a compat context version on LTW
            if(isCompatContext(versionInfo) && !hasAngelica(gamedir) && renderer instanceof GLESRenderSpec.LTWRenderSpec) {
                switchRendererIfSupported(true, GameRenderer.getKnownRenderer(Renderers.GL4ES_RENDERER), gameRenderer, instance, activity, 0);
            }

            boolean isGl4es = renderer instanceof GLESRenderSpec.GL4ESRenderSpec;
            RenderSpec ltw = GameRenderer.getKnownRenderer(Renderers.LTW_RENDERER);
            boolean ltwSupported = ltw != null && ltw.compatibleDevice(activity);
            // Block Sodium from running with GL4ES on 1.17+
            if(!isCompatContext(versionInfo) && isGl4es && hasSodium(gamedir)) {
                switchRendererIfSupported(ltwSupported, ltw, gameRenderer, instance, activity, R.string.compat_sodium_not_supported);
            }

            // Switch renderer to LTW when running 1.21.5
            if(!isGl4esCompatible(versionInfo) && isGl4es) {
                switchRendererIfSupported(ltwSupported, ltw, gameRenderer, instance, activity, R.string.compat_version_not_supported);
            }
        }

        boolean isLtw = renderer instanceof GLESRenderSpec.LTWRenderSpec;

        if(isLtw && checkRenderDistance(versionInfo, gamedir)) {
            if(showDialog(activity, R.string.ltw_render_distance_warning_msg)) return;
            // If the code goes here, it means that the user clicked "OK". Fix the render distance.
            try {
                MCOptionUtils.set("renderDistance", "7");
                MCOptionUtils.save();
            }catch (Exception e) {
                Log.e("Tools", "Failed to fix render distance setting", e);
            }
        }

        GameOptionsUtils.fixOptions(isLtw);

        if(isLtw && GpuUtils.getGlInfo().forcedMsaa) {
            if(showDialog(activity, R.string.ltw_4x_msaa_warning_msg)) return;
        }

        // The JSON of the version says which Java it needs, the table is only for JSONs that do not
        int requiredJavaVersion = versionInfo.javaVersion != null
                ? versionInfo.javaVersion.majorVersion : RuntimeManager.javaForMinecraft(versionId);
        PerfRecorder.mark("renderer_checked");

        Runtime runtime = MultiRTUtils.forceReread(pickRuntime(instance, requiredJavaVersion));
        PerfRecorder.mark("runtime_picked");
        RuntimeManager.warmUp(activity, runtime);

        // Pre-process specific files
        disableSplash(gamedir);
        List<String> launchArgs = getMoJsonClientArgs(account, versionInfo, gamedir);

        // Select the appropriate openGL version
        OldVersionsUtils.selectOpenGlVersion(versionInfo);

        ArrayList<String> launchClassPath = new ArrayList<>(classpath.length + 1);
        classpath = prependFileDialogBridge(activity, classpath);
        for(int i = 0; i < classpath.length; i++) {
            File classpathEntry = classpath[i];
            String entryPath = classpathEntry.getAbsolutePath();
            if(!classpathEntry.exists()) {
                Log.w("GameRunner", "Skipped classpath entry " + entryPath + " because it is missing");
            }
            launchClassPath.add(entryPath);
            // Unreference the classpath entry to avoid retaining it on heap
            classpath[i] = null;
        }
        launchClassPath.trimToSize();

        List<String> javaArgList = new ArrayList<>();

        if (versionInfo.logging != null && versionInfo.logging.client != null && versionInfo.logging.client.file != null) {
            String configFile = Tools.DIR_DATA + "/security/" + versionInfo.logging.client.file.id.replace("client", "log4j-rce-patch");
            if (!new File(configFile).exists()) {
                configFile = Tools.DIR_GAME_NEW + "/" + versionInfo.logging.client.file.id;
            }
            javaArgList.add("-Dlog4j.configurationFile=" + configFile);
        }

        // This allows some mods to use additional libs provided via plugin so we don't need to bloat our app with uncommon stuff
        String additionalNativesDir;
        LibraryPlugin natives;
        if((natives = LibraryPlugin.discoverPlugin(activity, LibraryPlugin.ID_NATIVES_PLUGIN)) != null){
            additionalNativesDir = natives.getLibraryPath();
            File dir = new File(additionalNativesDir);
            if(dir.isDirectory()){
                String overrides = String.join(" ", dir.list((f, s) -> s.endsWith(".so")));
                JREUtils.setLibraryOverrides(additionalNativesDir, overrides);
            }
            additionalNativesDir = ':' + additionalNativesDir;
        } else additionalNativesDir = "";

        versionInfo.logging = null;

        File versionSpecificNativesDir = new File(Tools.DIR_CACHE, "natives/"+versionId);
        if(versionSpecificNativesDir.exists()) {
            String dirPath = versionSpecificNativesDir.getAbsolutePath();
            javaArgList.add("-Djava.library.path="+dirPath+":"+Tools.NATIVE_LIB_DIR+additionalNativesDir);
            javaArgList.add("-Djna.boot.library.path="+dirPath);
            // Sometimes, the game can extract natives itself onto this path
            javaArgList.add("-Dorg.lwjgl.librarypath="+dirPath);
        } else {
            javaArgList.add("-Djava.library.path="+Tools.NATIVE_LIB_DIR+additionalNativesDir);
        }

        File lwjglExtractDir = new File(Tools.DIR_CACHE, "lwjgl_native/"+versionId);
        FileUtils.ensureDirectory(lwjglExtractDir);
        javaArgList.add("-Dorg.lwjgl.system.SharedLibraryExtractPath="+lwjglExtractDir.getAbsolutePath());

        addAuthlibInjectorArgs(javaArgList, account);
        addNoBlocklistArgs(javaArgList);

        mergeMoJsonArgs(javaArgList, getMoJsonJvmArgs(versionId));

        versionInfo.arguments = null;
        versionInfo.minecraftArguments = null;
        versionInfo.assets = null;
        versionInfo.assetIndex = null;

        javaArgList.addAll(JREUtils.parseJavaArguments(instance.getLaunchArgs()));

        Map<String, String> versionEnvMap = versionInfo.environment;

        // TODO: this should be decoupled from GameRunner completely
        gameRenderer.setupEnvironment(activity);
        JREUtils.setGameEnvironment(activity, versionEnvMap);
        JREUtils.chdir(instance.getGameDirectory().getAbsolutePath());

        if(!gameRenderer.maybeSetupRenderer()) {
            if(showDialog(activity, R.string.gr_err_renderer_load_Failed)) return;
            System.exit(0);
        }
        javaArgList.add("-Dorg.lwjgl.opengl.libname=libGLMojo.so");
        javaArgList.add("-Dorg.lwjgl.freetype.libname="+ Tools.NATIVE_LIB_DIR+"/libfreetype.so");

        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg, JvmTuner.getHeapMb()), Toast.LENGTH_SHORT).show());

        Log.i("GameRunner", "Running with "+ launchArgs.toString());

        String mainClass = versionInfo.mainClass;

        // The JVM and all of its threads (server, render, GC, JIT) get the priority of this thread
        // when they are created. A slightly higher priority keeps the game ticking when other
        // apps and system services use the CPU.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Throwable t) {
            Log.w("GameRunner", "Failed to raise the priority of the JVM thread", t);
        }

        try {
            JavaRunner.nativeSetupExit(activity);
            PerfRecorder.mark("args_built");
            writePerfReport(activity, runtime, requiredJavaVersion, renderer);
            JavaRunner.startJvm(runtime, javaArgList, launchClassPath, mainClass, launchArgs);
        }catch (VMLoadException e) {
            // The next launch uses another runtime when there is one that can run this version
            RuntimeManager.markFailed(runtime.name);
            final String failedRuntime = runtime.name;
            LifecycleAwareAlertDialog.DialogCreator dialogCreator = (dialog, builder) ->
                builder.setMessage(e.toString(activity) + "\n\n"
                        + activity.getString(R.string.perf_runtime_failed_hint, failedRuntime))
                        .setPositiveButton(android.R.string.ok, (d, w)->{});

            if(LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator)) {
                return;
            }
        }

        Tools.restartLauncherActivity(activity);
        Tools.fullyExit();
    }

    /** Writes the timings of this launch when the user turned the report on, it never stops the launch */
    private static void writePerfReport(AppCompatActivity activity, Runtime runtime, int requiredJava, RenderSpec renderer) {
        if(!PerfRecorder.isEnabled()) return;
        try {
            Map<String, String> settings = new java.util.LinkedHashMap<>();
            settings.put("java_required", String.valueOf(requiredJava));
            settings.put("java_runtime", String.valueOf(runtime.javaVersion));
            settings.put("heap_mb", String.valueOf(JvmTuner.getHeapMb()));
            settings.put("renderer", renderer == null ? "none" : renderer.name());
            settings.put("runtime_selection", RuntimeManager.isAutoSelection() ? "auto" : "manual");
            settings.put("tuning", String.valueOf(LauncherPreferences.DEFAULT_PREF.getString("javaArgs", "").contains("-Dnokor.tune=off") ? "off" : "on"));
            PerfRecorder.finish(DeviceProfile.detect(activity), settings);
        } catch (Throwable t) {
            Log.w("GameRunner", "Performance report failed", t);
        }
    }

    /**
     * Unpack the file dialog bridge jar and put it ahead of the game classpath, so
     * games link against the launcher dialog implementations (tinyfd, NFD) instead
     * of the bundled native glue libraries (which have no Android file picker).
     */
    private static File[] prependFileDialogBridge(AppCompatActivity activity, File[] classpath) {
        try {
            File bridgeDir = new File(Tools.DIR_CACHE, "filedialog_bridge");
            if (!bridgeDir.isDirectory() && !bridgeDir.mkdirs()) return classpath;
            File bridgeJar = new File(bridgeDir, "filedialog_bridge.jar");
            try (InputStream in = activity.getAssets().open(FILEDIALOG_BRIDGE_ASSET);
                 OutputStream out = new FileOutputStream(bridgeJar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            File[] newClasspath = new File[classpath.length + 1];
            newClasspath[0] = bridgeJar;
            System.arraycopy(classpath, 0, newClasspath, 1, classpath.length);
            Log.i("GameRunner", "File dialog bridge prepended to the game classpath");
            return newClasspath;
        } catch (Throwable t) {
            Log.w("GameRunner", "File dialog bridge unavailable, file dialogs will stay inactive", t);
            return classpath;
        }
    }

    private static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if(FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(forgeSplashFile,
                            forgeSplashContent.replace("enabled=true", "enabled=false"));
                }
            } catch (IOException e) {
                Log.w(Tools.APP_NAME, "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Log.w(Tools.APP_NAME, "Failed to create the configuration directory");
        }
    }

    private static boolean performAuthPing(Context context, Account account) {
        String injectorUrl = account.authType.injectorUrl;
        if(injectorUrl == null) return true;
        Logger.appendToLog("Pinging authentication service...");
        if(DownloadUtils.pingInjectorUrl(injectorUrl)) return true;
        Logger.appendToLog("Authentication service did not respond, disabling");
        Tools.runOnUiThread(()-> Toast.makeText(context, context.getString(R.string.warn_auth_type_missing, injectorUrl), Toast.LENGTH_SHORT).show());
        return false;
    }

    private static void addAuthlibInjectorArgs(List<String> javaArgList, Account account) {
        String injectorUrl = account.authType.injectorUrl;
        if(injectorUrl == null && account.authType == AuthType.LOCAL) {
            injectorUrl = LocalSkinServer.prepare(account);
        }
        if(injectorUrl == null) return;
        javaArgList.add("-javaagent:"+Tools.DIR_DATA+"/authlib-injector/authlib-injector.jar="+injectorUrl);
    }

    private static void addNoBlocklistArgs(List<String> javaArgList) {
        if(!LauncherPreferences.PREF_DISABLE_BLOCKLIST) return;
        javaArgList.add("-javaagent:"+Tools.DIR_DATA+"/noblocklist/NoBlocklist-1.0-SNAPSHOT-all.jar");
    }

    // Skip setting essential flags from the version JSON as we already override them
    private static boolean shouldSkipArg(String arg) {
        final String[] args = {
                "-Djava.library.path=",
                "-Djna.tmpdir=",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=",
                "-Dio.netty.native.workdir="
        };
        for(String s : args) {
            if(arg.startsWith(s)) return true;
        }
        return false;
    }

    private static void mergeMoJsonArgs(List<String> userArgs, List<String> moJsonArgs) {
        for(String arg : moJsonArgs) {
            if(shouldSkipArg(arg)) continue;
            userArgs.add(arg);
        }
    }

    private static List<String> getMoJsonJvmArgs(String versionName) {
        JVersionList.Version versionInfo = Tools.getVersionInfo(versionName, true);
        // Parse Forge 1.17+ additional JVM Arguments
        if (versionInfo.arguments == null || versionInfo.arguments.jvm == null) {
            return Collections.emptyList();
        }

        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("classpath_separator", ":");
        varArgMap.put("library_directory", Tools.DIR_HOME_LIBRARY);
        varArgMap.put("version_name", versionInfo.id);
        varArgMap.put("natives_directory", Tools.NATIVE_LIB_DIR);

        List<String> clientVmArgs = new ArrayList<>();
        if (versionInfo.arguments != null) {
            for (Object arg : versionInfo.arguments.jvm) {
                if (arg instanceof String) {
                    clientVmArgs.add((String) arg);
                } //TODO: implement (?maybe?)
            }
        }
        return JSONUtils.insertJSONValueList(clientVmArgs, varArgMap);
    }

    private static List<String> getMoJsonClientArgs(Account profile, JVersionList.Version versionInfo, File gameDir) {
        String username = profile.username;
        String versionName = versionInfo.id;
        if (versionInfo.inheritsFrom != null) {
            versionName = versionInfo.inheritsFrom;
        }

        String userType = "mojang";
        try {
            Date creationDate = DateUtils.getOriginalReleaseDate(versionInfo);
            // Minecraft 22w43a which adds chat reporting (and signing) was released on
            // 26th October 2022. So, if the date is not before that (meaning it is equal or higher)
            // change the userType to MSA to fix the missing signature
            if(creationDate != null && !DateUtils.dateBefore(creationDate, 2022, 9, 26)) {
                userType = "msa";
            }
        }catch (ParseException e) {
            Log.e("CheckForProfileKey", "Failed to determine profile creation date, using \"mojang\"", e);
        }


        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("auth_session", profile.accessToken); // For legacy versions of MC
        varArgMap.put("auth_access_token", profile.accessToken);
        varArgMap.put("auth_player_name", username);
        varArgMap.put("auth_uuid", profile.profileId.replace("-", ""));
        varArgMap.put("auth_xuid", profile.xuid);
        varArgMap.put("assets_root", Tools.ASSETS_PATH);
        varArgMap.put("assets_index_name", versionInfo.assets);
        varArgMap.put("game_assets", Tools.ASSETS_PATH + File.separator + "virtual" + File.separator + versionInfo.assets);
        varArgMap.put("game_directory", gameDir.getAbsolutePath());
        varArgMap.put("user_properties", "{}");
        varArgMap.put("user_type", userType);
        varArgMap.put("version_name", versionName);
        varArgMap.put("version_type", versionInfo.type);

        List<String> clientArgs = new ArrayList<>();
        if (versionInfo.arguments != null && versionInfo.arguments.game != null) {
            // Support Minecraft 1.13+
            for (Object arg : versionInfo.arguments.game) {
                if (arg instanceof String) {
                    clientArgs.add((String) arg);
                } //TODO: implement else clause
            }
        }
        if(versionInfo.minecraftArguments != null){
            clientArgs.addAll(splitAndFilterEmpty(versionInfo.minecraftArguments));
        }
        return JSONUtils.insertJSONValueList(clientArgs, varArgMap);
    }

    private static List<String> splitAndFilterEmpty(String argStr) {
        List<String> strList = new ArrayList<>();
        for (String arg : argStr.split(" ")) {
            if (!arg.isEmpty()) {
                strList.add(arg);
            }
        }
        return strList;
    }

    public static @NonNull String pickRuntime(Instance instance, int targetJavaVersion) {
        RuntimeSelector.Resolution resolution = RuntimeManager.resolve(instance.selectedRuntime,
                LauncherPreferences.PREF_DEFAULT_RUNTIME, targetJavaVersion, RuntimeManager.isAutoSelection());
        if(resolution != null) {
            Log.i("GameRunner", "Runtime " + resolution.runtime.name + " for Java " + targetJavaVersion
                    + ": " + resolution.reason);
            if(instance.selectedRuntime != null && !instance.selectedRuntime.equals(resolution.runtime.name)) {
                instance.selectedRuntime = resolution.runtime.name;
                instance.maybeWrite();
            }
            return resolution.runtime.name;
        }
        // No complete runtime is new enough, the old lookup reports the problem
        return pickRuntimeLegacy(instance, targetJavaVersion);
    }

    private static @NonNull String pickRuntimeLegacy(Instance instance, int targetJavaVersion) {
        String runtime = Tools.getSelectedRuntime(instance);
        String profileRuntime = instance.selectedRuntime;
        Runtime pickedRuntime = MultiRTUtils.read(runtime);
        if(runtime == null || pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
            String preferredRuntime = MultiRTUtils.getNearestJreName(targetJavaVersion);
            if(preferredRuntime == null) throw new RuntimeException("Failed to autopick runtime!");
            if(profileRuntime != null) {
                instance.selectedRuntime = preferredRuntime;
                instance.maybeWrite();
            }
            runtime = preferredRuntime;
        }
        return runtime;
    }
}
