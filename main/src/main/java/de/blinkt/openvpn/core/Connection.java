/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.Locale;

public class Connection implements Serializable, Cloneable {
    public String mServerName = "openvpn.example.com";
    public String mServerPort = "1194";
    public boolean mUseUdp = true;
    public String mCustomConfiguration = "";
    public boolean mUseCustomConfig = false;
    public boolean mEnabled = true;
    public int mConnectTimeout = 0;
    public static final int CONNECTION_DEFAULT_TIMEOUT = 120;
    public ProxyType mProxyType = ProxyType.NONE;
    public String mProxyName = "proxy.example.com";
    public String mProxyPort = "8080";

    public boolean mUseProxyAuth;
    public String mProxyAuthUser = null;
    public String mProxyAuthPassword = null;

    // Tunnel mode selector: NONE, SINGBOX, or YDTUN
    public enum TunnelType {
        NONE,
        SINGBOX,
        YDTUN
    }
    public TunnelType mTunnelType = TunnelType.NONE;

    // sing-box tunnel settings (active when mTunnelType == SINGBOX)
    public String mSingBoxOverrideAddress = "";
    public String mSingBoxOverridePort = "";     // empty = use port from remote
    public String mSingBoxServerPort = "";        // empty = 443
    public String mSingBoxUUID = "";
    public String mSingBoxTlsServerName = "";
    public String mSingBoxTlsPublicKey = "";
    public String mSingBoxTlsShortId = "";

    // ydtun/Telemost tunnel settings (active when mTunnelType == YDTUN)
    public String mYdtunTelemostCcUrl = "";     // Telemost control channel URL
    public String mYdtunDisplayName = "";       // Human-readable ydtun participant name
    public String mYdtunTunnelKey = "";          // encryption key (hex or passphrase)
    public boolean mYdtunForceTcpRelay = false;  // force TURN TCP relay instead of UDP

    // Log level: 0=info (default), 1=debug (-v), 2=trace (-vv)
    public int mYdtunLogLevel = 0;

    public String mYdtunTunnelId = "";
    public String mYdtunMaxBw = "";
    public String mYdtunMaxFrameBudget = "";
    public String mYdtunMaxFps = "";

    public boolean isSingBoxEnabled() { return mTunnelType == TunnelType.SINGBOX; }
    public boolean isYdtunEnabled() { return mTunnelType == TunnelType.YDTUN; }

    public enum ProxyType {
        NONE,
        HTTP,
        SOCKS5,
        ORBOT
    }

    private static final long serialVersionUID = 92031902903829090L;


    public String getConnectionBlock(boolean isOpenVPN3) {
        return getConnectionBlock(isOpenVPN3, -1, -1);
    }

    public String getConnectionBlock(boolean isOpenVPN3, int singBoxLocalPort) {
        return getConnectionBlock(isOpenVPN3, singBoxLocalPort, -1);
    }

    /**
     * Generate connection block. Tunnel orchestration is handled by the wrapper,
     * so the app always emits the original remote and passes tunnel settings as
     * wrapper directives.
     */
    public String getConnectionBlock(boolean isOpenVPN3, int singBoxLocalPort, int ydtunLocalPort) {
        StringBuilder cfg = new StringBuilder();

        cfg.append("remote ");
        cfg.append(mServerName);
        cfg.append(" ");
        cfg.append(mServerPort);
        if (mUseUdp)
            cfg.append(" udp\n");
        else
            cfg.append(" tcp-client\n");

        if (mConnectTimeout != 0)
            cfg.append(String.format(Locale.US, " connect-timeout  %d\n", mConnectTimeout));

        // OpenVPN 2.x manages proxy connection via management interface
        if ((isOpenVPN3 || usesExtraProxyOptions()) && mProxyType == ProxyType.HTTP)
        {
            cfg.append(String.format(Locale.US,"http-proxy %s %s\n", mProxyName, mProxyPort));
            if (mUseProxyAuth)
                cfg.append(String.format(Locale.US, "<http-proxy-user-pass>\n%s\n%s\n</http-proxy-user-pass>\n", mProxyAuthUser, mProxyAuthPassword));
        }
        if (usesExtraProxyOptions() && mProxyType == ProxyType.SOCKS5) {
            cfg.append(String.format(Locale.US,"socks-proxy %s %s\n", mProxyName, mProxyPort));
        }

        if (!TextUtils.isEmpty(mCustomConfiguration) && mUseCustomConfig) {
            cfg.append(mCustomConfiguration);
            cfg.append("\n");
        }

        // Do not emit sb_enable/telemost_enable here. The wrapper mode is selected by
        // the launcher via the TUNNELBLICK_CONNECTION_TYPE environment override; any
        // enable markers already present in the imported/custom config are preserved above.
        appendWrapperDirective(cfg, "sb_override_address", mSingBoxOverrideAddress);
        appendWrapperDirective(cfg, "sb_override_port", mSingBoxOverridePort);
        appendWrapperDirective(cfg, "sb_server_port", mSingBoxServerPort);
        appendWrapperDirective(cfg, "sb_uuid", mSingBoxUUID);
        appendWrapperDirective(cfg, "sb_tls_server_name", mSingBoxTlsServerName);
        appendWrapperDirective(cfg, "sb_tls_public_key", mSingBoxTlsPublicKey);
        appendWrapperDirective(cfg, "sb_tls_short_id", mSingBoxTlsShortId);

        appendWrapperDirective(cfg, "telemost_cc_url", mYdtunTelemostCcUrl);
        appendWrapperDirective(cfg, "telemost_display_name", mYdtunDisplayName);
        appendWrapperDirective(cfg, "telemost_tunnel_key", mYdtunTunnelKey);
        appendWrapperDirective(cfg, "telemost_tunnel_id", mYdtunTunnelId);
        appendWrapperDirective(cfg, "telemost_max_bw", mYdtunMaxBw);
        if (mYdtunForceTcpRelay)
            cfg.append("setenv-safe telemost_force_tcp_relay true\n");
        appendWrapperDirective(cfg, "telemost_max_frame_budget", mYdtunMaxFrameBudget);
        appendWrapperDirective(cfg, "telemost_max_fps", mYdtunMaxFps);
        if (mYdtunLogLevel > 0)
            cfg.append(String.format(Locale.US, "setenv-safe telemost_log_level %d\n", mYdtunLogLevel));


        return cfg.toString();
    }

    private static void appendWrapperDirective(StringBuilder cfg, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            cfg.append("setenv-safe ")
                    .append(key)
                    .append(" ")
                    .append(value)
                    .append("\n");
        }
    }

    public boolean usesExtraProxyOptions() {
        return (mUseCustomConfig && mCustomConfiguration.contains("http-proxy-option "));
    }


    @Override
    public Connection clone() throws CloneNotSupportedException {
        return (Connection) super.clone();
    }

    public boolean isOnlyRemote() {
        return TextUtils.isEmpty(mCustomConfiguration) || !mUseCustomConfig;
    }

    /**
     * Returns the effective override port for sing-box inbound.
     * If sb_override_port is not set, uses the port from remote.
     */
    public int getSingBoxOverridePort() {
        if (!TextUtils.isEmpty(mSingBoxOverridePort)) {
            try {
                return Integer.parseInt(mSingBoxOverridePort);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            return Integer.parseInt(mServerPort);
        } catch (NumberFormatException ignored) {
            return 1194;
        }
    }

    /**
     * Returns the effective VLESS server port.
     * If sb_server_port is not set, defaults to 443.
     */
    public int getSingBoxServerPort() {
        if (!TextUtils.isEmpty(mSingBoxServerPort)) {
            try {
                return Integer.parseInt(mSingBoxServerPort);
            } catch (NumberFormatException ignored) {
            }
        }
        return 443;
    }

    public int getTimeout() {
        if (mConnectTimeout <= 0)
            return CONNECTION_DEFAULT_TIMEOUT;
        else
            return mConnectTimeout;
    }
}
