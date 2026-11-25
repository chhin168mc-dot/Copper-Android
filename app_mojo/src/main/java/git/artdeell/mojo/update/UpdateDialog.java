package git.artdeell.mojo.update;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;

/**
 * The update window: version info, the changelog, the download progress with the speed, then the install.
 * A required update has no "Later" and "Skip" buttons and the window cannot be closed.
 */
final class UpdateDialog {
    private static final float MB = 1024f * 1024f;

    private final Activity mActivity;
    private final UpdateManager.Info mInfo;
    private final File mApk;

    private AlertDialog mDialog;
    private View mProgressGroup;
    private TextView mStatus, mAmount, mSpeed;
    private LinearProgressIndicator mProgress;
    private MaterialButton mPrimary, mLater, mSkip;

    private boolean mReady;
    private boolean mCanSkip;
    private boolean mDownloading;
    private AtomicBoolean mCancelled = new AtomicBoolean(false);

    private UpdateDialog(Activity activity, UpdateManager.Info info, boolean ready) {
        mActivity = activity;
        mInfo = info;
        mReady = ready;
        mApk = UpdateManager.apkFile(activity);
    }

    static void show(Activity activity, UpdateManager.Info info, boolean ready, boolean manual) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new UpdateDialog(activity, info, ready).build(manual);
    }

    private void build(boolean manual) {
        View view = LayoutInflater.from(mActivity).inflate(R.layout.dialog_update, null);
        mProgressGroup = view.findViewById(R.id.update_progress_group);
        mStatus = view.findViewById(R.id.update_status);
        mAmount = view.findViewById(R.id.update_progress_amount);
        mSpeed = view.findViewById(R.id.update_progress_speed);
        mProgress = view.findViewById(R.id.update_progress);
        mPrimary = view.findViewById(R.id.update_primary);
        mLater = view.findViewById(R.id.update_later);
        mSkip = view.findViewById(R.id.update_skip);

        ((TextView) view.findViewById(R.id.update_current_version)).setText(BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        ((TextView) view.findViewById(R.id.update_new_version)).setText(mInfo.version);
        if (mInfo.size > 0) {
            ((TextView) view.findViewById(R.id.update_size)).setText(
                    mActivity.getString(R.string.update_size_value, mInfo.size / MB));
        } else {
            view.findViewById(R.id.update_size_row).setVisibility(View.GONE);
        }
        if (mInfo.notes.isEmpty()) {
            view.findViewById(R.id.update_notes_group).setVisibility(View.GONE);
        } else {
            ((TextView) view.findViewById(R.id.update_notes)).setText(mInfo.notes);
        }

        if (mInfo.mandatory) view.findViewById(R.id.update_mandatory).setVisibility(View.VISIBLE);
        mCanSkip = !mInfo.mandatory && !manual;

        mDialog = new MaterialAlertDialogBuilder(mActivity)
                .setView(view)
                .setCancelable(!mInfo.mandatory)
                .create();
        // Leaving the window in the middle of the download stops it
        mDialog.setOnDismissListener(d -> mCancelled.set(true));

        mPrimary.setOnClickListener(v -> {
            if (mReady) UpdateManager.install(mActivity, mInfo, mApk);
            else startDownload();
        });
        mLater.setOnClickListener(v -> {
            if (mDownloading) cancelDownload();
            else mDialog.dismiss();
        });
        mSkip.setOnClickListener(v -> {
            PreferenceManager.getDefaultSharedPreferences(mActivity).edit()
                    .putLong(UpdateManager.PREF_SKIPPED_CODE, mInfo.code).apply();
            mDialog.dismiss();
        });

        showAvailableState();
        mDialog.show();
    }

    private void showAvailableState() {
        mDownloading = false;
        mProgressGroup.setVisibility(View.GONE);
        mPrimary.setEnabled(true);
        mPrimary.setText(mReady ? R.string.update_action_install : R.string.update_action_download);
        showIdleButtons();
    }

    /** The buttons when nothing is downloading. A required update can only be installed. */
    private void showIdleButtons() {
        mLater.setText(R.string.update_action_later);
        mLater.setVisibility(mInfo.mandatory ? View.GONE : View.VISIBLE);
        mSkip.setVisibility(mCanSkip ? View.VISIBLE : View.GONE);
        mDialog.setCancelable(!mInfo.mandatory);
    }

    private void startDownload() {
        mDownloading = true;
        mCancelled = new AtomicBoolean(false);
        final AtomicBoolean cancelled = mCancelled;

        mProgressGroup.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.update_downloading);
        mProgress.setIndeterminate(true);
        mAmount.setText("");
        mSpeed.setText("");
        mPrimary.setEnabled(false);
        mSkip.setVisibility(View.GONE);
        mLater.setVisibility(View.VISIBLE);
        mLater.setText(android.R.string.cancel);
        mDialog.setCancelable(false);

        MojoApplication.sExecutorService.execute(() -> {
            boolean ok = false;
            int error = R.string.update_download_failed;
            try {
                ok = UpdateManager.download(mActivity, mInfo, mApk, cancelled, this::onProgress);
            } catch (UpdateManager.ChecksumException e) {
                error = R.string.update_download_corrupt;
            } catch (Exception e) {
                android.util.Log.w("UpdateDialog", "Update download failed", e);
            }
            final boolean success = ok;
            final int errorText = error;
            UpdateManager.runOnUi(mActivity, () -> onFinished(success, cancelled.get(), errorText));
        });
    }

    private void onProgress(long done, long total, long bytesPerSecond) {
        UpdateManager.runOnUi(mActivity, () -> {
            if (!mDownloading) return;
            if (total > 0) {
                mProgress.setIndeterminate(false);
                mProgress.setProgressCompat((int) (done * 100 / total), true);
                mAmount.setText(mActivity.getString(R.string.update_progress_amount, done / MB, total / MB));
            } else {
                mAmount.setText(mActivity.getString(R.string.update_progress_unknown, done / MB));
            }
            if (bytesPerSecond > 0) {
                mSpeed.setText(bytesPerSecond >= MB
                        ? mActivity.getString(R.string.update_progress_speed, bytesPerSecond / MB)
                        : mActivity.getString(R.string.update_progress_speed_kb, bytesPerSecond / 1024f));
            }
        });
    }

    private void onFinished(boolean success, boolean cancelled, int errorText) {
        if (mActivity.isFinishing() || mActivity.isDestroyed() || !mDialog.isShowing()) return;
        if (cancelled) {
            showAvailableState();
            return;
        }
        mDownloading = false;
        mPrimary.setEnabled(true);
        showIdleButtons();
        if (success) {
            mReady = true;
            mProgress.setIndeterminate(false);
            mProgress.setProgressCompat(100, true);
            mStatus.setText(R.string.update_download_complete);
            mPrimary.setText(R.string.update_action_install);
            UpdateManager.install(mActivity, mInfo, mApk);
        } else {
            mProgressGroup.setVisibility(View.VISIBLE);
            mProgress.setIndeterminate(false);
            mProgress.setProgress(0);
            mStatus.setText(errorText);
            mPrimary.setText(R.string.update_action_retry);
            Toast.makeText(mActivity, errorText, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelDownload() {
        mCancelled.set(true);
    }
}
