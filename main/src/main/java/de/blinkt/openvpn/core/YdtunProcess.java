/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


public class YdtunProcess {
    private static final String TAG = "Ydtun";
    private static final int START_TIMEOUT_MS = 30000;
    private static final int PORT_CHECK_INTERVAL_MS = 200;

    private Process mProcess;
    private int mLocalPort;
    private int mApiPort;
    private Thread mLogThread;
    private volatile HttpURLConnection mKcpConnection;
    private String mNetGateway;


    private static String getYdtunPath(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return new File(context.getApplicationInfo().nativeLibraryDir, "libydtun.so").getPath();
        }
        File cached = new File(context.getCacheDir(), "ydtun");
        if (cached.exists() && cached.canExecute()) {
            return cached.getPath();
        }
        throw new RuntimeException("ydtun binary not found");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    public String getNetGateway() {
        return mNetGateway;
    }

    /**
     * Start ydtun process in port-forward listen mode. Returns local port or -1 on failure.
     */
    public int start(Context context, Connection conn) {
        try {
            mLocalPort = findFreePort();
            mApiPort = findFreePort();
            mNetGateway = conn.mYdtunNetGateway;

            String binaryPath = getYdtunPath(context);

            List<String> cmd = new ArrayList<>();
            cmd.add(binaryPath);
            cmd.add("--no-color");
            if (conn.mYdtunLogLevel == 1) {
                cmd.add("-v");
            } else if (conn.mYdtunLogLevel >= 2) {
                cmd.add("-vv");
            }
            cmd.add("--api-addr");
            cmd.add("127.0.0.1:" + mApiPort);
            cmd.add("--mode");
            cmd.add("port-forward");
            cmd.add("--pf-listen");
            cmd.add("127.0.0.1:" + mLocalPort);
            cmd.add("--telemost-urls");
            cmd.add(conn.mYdtunTelemostUrls);

            if (!TextUtils.isEmpty(conn.mYdtunTunnelKey)) {
                cmd.add("--tunnel-key");
                cmd.add(conn.mYdtunTunnelKey);
            }

            if (conn.mYdtunForceTcpRelay) {
                cmd.add("--force-tcp-relay");
            }

            VpnStatus.logInfo("ydtun: starting on port " + mLocalPort);
            VpnStatus.logInfo("ydtun: telemost URLs: " + conn.mYdtunTelemostUrls);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
            pb.redirectErrorStream(true);

            mProcess = pb.start();

            // Start log reader thread
            mLogThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(mProcess.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i(TAG, line);
                        VpnStatus.logInfo("ydtun: " + line);
                    }
                } catch (IOException e) {
                    // Process ended
                }
            }, "YdtunLogThread");
            mLogThread.setDaemon(true);
            mLogThread.start();

            // Wait for port to become available
            if (!waitForPort(mLocalPort, START_TIMEOUT_MS)) {
                VpnStatus.logError("ydtun: failed to start within timeout");
                stop();
                return -1;
            }

            VpnStatus.logInfo("ydtun: started successfully on port " + mLocalPort);
            return mLocalPort;

        } catch (Exception e) {
            VpnStatus.logError("ydtun: failed to start: " + e.getMessage());
            stop();
            return -1;
        }
    }

    private boolean waitForPort(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                socket.close();
                return true;
            } catch (IOException e) {
                // Port not ready yet
            }
            try {
                Thread.sleep(PORT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return false;
            }
            if (mProcess != null) {
                try {
                    int exit = mProcess.exitValue();
                    VpnStatus.logError("ydtun: process exited prematurely with code " + exit);
                    return false;
                } catch (IllegalThreadStateException e) {
                    // Still running, good
                }
            }
        }
        return false;
    }

    public void stop() {
        // Abort any blocking /alive/kcp request first
        HttpURLConnection kcpConn = mKcpConnection;
        if (kcpConn != null) {
            kcpConn.disconnect();
        }

        if (mProcess != null) {
            VpnStatus.logInfo("ydtun: stopping (SIGKILL)");
            mProcess.destroyForcibly();
            try {
                mProcess.waitFor();
            } catch (InterruptedException e) {
                // ignore
            }
            mProcess = null;
        }
        if (mLogThread != null) {
            mLogThread.interrupt();
            mLogThread = null;
        }
    }

    public boolean isRunning() {
        if (mProcess == null)
            return false;
        try {
            mProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public int getLocalPort() {
        return mLocalPort;
    }

    public int getApiPort() {
        return mApiPort;
    }

    /**
     * Quick non-blocking check of ydtun tunnel status via /status endpoint.
     * Returns true if tunnel is alive, false otherwise.
     */
    public boolean checkAlive() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + mApiPort + "/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) return false;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String body = sb.toString();
            return body.contains("\"alive\":true") || body.contains("\"alive\": true");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Wait for ydtun KCP tunnel to become ready via REST API.
     * The /alive/kcp endpoint polls internally (200ms interval, 120s timeout).
     */
    public boolean waitForKcpAlive() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + mApiPort + "/alive/kcp");
            conn = (HttpURLConnection) url.openConnection();
            mKcpConnection = conn;
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(130000); // ydtun polls up to 120s internally
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) {
                VpnStatus.logError("ydtun: /alive/kcp returned HTTP " + code);
                return false;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String body = sb.toString();
            return body.contains("\"alive\":true") || body.contains("\"alive\": true");

        } catch (Exception e) {
            VpnStatus.logError("ydtun: /alive/kcp check failed: " + e.getMessage());
            return false;
        } finally {
            mKcpConnection = null;
            if (conn != null) conn.disconnect();
        }
    }
}
