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

    private static AlertDialog sCurrent;
    private static Activity sCurrentActivity;

    private AlertDialog mDialog;
    private View mProgressGroup;
    private TextView mStatus, mAmount, mSpeed;
    private LinearProgressIndicator mProgress;
    private MaterialButton mPrimary, mLater, mSkip;

    private boolean mReady;
    private boolean mCanSkip;
    private boolean mDownloading;
    private volatile boolean mWaiting;
    private AtomicBoolean mCancelled = new AtomicBoolean(false);

    private UpdateDialog(Activity activity, UpdateManager.Info info, boolean ready) {
        mActivity = activity;
        mInfo = info;
        mReady = ready;
        mApk = UpdateManager.apkFile(activity);
    }

    static void show(Activity activity, UpdateManager.Info info, boolean ready, boolean manual) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        // One window is enough, the startup and a manual check must not stack two of them
        if (sCurrent != null && sCurrent.isShowing() && sCurrentActivity != null
                && !sCurrentActivity.isFinishing() && !sCurrentActivity.isDestroyed()) return;
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
            // With an update file only what changed is downloaded, the window tells how much
            ((TextView) view.findViewById(R.id.update_size)).setText(mInfo.delta != null && !mReady
                    ? mActivity.getString(R.string.update_size_delta, mInfo.size / MB, mInfo.delta.size / MB)
                    : mActivity.getString(R.string.update_size_value, mInfo.size / MB));
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
        // Leaving the window in the middle of the download stops it, the part that is there stays for the next time
        mDialog.setOnDismissListener(d -> {
            mCancelled.set(true);
            if (sCurrent == mDialog) {
                sCurrent = null;
                sCurrentActivity = null;
            }
        });

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
        sCurrent = mDialog;
        sCurrentActivity = mActivity;
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
        mWaiting = false;
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
                ok = UpdateManager.download(mActivity, mInfo, mApk, cancelled, new UpdateManager.ProgressCallback() {
                    @Override
                    public void onProgress(long done, long total, long bytesPerSecond) {
                        UpdateDialog.this.onProgress(done, total, bytesPerSecond);
                    }

                    @Override
                    public void onWaiting(boolean waiting) {
                        UpdateDialog.this.onWaiting(waiting);
                    }
                });
            } catch (UpdateManager.ChecksumException e) {
                error = R.string.update_download_corrupt;
            } catch (UpdateManager.StorageException e) {
                error = R.string.update_download_no_space;
            } catch (Exception e) {
                android.util.Log.w("UpdateDialog", "Update download failed", e);
            }
            final boolean success = ok;
            final int errorText = error;
            UpdateManager.runOnUi(mActivity, () -> onFinished(success, cancelled.get(), errorText));
        });
    }

    /** No internet: the window says so and the download goes on by itself when the connection is back */
    private void onWaiting(boolean waiting) {
        if (mWaiting == waiting) return;
        mWaiting = waiting;
        UpdateManager.runOnUi(mActivity, () -> {
            if (!mDownloading) return;
            mStatus.setText(waiting ? R.string.update_waiting_internet : R.string.update_downloading);
            if (waiting) mSpeed.setText("");
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
            // The progress stays where it stopped, "Try again" goes on from there
            mProgressGroup.setVisibility(View.VISIBLE);
            mProgress.setIndeterminate(false);
            mStatus.setText(errorText);
            mPrimary.setText(R.string.update_action_retry);
            Toast.makeText(mActivity, errorText, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelDownload() {
        mCancelled.set(true);
    }
}
