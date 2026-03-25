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
    public String mYdtunTelemostUrls = "";      // comma-separated Telemost meeting URLs
    public String mYdtunTunnelKey = "";          // encryption key (hex or passphrase)
    public String mYdtunTunnelId = "0";          // tunnel ID, default 0

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
     * Generate connection block. If a tunnel (sing-box or ydtun) is enabled and its
     * local port > 0, override remote to 127.0.0.1:{port} tcp-client.
     */
    public String getConnectionBlock(boolean isOpenVPN3, int singBoxLocalPort, int ydtunLocalPort) {
        String cfg = "";

        // Server Address — tunnel override
        if (isSingBoxEnabled() && singBoxLocalPort > 0) {
            cfg += "remote 127.0.0.1 " + singBoxLocalPort + " tcp-client\n";
        } else if (isYdtunEnabled() && ydtunLocalPort > 0) {
            cfg += "remote 127.0.0.1 " + ydtunLocalPort + " tcp-client\n";
        } else {
            cfg += "remote ";
            cfg += mServerName;
            cfg += " ";
            cfg += mServerPort;
            if (mUseUdp)
                cfg += " udp\n";
            else
                cfg += " tcp-client\n";
        }

        if (mConnectTimeout != 0)
            cfg += String.format(Locale.US, " connect-timeout  %d\n", mConnectTimeout);

        // OpenVPN 2.x manages proxy connection via management interface
        if ((isOpenVPN3 || usesExtraProxyOptions()) && mProxyType == ProxyType.HTTP)
        {
            cfg+=String.format(Locale.US,"http-proxy %s %s\n", mProxyName, mProxyPort);
            if (mUseProxyAuth)
                cfg+=String.format(Locale.US, "<http-proxy-user-pass>\n%s\n%s\n</http-proxy-user-pass>\n", mProxyAuthUser, mProxyAuthPassword);
        }
        if (usesExtraProxyOptions() && mProxyType == ProxyType.SOCKS5) {
            cfg+=String.format(Locale.US,"socks-proxy %s %s\n", mProxyName, mProxyPort);
        }

        if (!TextUtils.isEmpty(mCustomConfiguration) && mUseCustomConfig) {
            cfg += mCustomConfiguration;
            cfg += "\n";
        }


        return cfg;
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
