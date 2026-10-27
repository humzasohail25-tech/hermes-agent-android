package ai.hermes.agent;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import java.io.File;

public class HermesApp extends Application {
    public static final String CHANNEL_ID = "hermes_gateway_daemon";
    public static final String PREF_NAME = "hermes_prefs";
    public static final String PREF_AUTOSTART = "autostart_boot";
    public static final String PREF_WAKELOCK = "keep_wakelock";
    public static final int NOTIFICATION_ID = 9119;

    private static HermesApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    public static HermesApp getInstance() {
        return instance;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Hermes Agent Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background supervisor for Hermes Agent Gateway & Channels");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static File getAppFilesDir(Context context) {
        return context.getFilesDir();
    }

    public static File getHermesHome(Context context) {
        return new File(getAppFilesDir(context), "hermes_home");
    }

    public static File getUsrDir(Context context) {
        return new File(getAppFilesDir(context), "usr");
    }
}
