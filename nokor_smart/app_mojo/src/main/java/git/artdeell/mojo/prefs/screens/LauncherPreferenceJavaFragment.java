package git.artdeell.mojo.prefs.screens;

import static git.artdeell.mojo.Architecture.is32BitsDevice;
import static git.artdeell.mojo.Tools.getTotalDeviceMemory;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.multirt.MultiRTConfigDialog;
import git.artdeell.mojo.multirt.RuntimeManager;
import git.artdeell.mojo.perf.DeviceProfile;
import git.artdeell.mojo.perf.GameOptimizer;
import git.artdeell.mojo.perf.PerfRecorder;
import git.artdeell.mojo.perf.RendererAdvisor;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.RamInputDialog;

import java.util.List;

public class LauncherPreferenceJavaFragment extends LauncherPreferenceFragment {
    private MultiRTConfigDialog mDialogScreen;
    private final ActivityResultLauncher<Object> mVmInstallLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("xz"), (data)->{
                if(data != null) Tools.installRuntimeFromUri(getContext(), data);
            });

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        int ramAllocation = LauncherPreferences.PREF_RAM_ALLOCATION;
        // Triggers a write for some reason
        addPreferencesFromResource(R.xml.pref_java);

        CustomSeekBarPreference memorySeekbar = requirePreference("allocation",
                CustomSeekBarPreference.class);

        int maxRAM;
        int deviceRam = getTotalDeviceMemory(memorySeekbar.getContext());

        if(is32BitsDevice() || deviceRam < 2048) maxRAM = Math.min(1024, deviceRam);
        else maxRAM = deviceRam - (deviceRam < 3064 ? 800 : 1024); //To have a minimum for the device to breathe

        memorySeekbar.setMaxKeepIncrement(maxRAM);
        memorySeekbar.setValue(ramAllocation);
        memorySeekbar.setSuffix(" MB");

        // Tapping the row lets the user type the amount of RAM instead of using the slider
        final int finalMaxRAM = maxRAM;
        memorySeekbar.setOnRowClickListener(preference ->
                RamInputDialog.show(requireContext(), preference.getValue(),
                        preference.getMinValue(), finalMaxRAM, preference::setValue));

        EditTextPreference editJVMArgs = findPreference("javaArgs");
        if (editJVMArgs != null) {
            editJVMArgs.setOnBindEditTextListener(TextView::setSingleLine);
        }

        requirePreference("install_jre").setOnPreferenceClickListener(preference->{
            openMultiRTDialog();
            return true;
        });

        requirePreference("perf_optimize").setOnPreferenceClickListener(preference->{
            showOptimizeDialog();
            return true;
        });

        requirePreference("perf_overview").setOnPreferenceClickListener(preference->{
            showPerformanceOverview();
            return true;
        });
    }

    /** Works out the changes in the background, then asks the player before anything is written */
    private void showOptimizeDialog() {
        final Context context = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            final DeviceProfile device = DeviceProfile.detect(context);
            final SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
            final List<GameOptimizer.Change> changes = GameOptimizer.plan(new GameOptimizer.Inputs(
                    device.totalRamMb, device.cores, device.is32Bit, device.recommendedHeapMb(),
                    LauncherPreferences.PREF_RAM_ALLOCATION,
                    Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f),
                    LauncherPreferences.PREF_SUSTAINED_PERFORMANCE,
                    prefs.getString(RuntimeManager.PREF_SELECTION, RuntimeManager.SELECTION_AUTO),
                    prefs.getBoolean(RuntimeManager.PREF_PRELOAD, true)));
            final RendererAdvisor.Advice core = RendererAdvisor.advise(context, device, true);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                StringBuilder message = new StringBuilder();
                if (changes.isEmpty()) {
                    message.append(context.getString(R.string.perf_optimize_none));
                } else {
                    message.append(context.getString(R.string.perf_optimize_intro)).append("\n");
                    for (GameOptimizer.Change change : changes) {
                        message.append("\n• ").append(optimizeLabel(context, change.key)).append(": ")
                                .append(optimizeValue(context, change, change.before)).append(" → ")
                                .append(optimizeValue(context, change, change.after));
                    }
                }
                if (!core.rendererId.equals(LauncherPreferences.PREF_RENDERER)) {
                    message.append("\n\n").append(context.getString(R.string.perf_opt_renderer_tip,
                            core.rendererId, core.reason));
                }
                message.append("\n\n").append(context.getString(R.string.perf_optimize_kept));

                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.perf_optimize_title)
                        .setMessage(message.toString());
                if (changes.isEmpty()) {
                    builder.setPositiveButton(android.R.string.ok, null);
                } else {
                    builder.setPositiveButton(R.string.perf_optimize_apply, (d, w) -> {
                        GameOptimizer.apply(prefs, changes);
                        optimizeApplied(context, R.string.perf_optimize_done);
                    }).setNegativeButton(android.R.string.cancel, null);
                }
                if (GameOptimizer.hasBackup(prefs)) {
                    builder.setNeutralButton(R.string.perf_optimize_undo, (d, w) -> {
                        GameOptimizer.restore(prefs);
                        optimizeApplied(context, R.string.perf_optimize_undone);
                    });
                }
                builder.show();
            });
        });
    }

    /** The settings are written: load them and show them in the rows of this screen */
    private void optimizeApplied(Context context, int messageRes) {
        LauncherPreferences.loadPreferences(context);
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        CustomSeekBarPreference memory = findPreference("allocation");
        if (memory != null) memory.setValue(prefs.getInt("allocation", LauncherPreferences.PREF_RAM_ALLOCATION));
        ListPreference selection = findPreference(RuntimeManager.PREF_SELECTION);
        if (selection != null) selection.setValue(prefs.getString(RuntimeManager.PREF_SELECTION, RuntimeManager.SELECTION_AUTO));
        SwitchPreferenceCompat preload = findPreference(RuntimeManager.PREF_PRELOAD);
        if (preload != null) preload.setChecked(prefs.getBoolean(RuntimeManager.PREF_PRELOAD, true));
        Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show();
    }

    private static String optimizeLabel(Context context, String key) {
        switch (key) {
            case GameOptimizer.KEY_MEMORY: return context.getString(R.string.perf_opt_memory);
            case GameOptimizer.KEY_RESOLUTION: return context.getString(R.string.perf_opt_resolution);
            case GameOptimizer.KEY_SUSTAINED: return context.getString(R.string.perf_opt_sustained);
            case GameOptimizer.KEY_RUNTIME_SELECTION: return context.getString(R.string.perf_opt_selection);
            case GameOptimizer.KEY_RUNTIME_PRELOAD: return context.getString(R.string.perf_opt_preload);
            default: return key;
        }
    }

    private static String optimizeValue(Context context, GameOptimizer.Change change, Object value) {
        if (change.type == GameOptimizer.BOOL) {
            return context.getString((Boolean) value ? R.string.perf_opt_on : R.string.perf_opt_off);
        }
        if (GameOptimizer.KEY_MEMORY.equals(change.key)) return value + " MB";
        if (GameOptimizer.KEY_RESOLUTION.equals(change.key)) return value + "%";
        return String.valueOf(value);
    }

    /** Builds the report in the background (the GPU query can take a moment) and shows it */
    private void showPerformanceOverview() {
        final Context context = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            final String text = buildOverview(context);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.perf_overview_title)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.perf_overview_clear, (d, w) -> PerfRecorder.clear())
                        .show();
            });
        });
    }

    private static String buildOverview(Context context) {
        StringBuilder sb = new StringBuilder();
        DeviceProfile device = DeviceProfile.detect(context);
        sb.append(context.getString(R.string.perf_overview_device)).append('\n')
                .append(device.describe()).append("\n\n");
        sb.append(context.getString(R.string.perf_overview_runtimes)).append('\n')
                .append(RuntimeManager.describe(context)).append("\n\n");
        RendererAdvisor.Advice legacy = RendererAdvisor.advise(context, device, false);
        RendererAdvisor.Advice core = RendererAdvisor.advise(context, device, true);
        sb.append(context.getString(R.string.perf_overview_renderer)).append('\n')
                .append("Minecraft before 1.17: ").append(legacy.rendererId).append('\n')
                .append(legacy.reason).append('\n')
                .append("Minecraft 1.17 and newer: ").append(core.rendererId).append('\n')
                .append(core.reason).append("\n\n");
        sb.append(context.getString(R.string.perf_overview_timings)).append('\n');
        String timings = PerfRecorder.summarize();
        sb.append(timings.isEmpty() ? context.getString(R.string.perf_overview_no_timings) : timings);
        return sb.toString();
    }

    private void openMultiRTDialog() {
        if (mDialogScreen == null) {
            mDialogScreen = new MultiRTConfigDialog();
            mDialogScreen.prepare(getContext(), mVmInstallLauncher);
        }
        mDialogScreen.show();
    }
}
