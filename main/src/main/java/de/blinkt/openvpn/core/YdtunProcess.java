/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YdtunProcess {
    private static final String TAG = "Ydtun";
    private static final int START_TIMEOUT_MS = 30000;
    private static final int PORT_CHECK_INTERVAL_MS = 200;

    private Process mProcess;
    private int mLocalPort;
    private Thread mLogThread;
    private final List<String> mExcludedIps = Collections.synchronizedList(new ArrayList<>());

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

    /**
     * Start ydtun process in port-forward listen mode. Returns local port or -1 on failure.
     */
    public int start(Context context, Connection conn) {
        try {
            mLocalPort = findFreePort();

            String binaryPath = getYdtunPath(context);

            List<String> cmd = new ArrayList<>();
            cmd.add(binaryPath);
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

            if (!TextUtils.isEmpty(conn.mYdtunTunnelId) && !"0".equals(conn.mYdtunTunnelId)) {
                cmd.add("--tunnel-id");
                cmd.add(conn.mYdtunTunnelId);
            }

            VpnStatus.logInfo("ydtun: starting on port " + mLocalPort);
            VpnStatus.logInfo("ydtun: telemost URLs: " + conn.mYdtunTelemostUrls);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
            pb.environment().put("RUST_LOG", "ydtun=info");
            pb.redirectErrorStream(true);

            mProcess = pb.start();

            // Start log reader thread — also parses ROUTE_EXCLUDE lines
            mLogThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(mProcess.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("ROUTE_EXCLUDE: ")) {
                            String ip = line.substring("ROUTE_EXCLUDE: ".length()).trim();
                            if (!ip.isEmpty() && !mExcludedIps.contains(ip)) {
                                mExcludedIps.add(ip);
                                VpnStatus.logInfo("ydtun: route exclude " + ip);
                            }
                        } else {
                            VpnStatus.logInfo("ydtun: " + line);
                        }
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
        if (mProcess != null) {
            VpnStatus.logInfo("ydtun: stopping");
            mProcess.destroy();
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

    /**
     * Returns list of IPs that ydtun connects to (Telemost, TURN servers).
     * These should be excluded from VPN routes.
     */
    public List<String> getExcludedRoutes() {
        return new ArrayList<>(mExcludedIps);
    }

    public int getLocalPort() {
        return mLocalPort;
    }
}
