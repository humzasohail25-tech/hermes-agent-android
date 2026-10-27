package ai.hermes.agent;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

public class ProcessManager {
    private static final String TAG = "HermesProcess";
    private static final String BUNDLE_STAMP = "bundle_v1.0.6.stamp";

    public interface LogListener {
        void onLogLine(String line);
    }

    public interface StateListener {
        void onProcessStateChanged(boolean isRunning, int pid, String message);
    }

    private final Context context;
    private final File baseDir;
    private final File usrDir;
    private final File agentDir;
    private final File homeDir;
    private final File tmpDir;
    private final File runnerScript;

    private Process process;
    private int currentPid = -1;
    private boolean shouldBeRunning = false;
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private StateListener stateListener;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ProcessManager(Context context) {
        this.context = context.getApplicationContext();
        this.baseDir = context.getFilesDir();
        this.usrDir = new File(baseDir, "usr");
        this.agentDir = new File(baseDir, "hermes-agent");
        this.homeDir = new File(baseDir, "hermes_home");
        this.tmpDir = new File(baseDir, "tmp");
        this.runnerScript = new File(usrDir, "bin/hermes");
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void addLogListener(LogListener listener) {
        if (listener != null && !logListeners.contains(listener)) {
            logListeners.add(listener);
        }
    }

    public void removeLogListener(LogListener listener) {
        logListeners.remove(listener);
    }

    public boolean isExtracted() {
        File stamp = new File(baseDir, BUNDLE_STAMP);
        return stamp.exists() && runnerScript.exists();
    }

    public synchronized void ensureExtracted() throws IOException {
        if (isExtracted()) {
            return;
        }

        emitLog("[System] Extracting Hermes Agent & runtime dependencies (first run)…");
        // Wipe old incomplete usr tree if re-extracting
        deleteRecursively(usrDir);
        baseDir.mkdirs();
        tmpDir.mkdirs();
        homeDir.mkdirs();

        // Extract hermes_bundle from assets (handles both .tar and .tar.gz dynamically)
        InputStream assetIn;
        try {
            assetIn = context.getAssets().open("hermes_bundle.tar");
        } catch (IOException e) {
            assetIn = context.getAssets().open("hermes_bundle.tar.gz");
        }

        BufferedInputStream bis = new BufferedInputStream(assetIn);
        bis.mark(2);
        int b1 = bis.read();
        int b2 = bis.read();
        bis.reset();

        InputStream tarStream = (b1 == 0x1F && b2 == 0x8B) ? new GZIPInputStream(bis) : bis;
        try {
            extractTar(tarStream, baseDir);
        } finally {
            tarStream.close();
        }

        // Fix permissions: chmod 755 on binaries and libraries
        setExecutablePermissions(new File(usrDir, "bin"));
        setExecutablePermissions(new File(usrDir, "lib"));

        // Create launch stamp
        new File(baseDir, BUNDLE_STAMP).createNewFile();
        emitLog("[System] Extraction and permission verification complete!");
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void extractTar(InputStream is, File targetDir) throws IOException {
        byte[] buffer = new byte[8192];
        byte[] header = new byte[512];

        while (true) {
            int read = readFully(is, header, 0, 512);
            if (read < 512) break;

            // Check for end of tar (all zeros)
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) break;

            // Extract file name
            String name = new String(header, 0, 100).trim();
            // Prefix field in ustar
            String prefix = new String(header, 345, 155).trim();
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (name.isEmpty()) continue;

            // Extract size in octal
            long size = 0;
            String sizeStr = new String(header, 124, 12).trim();
            if (!sizeStr.isEmpty()) {
                try {
                    size = Long.parseLong(sizeStr, 8);
                } catch (NumberFormatException ignored) {}
            }

            char typeFlag = (char) header[156];
            File outputFile = new File(targetDir, name);

            if (typeFlag == '5' || name.endsWith("/")) {
                outputFile.mkdirs();
            } else if (typeFlag == '2') {
                String linkTarget = new String(header, 157, 100).trim();
                File parent = outputFile.getParentFile();
                if (parent != null) parent.mkdirs();
                outputFile.delete();
                try {
                    android.system.Os.symlink(linkTarget, outputFile.getAbsolutePath());
                } catch (Exception e) {
                    Log.w(TAG, "Symlink fallback error for " + name + " -> " + linkTarget + ": " + e.getMessage());
                }
            } else {
                File parent = outputFile.getParentFile();
                if (parent != null) parent.mkdirs();
                outputFile.delete();

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    long remaining = size;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buffer.length, remaining);
                        int bytesRead = readFully(is, buffer, 0, toRead);
                        if (bytesRead <= 0) break;
                        fos.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }

                // Tar pads files to 512 byte boundaries
                long pad = 512 - (size % 512);
                if (pad < 512) {
                    long skipped = 0;
                    while (skipped < pad) {
                        long s = is.skip(pad - skipped);
                        if (s <= 0) {
                            if (is.read() == -1) break;
                            skipped++;
                        } else {
                            skipped += s;
                        }
                    }
                }
            }
        }
    }

    private int readFully(InputStream is, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int count = is.read(b, off + total, len - total);
            if (count < 0) return total == 0 ? -1 : total;
            total += count;
        }
        return total;
    }

    private void setExecutablePermissions(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            f.setExecutable(true, false);
            f.setReadable(true, false);
            if (f.isDirectory()) {
                setExecutablePermissions(f);
            }
        }
    }

    public synchronized void start() {
        if (process != null && isProcessAlive(process)) {
            emitLog("[System] Hermes Gateway is already running.");
            return;
        }

        shouldBeRunning = true;
        executor.submit(() -> {
            try {
                ensureExtracted();
                launchDaemon();
            } catch (Exception e) {
                emitLog("[Error] Failed to start Hermes Gateway: " + e.getMessage());
                Log.e(TAG, "Start error", e);
                notifyState(false, -1, "Start failed: " + e.getMessage());
            }
        });
    }

    private void launchDaemon() throws Exception {
        emitLog("[System] Launching Hermes Unified Daemon on port 9119…");

        // Prepare environment
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", runnerScript.getAbsolutePath(), "daemon");
        pb.directory(baseDir);

        Map<String, String> env = pb.environment();
        env.put("HERMES_HOME", homeDir.getAbsolutePath());
        env.put("PYTHONHOME", usrDir.getAbsolutePath());
        env.put("PYTHONPATH", agentDir.getAbsolutePath() + ":" + usrDir.getAbsolutePath() + "/lib/python3.14:" + usrDir.getAbsolutePath() + "/lib/python3.14/site-packages");
        env.put("PATH", usrDir.getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        env.put("LD_PRELOAD", usrDir.getAbsolutePath() + "/lib/libandroid_compat.so:" + usrDir.getAbsolutePath() + "/lib/libpython3.14.so");
        env.put("LD_LIBRARY_PATH", usrDir.getAbsolutePath() + "/lib");
        env.put("SSL_CERT_FILE", usrDir.getAbsolutePath() + "/etc/tls/cert.pem");
        env.put("REQUESTS_CA_BUNDLE", usrDir.getAbsolutePath() + "/etc/tls/cert.pem");
        env.put("CURL_CA_BUNDLE", usrDir.getAbsolutePath() + "/etc/tls/cert.pem");
        env.put("OPENSSL_CONF", usrDir.getAbsolutePath() + "/etc/tls/openssl.cnf");
        env.put("HERMES_NODE", usrDir.getAbsolutePath() + "/bin/node");
        env.put("HERMES_TUI_DIR", agentDir.getAbsolutePath() + "/ui-tui");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("HOME", homeDir.getAbsolutePath());

        pb.redirectErrorStream(true);
        process = pb.start();
        currentPid = getPid(process);

        notifyState(true, currentPid, "Online (PID: " + currentPid + ")");
        emitLog("[System] Process started successfully with PID: " + currentPid);

        // Stream logs in background
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    emitLog(line);
                }
            } catch (IOException ignored) {}

            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException ignored) {}

            emitLog("[System] Hermes Gateway exited with code: " + exitCode);
            notifyState(false, -1, "Stopped (Exit: " + exitCode + ")");

            // Auto-restart watchdog if unexpected exit
            if (shouldBeRunning) {
                emitLog("[Watchdog] Restarting Hermes Gateway in 3 seconds…");
                try {
                    Thread.sleep(3000);
                    if (shouldBeRunning) {
                        launchDaemon();
                    }
                } catch (Exception e) {
                    emitLog("[Watchdog Error] " + e.getMessage());
                }
            }
        });
    }

    public synchronized void stop() {
        shouldBeRunning = false;
        if (process != null) {
            emitLog("[System] Stopping Hermes Gateway Daemon…");
            process.destroy();
            process = null;
            currentPid = -1;
            notifyState(false, -1, "Stopped");
        }
    }

    public synchronized void restart() {
        emitLog("[System] Restarting Hermes Gateway…");
        stop();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
        start();
    }

    /**
     * Called when network connection is restored. Pings the local gateway to resume poller loops immediately.
     */
    public void onNetworkRestored() {
        emitLog("[Network] Connectivity restored: Resuming Hermes gateway and channels…");
        executor.submit(() -> {
            try {
                // Ping local health endpoint to wake up server
                URL url = new URL("http://127.0.0.1:9119/api/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.getResponseCode();
                conn.disconnect();
                emitLog("[Network] Gateway health check responded — channels active.");
            } catch (Exception e) {
                // If the process was frozen, restart it smoothly
                if (shouldBeRunning && (process == null || !isProcessAlive(process))) {
                    emitLog("[Network] Restarting inactive process after network reconnect…");
                    restart();
                }
            }
        });
    }

    public void onNetworkLost() {
        emitLog("[Network] Internet connection lost: Hermes Gateway holding offline state until reconnect…");
    }

    private void emitLog(String line) {
        Log.i(TAG, line);
        for (LogListener l : logListeners) {
            try {
                l.onLogLine(line);
            } catch (Exception ignored) {}
        }
    }

    private void notifyState(boolean isRunning, int pid, String message) {
        if (stateListener != null) {
            stateListener.onProcessStateChanged(isRunning, pid, message);
        }
    }

    public boolean isRunning() {
        return process != null && isProcessAlive(process);
    }

    public int getPid() {
        return currentPid;
    }

    private static boolean isProcessAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static int getPid(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
