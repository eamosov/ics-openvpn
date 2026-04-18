/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * End-to-end OpenVPN handshake probe.
 *
 * Mirrors the Rust implementation in ~/git/openvpn/src/probe.rs: sends
 * P_CONTROL_HARD_RESET_CLIENT_V2 and checks that the server replies with
 * P_CONTROL_HARD_RESET_SERVER_V2. Used to verify a local sidecar (sing-box,
 * ydtun) actually forwards traffic to a reachable OpenVPN server, not just
 * that the local port is listening.
 *
 * Does NOT support tls-auth/tls-crypt/tls-crypt-v2 — the probe packet is
 * unauthenticated and an HMAC-protected server would silently drop it.
 */
public final class OpenVpnProbe {
    // Payload length after TCP length prefix.
    private static final int HARD_RESET_V2_LEN = 14;
    // HARD_RESET_SERVER_V2 opcode byte: (8 << 3).
    private static final byte HARD_RESET_SERVER_V2_OPCODE = 0x40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OpenVpnProbe() {}

    public static boolean probeTcp(int port, int timeoutMs) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), Math.min(timeoutMs, 2000));
            sock.setSoTimeout(timeoutMs);
            byte[] payload = buildHardResetPayload();
            byte[] framed = new byte[2 + payload.length];
            framed[0] = (byte) ((HARD_RESET_V2_LEN >> 8) & 0xff);
            framed[1] = (byte) (HARD_RESET_V2_LEN & 0xff);
            System.arraycopy(payload, 0, framed, 2, payload.length);

            OutputStream out = sock.getOutputStream();
            out.write(framed);
            out.flush();

            DataInputStream in = new DataInputStream(sock.getInputStream());
            byte[] reply = new byte[3];
            in.readFully(reply);
            return reply[2] == HARD_RESET_SERVER_V2_OPCODE;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean probeUdp(int port, int timeoutMs) {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(timeoutMs);
            byte[] payload = buildHardResetPayload();
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            sock.send(new DatagramPacket(payload, payload.length, addr, port));

            byte[] buf = new byte[1500];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            sock.receive(reply);
            return reply.getLength() >= 1 && buf[0] == HARD_RESET_SERVER_V2_OPCODE;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] buildHardResetPayload() {
        byte[] out = new byte[HARD_RESET_V2_LEN];
        out[0] = 0x38; // opcode 7 << 3, key_id = 0
        byte[] session = new byte[8];
        RANDOM.nextBytes(session);
        System.arraycopy(session, 0, out, 1, 8);
        out[9] = 0x00;
        return out;
    }
}
