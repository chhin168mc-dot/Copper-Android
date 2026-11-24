package git.artdeell.mojo.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import git.artdeell.mojo.game.GameActivity;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.NotificationUtils;

import java.lang.ref.WeakReference;

public class GameService extends Service {
    private static final WeakReference<Service> sGameService = new WeakReference<>(null);
    private final LocalBinder mLocalBinder = new LocalBinder();
    private WifiManager.WifiLock mWifiLock;

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        acquireWifiLock();
    }

    /**
     * Asks Android not to put WiFi into power saving mode while the game runs. In the power saving
     * mode packets wait for the next beacon (up to 100 ms), which shows up as lag and rubber-banding
     * in multiplayer. Nothing happens on mobile data (4G/5G) or if the device does not support it.
     */
    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return;
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            WifiManager.WifiLock lock = wifiManager.createWifiLock(mode, "NokorLauncher:game");
            lock.setReferenceCounted(false);
            lock.acquire();
            mWifiLock = lock;
        } catch (Throwable t) {
            Log.w("GameService", "Failed to acquire the WiFi lock", t);
        }
    }

    private void releaseWifiLock() {
        WifiManager.WifiLock lock = mWifiLock;
        mWifiLock = null;
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable t) {
            Log.w("GameService", "Failed to release the WiFi lock", t);
        }
    }

    @Override
    public void onDestroy() {
        releaseWifiLock();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.getBooleanExtra("kill", false)) {
            releaseWifiLock();
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        Intent killIntent = new Intent(getApplicationContext(), GameService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_GAME_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, GameActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                 PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, Tools.notificationChannelId(this))
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .setContentText(getString(R.string.notification_game_runs))
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setOnlyAlertOnce(true)
                .setSound(Tools.notificationSoundUri(this));

        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification);
        }
        return START_NOT_STICKY; // non-sticky so android wont try restarting the game after the user uses the "Quit" button
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        //At this point in time  only the game runs and the user poofed the window, time to die
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    public static class LocalBinder extends Binder {
        public boolean isActive;
    }
}
