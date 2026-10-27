package ai.hermes.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity implements ProcessManager.LogListener, ProcessManager.StateListener {

    private HermesService hermesService;
    private boolean isBound = false;

    // UI elements
    private View topStatusDot;
    private TextView topStatusBadge;
    private ImageButton btnQuickRestart;

    // Containers
    private View tabDashboard;
    private View tabGateway;
    private View tabLogs;
    private View tabSettings;

    // Navigation items
    private TextView navTextDashboard, navTextGateway, navTextLogs, navTextSettings;

    // Dashboard Tab
    private WebView webViewDashboard;
    private View dashboardLoader;
    private TextView loaderText;

    // Gateway Tab
    private TextView tvDaemonState;
    private TextView tvDaemonDetails;
    private TextView tvNetworkStatus;
    private Button btnServiceToggle;
    private Button btnServiceRestart;

    // Logs Tab
    private TextView tvTerminalLogs;
    private ScrollView logsScrollView;
    private Button btnClearLogs, btnCopyLogs;
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LENGTH = 100000;

    // Settings Tab
    private CheckBox cbAutostartBoot;
    private CheckBox cbWakelock;
    private Button btnRequestBattery;
    private EditText etConfigEnv;
    private Button btnSaveConfig;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isDashboardLoaded = false;
    private static final String DASHBOARD_URL = "http://127.0.0.1:9119/";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            HermesService.LocalBinder b = (HermesService.LocalBinder) binder;
            hermesService = b.getService();
            isBound = true;

            hermesService.getProcessManager().addLogListener(MainActivity.this);
            hermesService.getProcessManager().setStateListener(MainActivity.this);

            updateUIState(hermesService.getProcessManager().isRunning(),
                          hermesService.getProcessManager().getPid(),
                          hermesService.getCurrentStateMessage());

            checkAndLoadDashboard();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            hermesService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigation();
        setupWebView();
        loadSettingsConfig();

        // Start Foreground Service
        Intent serviceIntent = new Intent(this, HermesService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        topStatusDot = findViewById(R.id.top_status_dot);
        topStatusBadge = findViewById(R.id.top_status_badge);
        btnQuickRestart = findViewById(R.id.btn_quick_restart);

        tabDashboard = findViewById(R.id.tab_container_dashboard);
        tabGateway = findViewById(R.id.tab_container_gateway);
        tabLogs = findViewById(R.id.tab_container_logs);
        tabSettings = findViewById(R.id.tab_container_settings);

        navTextDashboard = findViewById(R.id.nav_text_dashboard);
        navTextGateway = findViewById(R.id.nav_text_gateway);
        navTextLogs = findViewById(R.id.nav_text_logs);
        navTextSettings = findViewById(R.id.nav_text_settings);

        webViewDashboard = findViewById(R.id.webview_dashboard);
        dashboardLoader = findViewById(R.id.dashboard_loader);
        loaderText = findViewById(R.id.loader_text);

        tvDaemonState = findViewById(R.id.tv_daemon_state);
        tvDaemonDetails = findViewById(R.id.tv_daemon_details);
        tvNetworkStatus = findViewById(R.id.tv_network_status);
        btnServiceToggle = findViewById(R.id.btn_service_toggle);
        btnServiceRestart = findViewById(R.id.btn_service_restart);

        tvTerminalLogs = findViewById(R.id.tv_terminal_logs);
        logsScrollView = findViewById(R.id.logs_scrollview);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);

        cbAutostartBoot = findViewById(R.id.cb_autostart_boot);
        cbWakelock = findViewById(R.id.cb_wakelock);
        btnRequestBattery = findViewById(R.id.btn_request_battery);
        etConfigEnv = findViewById(R.id.et_config_env);
        btnSaveConfig = findViewById(R.id.btn_save_config);

        btnQuickRestart.setOnClickListener(v -> {
            if (hermesService != null) {
                hermesService.getProcessManager().restart();
                reloadDashboard();
            }
        });

        btnServiceToggle.setOnClickListener(v -> {
            if (hermesService != null) {
                if (hermesService.getProcessManager().isRunning()) {
                    hermesService.getProcessManager().stop();
                } else {
                    hermesService.getProcessManager().start();
                }
            }
        });

        btnServiceRestart.setOnClickListener(v -> {
            if (hermesService != null) {
                hermesService.getProcessManager().restart();
                reloadDashboard();
            }
        });

        btnClearLogs.setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvTerminalLogs.setText("");
        });

        btnCopyLogs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Hermes Logs", logBuffer.toString());
            cm.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        btnRequestBattery.setOnClickListener(v -> requestBatteryOptimizationExemption());
        btnSaveConfig.setOnClickListener(v -> saveConfigAndRestart());
    }

    private void setupNavigation() {
        findViewById(R.id.nav_item_dashboard).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.nav_item_gateway).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.nav_item_logs).setOnClickListener(v -> switchTab(2));
        findViewById(R.id.nav_item_settings).setOnClickListener(v -> switchTab(3));
    }

    private void switchTab(int index) {
        tabDashboard.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        tabGateway.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabLogs.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        int colorSelected = getResources().getColor(R.color.tab_selected);
        int colorUnselected = getResources().getColor(R.color.tab_unselected);

        navTextDashboard.setTextColor(index == 0 ? colorSelected : colorUnselected);
        navTextGateway.setTextColor(index == 1 ? colorSelected : colorUnselected);
        navTextLogs.setTextColor(index == 2 ? colorSelected : colorUnselected);
        navTextSettings.setTextColor(index == 3 ? colorSelected : colorUnselected);

        if (index == 0 && !isDashboardLoaded) {
            checkAndLoadDashboard();
        }
    }

    private void setupWebView() {
        WebSettings ws = webViewDashboard.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webViewDashboard.setWebChromeClient(new WebChromeClient());
        webViewDashboard.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.contains("9119") || url.contains("127.0.0.1"))) {
                    isDashboardLoaded = true;
                    dashboardLoader.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    isDashboardLoaded = false;
                    dashboardLoader.setVisibility(View.VISIBLE);
                    loaderText.setText("Connecting to Hermes Web Server…");
                    mainHandler.postDelayed(() -> {
                        if (hermesService != null && hermesService.getProcessManager().isRunning()) {
                            checkAndLoadDashboard();
                        }
                    }, 2000);
                }
            }
        });
    }

    private void checkAndLoadDashboard() {
        if (isDashboardLoaded) return;
        new Thread(() -> {
            boolean connected = false;
            for (int i = 0; i < 40; i++) {
                try {
                    java.net.Socket s = new java.net.Socket("127.0.0.1", 9119);
                    s.close();
                    connected = true;
                    break;
                } catch (Exception ignored) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored2) {}
                }
            }
            if (connected) {
                mainHandler.post(() -> {
                    if (!isDashboardLoaded) {
                        webViewDashboard.loadUrl(DASHBOARD_URL);
                    }
                });
            }
        }).start();
    }

    private void reloadDashboard() {
        isDashboardLoaded = false;
        dashboardLoader.setVisibility(View.VISIBLE);
        loaderText.setText("Restarting Hermes Gateway…");
        mainHandler.postDelayed(() -> webViewDashboard.loadUrl(DASHBOARD_URL), 2500);
    }

    private void updateUIState(boolean isRunning, int pid, String message) {
        mainHandler.post(() -> {
            if (isRunning) {
                topStatusDot.setBackgroundResource(R.drawable.dot_green);
                topStatusBadge.setText("Online :" + 9119);
                topStatusBadge.setTextColor(getResources().getColor(R.color.status_green));

                tvDaemonState.setText("Running");
                tvDaemonState.setTextColor(getResources().getColor(R.color.status_green));
                tvDaemonDetails.setText("Port: 9119 (HTTP/WebSocket)\nPID: " + pid + "\nMode: 24/7 Foreground Service\nResumption: Active");

                btnServiceToggle.setText("Stop Service");
                btnServiceToggle.setBackgroundResource(R.drawable.btn_secondary);
            } else {
                topStatusDot.setBackgroundResource(R.drawable.dot_red);
                topStatusBadge.setText("Stopped");
                topStatusBadge.setTextColor(getResources().getColor(R.color.status_red));

                tvDaemonState.setText("Stopped");
                tvDaemonState.setTextColor(getResources().getColor(R.color.status_red));
                tvDaemonDetails.setText("Port: 9119 (Offline)\nPID: ---\nStatus: " + message);

                btnServiceToggle.setText("Start Service");
                btnServiceToggle.setBackgroundResource(R.drawable.btn_primary);
            }

            boolean online = hermesService != null && hermesService.isNetworkOnline();
            tvNetworkStatus.setText(online ?
                "Network: Connected (Online)\nAuto-Resume: Active (Automatically resumes all channels upon reconnect)" :
                "Network: Disconnected (Offline)\nAuto-Resume: Waiting for connection to resume…");
        });
    }

    @Override
    public void onLogLine(String line) {
        mainHandler.post(() -> {
            if (logBuffer.length() > MAX_LOG_LENGTH) {
                logBuffer.delete(0, MAX_LOG_LENGTH / 4);
            }
            logBuffer.append(line).append("\n");
            tvTerminalLogs.setText(logBuffer.toString());
            logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onProcessStateChanged(boolean isRunning, int pid, String message) {
        updateUIState(isRunning, pid, message);
    }

    private void loadSettingsConfig() {
        SharedPreferences prefs = getSharedPreferences(HermesApp.PREF_NAME, MODE_PRIVATE);
        cbAutostartBoot.setChecked(prefs.getBoolean(HermesApp.PREF_AUTOSTART, true));
        cbWakelock.setChecked(prefs.getBoolean(HermesApp.PREF_WAKELOCK, true));

        cbAutostartBoot.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(HermesApp.PREF_AUTOSTART, isChecked).apply();
        });

        cbWakelock.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(HermesApp.PREF_WAKELOCK, isChecked).apply();
            Toast.makeText(this, "Setting saved (effective on next service start)", Toast.LENGTH_SHORT).show();
        });

        // Load .env content
        File envFile = new File(HermesApp.getHermesHome(this), ".env");
        if (envFile.exists()) {
            try (FileInputStream fis = new FileInputStream(envFile)) {
                byte[] data = new byte[(int) envFile.length()];
                fis.read(data);
                etConfigEnv.setText(new String(data, StandardCharsets.UTF_8));
            } catch (Exception e) {
                etConfigEnv.setText("# Error loading .env: " + e.getMessage());
            }
        }
    }

    private void saveConfigAndRestart() {
        File envFile = new File(HermesApp.getHermesHome(this), ".env");
        try (FileOutputStream fos = new FileOutputStream(envFile)) {
            fos.write(etConfigEnv.getText().toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Configuration saved! Restarting gateway…", Toast.LENGTH_SHORT).show();
            if (hermesService != null) {
                hermesService.getProcessManager().restart();
                reloadDashboard();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Battery optimizations are already disabled!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            if (hermesService != null) {
                hermesService.getProcessManager().removeLogListener(this);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
