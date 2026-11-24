# Nokor: runtime and performance upgrade

Status: **written and checked without a build.** The Android project was not built and nothing was
run on a device, because the environment that made this change has no Gradle, no Android SDK and no
network. See "What was tested" at the end.

## Runtime architecture

The PojavLauncher runtime stays. Nothing was removed or replaced.

| Part | File | Job |
|---|---|---|
| Install, remove, read runtimes | `multirt/MultiRTUtils`, `NewJREUtil`, runtime dialog | unchanged |
| Selection rules | `multirt/RuntimeSelector` | pure rules, no Android |
| Manager | `multirt/RuntimeManager` | usable check, selection, failed-runtime memory, warm-up, text report |
| Start | `utils/jre/GameRunner.pickRuntime` | asks the manager, old lookup kept as fallback |
| JVM arguments | `utils/jre/JvmArgSanitizer`, existing `JvmTuner` | drop arguments the Java version does not accept, then tune |

Selection order: runtime set on the instance (if usable and new enough) → in manual mode the default
runtime → installed runtime with exactly the required Java → lowest usable Java above it. The
required Java comes from the version JSON; `RuntimeSelector.javaForMinecraft` (8 up to 1.16, 16 for
1.17, 17 for 1.18 to 1.20.4, 21 from 1.20.5, 25 for year-named versions) is used only when the JSON has none.

Runtime fallback: runtimes that are incomplete (no JVM library, unreadable `release`) are never
chosen. A runtime whose JVM fails to start is remembered; the next launch skips it when another
runtime can run the version, and the one after that tries it again. A second JVM is not started in
the same process after a failure, that is not safe.

New settings (Settings > Java): runtime selection (automatic / manual), warm-up switch, record launch
timings switch, and a "Runtime and performance report" row.

## What was added for performance

* Java-version-aware argument filter: drops module options and `-Xlog` on Java 8, CMS flags from Java 14,
  `AggressiveOpts` from 12, biased locking from 18, `MaxPermSize`, `ZGenerational` before 21,
  `--illegal-access` from 17. Everything else is kept. (The existing `JvmTuner` already handles heap
  limits, G1 settings and 32 bit address space.)
* Runtime warm-up: reads `libjvm.so`, the CDS archive and up to 64 MB of `lib/modules` into the system page cache in
  a background thread, only when enough RAM is free.
* `DeviceProfile` (CPU, fast cores, RAM, GPU) and `RendererAdvisor` (advice only, never changes the setting).
* `PerfRecorder`: opt-in launch timings, adapted from the idea of the Starling performance reports
  (local only, no personal data, size limit, last 3 files, one change at a time to compare). It measures the
  launcher side only: auth check, renderer checks, runtime pick, argument build. Time inside the game is not measured.

## Installers

The existing Modrinth/CurseForge browse, update, enable/disable and delete screens were kept. Added in
`ModsSearchFragment`: download to `name.part`, check size against the server, check SHA-1 when the version
has one, check the file (`utils/ContentValidator`), then move into place. A file that fails is deleted and the
reason is shown. An identical file already in the folder (also a disabled one) is not downloaded again.
Resource packs must have `pack.mcmeta` in the ZIP root, shader packs a `shaders` folder in the root or in one
wrapping folder. After a shader pack install the player is told that it needs Iris or OptiFine and that the
current renderer may not run it. Mods are only checked as ZIP/JAR because old Forge mods have no descriptor.

## Build

    ./gradlew :app_mojo:assembleDebug      # needs JDK 17 and the Android SDK from build.gradle

## Self-test that runs without Android

    sh tools/perf-selftest/run.sh

## What was tested

* Run: 57 checks of the selection rules, the Minecraft to Java table, the argument filter and the file
  validator (real ZIP files), on JDK 21.
* Compiled with javac against the whole source tree: no error that points to a project class or method of the
  changed files. All remaining errors are Android/AndroidX/Gson/R classes that do not exist outside the SDK.
* **Not done:** Gradle build, APK, installing, starting Minecraft, runtime warm-up timing, installer downloads,
  UI of the new settings. No FPS or startup improvement has been measured, so none is claimed.

## Known limits

* No new JDK was bundled: the environment has no network, and the existing runtimes stay in use.
* Warm-up helps the first start after a restart; its benefit is not measured.
* Mod loader compatibility filtering is the one of the existing Modrinth/CurseForge code.
* Backups of the changed original files are in `_backup_before_perf_upgrade/`.
