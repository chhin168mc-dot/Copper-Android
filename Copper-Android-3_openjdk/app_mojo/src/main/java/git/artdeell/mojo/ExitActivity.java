package git.artdeell.mojo;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import static git.artdeell.mojo.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid") //invalid on some translations but valid on most, cant fix that atm
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        String message = isSignal ? getString(R.string.mcn_abort_title) : getString(R.string.mcn_exit_title, code);

        // The share dialog has to be shown before this activity finishes, so when the user
        // taps "share", finishing is deferred until the share dialog itself is closed.
        final boolean[] sharing = {false};
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.main_share_logs, (dialog, which) -> {
                    sharing[0] = true;
                    shareLog(this, ExitActivity.this::finish);
                })
                .setOnDismissListener(dialog -> {
                    if(!sharing[0]) ExitActivity.this.finish();
                })
                .show();
    }

    @SuppressWarnings("unused") //used by native jre_launcher_new
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        if((!isSignal && code == 0)) {
            if(ctx != null) Tools.restartLauncherActivity(ctx);
            System.exit(0);
            return;
        }

        // A latch keeps the notification even if the UI thread is faster than this thread. With
        // wait/notify the notification could be lost and this thread would wait forever, which left
        // a dead game process behind that blocked the next launch until the app was closed by hand.
        final CountDownLatch launched = new CountDownLatch(1);
        Tools.runOnUiThread(()->{
            try {
                Intent i = new Intent(ctx,ExitActivity.class);
                i.putExtra("code",code);
                i.putExtra("isSignal", isSignal);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t) {
                Log.e("ExitActivity", "Failed to show the exit dialog", t);
            } finally {
                launched.countDown();
            }
        });
        try {
            // Do not wait forever when the UI thread is stuck, the process has to exit in any case
            launched.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e("ExitActivity", "Waiting on the exit dialog failed: "+e);
        }
        System.exit(0);
        // Make sure that no half-dead game process survives if exit hooks of the VM do not return
        Tools.fullyExit();
    }

}
