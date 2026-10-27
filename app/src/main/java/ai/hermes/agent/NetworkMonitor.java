package ai.hermes.agent;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkMonitor {
    public interface NetworkStateListener {
        void onNetworkAvailable();
        void onNetworkLost();
    }

    private final Context context;
    private final NetworkStateListener listener;
    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public NetworkMonitor(Context context, NetworkStateListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);

        this.networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean wasOffline = !isConnected.getAndSet(true);
                if (wasOffline && listener != null) {
                    mainHandler.post(listener::onNetworkAvailable);
                }
            }

            @Override
            public void onLost(Network network) {
                // Check if any other network is active before declaring offline
                boolean active = isCurrentlyConnected();
                isConnected.set(active);
                if (!active && listener != null) {
                    mainHandler.post(listener::onNetworkLost);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                boolean wasOffline = !isConnected.getAndSet(hasInternet);
                if (hasInternet && wasOffline && listener != null) {
                    mainHandler.post(listener::onNetworkAvailable);
                }
            }
        };
    }

    public void start() {
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
            isConnected.set(isCurrentlyConnected());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    public boolean isCurrentlyConnected() {
        try {
            if (connectivityManager == null) return false;
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }
}
