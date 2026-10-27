package ai.hermes.agent;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

public class HermesService extends Service implements ProcessManager.StateListener, NetworkMonitor.NetworkStateListener {
    public static final String ACTION_START = "ai.hermes.agent.START";
    public static final String ACTION_STOP = "ai.hermes.agent.STOP";
    public static final String ACTION_RESTART = "ai.hermes.agent.RESTART";

    public class LocalBinder extends Binder {
        public HermesService getService() {
            return HermesService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private ProcessManager processManager;
    private NetworkMonitor networkMonitor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String currentStateMessage = "Initializing…";
    private boolean isNetworkOnline = true;

    @Override
    public void onCreate() {
        super.onCreate();
        processManager = new ProcessManager(this);
        processManager.setStateListener(this);

        networkMonitor = new NetworkMonitor(this, this);
        networkMonitor.start();
        isNetworkOnline = networkMonitor.isCurrentlyConnected();

        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(HermesApp.NOTIFICATION_ID, buildNotification("Hermes Gateway Daemon", currentStateMessage));

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_RESTART.equals(action)) {
                processManager.restart();
                return START_STICKY;
            }
        }

        if (!processManager.isRunning()) {
            processManager.start();
        }

        return START_STICKY;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    public String getCurrentStateMessage() {
        return currentStateMessage;
    }

    public boolean isNetworkOnline() {
        return isNetworkOnline;
    }

    private void acquireLocks() {
        SharedPreferences prefs = getSharedPreferences(HermesApp.PREF_NAME, MODE_PRIVATE);
        boolean useWakeLock = prefs.getBoolean(HermesApp.PREF_WAKELOCK, true);

        if (useWakeLock) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesAgent::247DaemonWakeLock");
                    wakeLock.acquire();
                }

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HermesAgent::247WifiLock");
                    wifiLock.acquire();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}

        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent restartIntent = new Intent(this, HermesService.class);
        restartIntent.setAction(ACTION_RESTART);
        PendingIntent restartPending = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String netStatus = isNetworkOnline ? " [Online]" : " [Offline - AutoResume]";

        return new NotificationCompat.Builder(this, HermesApp.CHANNEL_ID)
            .setContentTitle(title + netStatus)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Restart", restartPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String title, String content) {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(HermesApp.NOTIFICATION_ID, buildNotification(title, content));
        }
    }

    @Override
    public void onProcessStateChanged(boolean isRunning, int pid, String message) {
        this.currentStateMessage = message;
        updateNotification(isRunning ? "Hermes Gateway Online" : "Hermes Gateway Stopped", message);
    }

    @Override
    public void onNetworkAvailable() {
        isNetworkOnline = true;
        updateNotification("Hermes Gateway", currentStateMessage);
        if (processManager != null) {
            processManager.onNetworkRestored();
        }
    }

    @Override
    public void onNetworkLost() {
        isNetworkOnline = false;
        updateNotification("Hermes Gateway", "Network Offline (Waiting for Connection…)");
        if (processManager != null) {
            processManager.onNetworkLost();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (processManager != null) {
            processManager.stop();
        }
        if (networkMonitor != null) {
            networkMonitor.stop();
        }
        releaseLocks();
    }
}
