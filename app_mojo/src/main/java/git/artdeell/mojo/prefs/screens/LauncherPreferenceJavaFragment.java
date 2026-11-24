package git.artdeell.mojo.prefs.screens;

import static git.artdeell.mojo.Architecture.is32BitsDevice;
import static git.artdeell.mojo.Tools.getTotalDeviceMemory;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.EditTextPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.multirt.MultiRTConfigDialog;
import git.artdeell.mojo.multirt.RuntimeManager;
import git.artdeell.mojo.perf.DeviceProfile;
import git.artdeell.mojo.perf.PerfRecorder;
import git.artdeell.mojo.perf.RendererAdvisor;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.prefs.RamInputDialog;

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

        requirePreference("perf_overview").setOnPreferenceClickListener(preference->{
            showPerformanceOverview();
            return true;
        });
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
