/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.blinkt.openvpn.R;

public class SingBoxProcess {
    private static final String TAG = "SingBox";
    private static final int START_TIMEOUT_MS = 10000;
    private static final int PORT_CHECK_INTERVAL_MS = 200;

    private Process mProcess;
    private int mLocalPort;
    private String mRemoteServerIp;
    private Thread mLogThread;

    /**
     * Get path to the sing-box binary.
     */
    private static String getSingBoxPath(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return new File(context.getApplicationInfo().nativeLibraryDir, "libsingbox.so").getPath();
        }
        // For older versions, extract from cache
        File cached = new File(context.getCacheDir(), "singbox");
        if (cached.exists() && cached.canExecute()) {
            return cached.getPath();
        }
        throw new RuntimeException("sing-box binary not found");
    }

    /**
     * Generate sing-box JSON config from Connection parameters.
     */
    public static String generateConfig(Connection conn, int localPort) {
        int overridePort = conn.getSingBoxOverridePort();
        int serverPort = conn.getSingBoxServerPort();

        return String.format(Locale.US,
                "{\n" +
                "  \"log\": { \"level\": \"warn\" },\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"direct\",\n" +
                "      \"listen\": \"127.0.0.1\",\n" +
                "      \"listen_port\": %d,\n" +
                "      \"network\": \"%s\",\n" +
                "      \"override_address\": \"%s\",\n" +
                "      \"override_port\": %d\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"vless\",\n" +
                "      \"server\": \"%s\",\n" +
                "      \"server_port\": %d,\n" +
                "      \"uuid\": \"%s\",\n" +
                "      \"flow\": \"\",\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"server_name\": \"%s\",\n" +
                "        \"reality\": {\n" +
                "          \"enabled\": true,\n" +
                "          \"public_key\": \"%s\",\n" +
                "          \"short_id\": \"%s\"\n" +
                "        },\n" +
                "        \"utls\": {\n" +
                "          \"enabled\": true,\n" +
                "          \"fingerprint\": \"chrome\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}",
                localPort,
                conn.mUseUdp ? "udp" : "tcp",
                conn.mSingBoxOverrideAddress,
                overridePort,
                conn.mServerName,
                serverPort,
                conn.mSingBoxUUID,
                conn.mSingBoxTlsServerName,
                conn.mSingBoxTlsPublicKey,
                conn.mSingBoxTlsShortId
        );
    }

    /**
     * Find a free local TCP port.
     */
    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Start sing-box process. Returns local port or -1 on failure.
     */
    public int start(Context context, Connection conn) {
        try {
            mRemoteServerIp = conn.mServerName;
            mLocalPort = findFreePort();

            String config = generateConfig(conn, mLocalPort);

            // Write config to temp file
            File configFile = new File(context.getCacheDir(), "singbox_config.json");
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config);
            }

            String binaryPath = getSingBoxPath(context);

            VpnStatus.logInfo("sing-box: starting on port " + mLocalPort);
            VpnStatus.logInfo("sing-box: VLESS server " + conn.mServerName + ":" + conn.getSingBoxServerPort());
            VpnStatus.logInfo("sing-box: override " + conn.mSingBoxOverrideAddress + ":" + conn.getSingBoxOverridePort());

            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath, "run", "-c", configFile.getAbsolutePath()
            );
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
                        VpnStatus.logInfo("sing-box: " + line);
                    }
                } catch (IOException e) {
                    // Process ended
                }
            }, "SingBoxLogThread");
            mLogThread.setDaemon(true);
            mLogThread.start();

            // Wait for port to become available
            if (!waitForPort(mLocalPort, START_TIMEOUT_MS, conn.mUseUdp)) {
                VpnStatus.logError("sing-box: failed to start within timeout");
                stop();
                return -1;
            }

            VpnStatus.logInfo("sing-box: started successfully on port " + mLocalPort);
            return mLocalPort;

        } catch (Exception e) {
            VpnStatus.logError("sing-box: failed to start: " + e.getMessage());
            stop();
            return -1;
        }
    }

    /**
     * Wait until sing-box forwards an OpenVPN handshake end-to-end.
     */
    private boolean waitForPort(int port, int timeoutMs, boolean udp) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean ok = udp ? OpenVpnProbe.probeUdp(port, 1000) : OpenVpnProbe.probeTcp(port, 2000);
            if (ok) return true;
            try {
                Thread.sleep(PORT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return false;
            }
            // Check if process died
            if (mProcess != null) {
                try {
                    int exit = mProcess.exitValue();
                    VpnStatus.logError("sing-box: process exited prematurely with code " + exit);
                    return false;
                } catch (IllegalThreadStateException e) {
                    // Still running, good
                }
            }
        }
        return false;
    }

    /**
     * Stop sing-box process.
     */
    public void stop() {
        if (mProcess != null) {
            VpnStatus.logInfo("sing-box: stopping");
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

    public String getRemoteServerIp() {
        return mRemoteServerIp;
    }

    public int getLocalPort() {
        return mLocalPort;
    }
}
