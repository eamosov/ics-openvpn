/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener;

import android.net.NetworkRequest;
import android.os.HandlerThread;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

import static de.blinkt.openvpn.core.OpenVPNManagement.pauseReason;

public class DeviceStateReceiver extends BroadcastReceiver implements ByteCountListener, OpenVPNManagement.PausedStateCallback {
    private final Handler mDisconnectHandler;
    private int lastNetwork = -1;
    private OpenVPNManagement mManagement;
    private ConnectivityManager.NetworkCallback mWifiCallback;
    private Context mContext;
    private static volatile String sCachedWifiSsid;
    private static CountDownLatch sWifiSsidLatch;
    private static volatile boolean sTrustedWifiStandby = false;
    private HandlerThread mWifiHandlerThread;

    /** Called by VPN thread when it exits trusted WiFi wait loop and starts connecting */
    public static void clearTrustedWifiStandby() {
        sTrustedWifiStandby = false;
    }

    /** Check if VPN is in trusted WiFi standby mode (should not start proxy tunnels) */
    public static boolean isTrustedWifiStandby() {
        return sTrustedWifiStandby;
    }

    /** Check if currently connected to a trusted WiFi for the given profile */
    public static boolean isOnTrustedWifi(Context context, VpnProfile profile) {
        if (profile == null || profile.mTrustedWifiList == null || profile.mTrustedWifiList.isEmpty())
            return false;
        String ssid = getCurrentWifiSsid(context);
        return ssid != null && profile.mTrustedWifiList.contains(ssid);
    }

    // Window time in s
    private final int TRAFFIC_WINDOW = 60;
    // Data traffic limit in bytes
    private final long TRAFFIC_LIMIT = 64 * 1024;

    // Time to wait after network disconnect to pause the VPN
    private final int DISCONNECT_WAIT = 20;

    connectState network = connectState.DISCONNECTED;
    connectState screen = connectState.SHOULDBECONNECTED;
    connectState userpause = connectState.SHOULDBECONNECTED;
    connectState trustedWifi = connectState.SHOULDBECONNECTED;

    private String lastStateMsg = null;
    private final java.lang.Runnable mDelayDisconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!(network == connectState.PENDINGDISCONNECT))
                return;

            network = connectState.DISCONNECTED;

            // Set screen state to be disconnected if disconnect pending
            if (screen == connectState.PENDINGDISCONNECT)
                screen = connectState.DISCONNECTED;

            mManagement.pause(getPauseReason());
        }
    };
    private NetworkInfo lastConnectedNetwork;

    @Override
    public boolean shouldBeRunning() {
        return shouldBeConnected();
    }

    private enum connectState {
        SHOULDBECONNECTED,
        PENDINGDISCONNECT,
        DISCONNECTED
    }

    private static class Datapoint {
        private Datapoint(long t, long d) {
            timestamp = t;
            data = d;
        }

        long timestamp;
        long data;
    }

    private final LinkedList<Datapoint> trafficdata = new LinkedList<>();


    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        if (screen != connectState.PENDINGDISCONNECT)
            return;

        long total = diffIn + diffOut;
        trafficdata.add(new Datapoint(System.currentTimeMillis(), total));

        while (trafficdata.getFirst().timestamp <= (System.currentTimeMillis() - TRAFFIC_WINDOW * 1000)) {
            trafficdata.removeFirst();
        }

        long windowtraffic = 0;
        for (Datapoint dp : trafficdata)
            windowtraffic += dp.data;

        if (windowtraffic < TRAFFIC_LIMIT) {
            screen = connectState.DISCONNECTED;
            VpnStatus.logInfo(R.string.screenoff_pause,
                    "64 kB", TRAFFIC_WINDOW);

            mManagement.pause(getPauseReason());
        }
    }


    public void userPause(boolean pause) {
        if (pause) {
            userpause = connectState.DISCONNECTED;
            // Check if we should disconnect
            mManagement.pause(getPauseReason());
        } else {
            boolean wereConnected = shouldBeConnected();
            userpause = connectState.SHOULDBECONNECTED;
            if (shouldBeConnected() && !wereConnected)
                mManagement.resume();
            else
                // Update the reason why we currently paused
                mManagement.pause(getPauseReason());
        }
    }

    public DeviceStateReceiver(OpenVPNManagement management) {
        super();
        mManagement = management;
        mManagement.setPauseCallback(this);
        mDisconnectHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Register a NetworkCallback for WiFi changes. This is needed because
     * CONNECTIVITY_ACTION may not fire for WiFi changes when VPN is the active network.
     */
    public void registerWifiCallback(Context context) {
        mContext = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return;

        sWifiSsidLatch = new CountDownLatch(1);

        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        mWifiCallback = new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onAvailable(Network network) {
                // Don't trigger networkStateChange here - SSID is not yet available.
                // onCapabilitiesChanged will fire shortly with SSID info.
                android.util.Log.i("WifiCB", "onAvailable: network=" + network);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.net.TransportInfo ti = caps.getTransportInfo();
                    android.util.Log.i("WifiCB", "onCapabilitiesChanged: ti=" + ti + " tiClass=" + (ti != null ? ti.getClass().getName() : "null"));
                    if (ti instanceof WifiInfo) {
                        WifiInfo wi = (WifiInfo) ti;
                        String ssid = wi.getSSID();
                        android.util.Log.i("WifiCB", "onCapabilitiesChanged: rawSsid=" + ssid);
                        if (ssid != null) {
                            ssid = ssid.replace("\"", "");
                            if (!ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
                                sCachedWifiSsid = ssid;
                                CountDownLatch latch = sWifiSsidLatch;
                                if (latch != null) latch.countDown();
                                mDisconnectHandler.post(() -> networkStateChange(mContext));
                                return;
                            }
                        }
                    }
                    // SSID not yet available (<unknown ssid>), do NOT signal latch
                    // Latch will be signaled when real SSID arrives or onLost fires
                }
            }

            @Override
            public void onLost(Network network) {
                sCachedWifiSsid = null;
                CountDownLatch latch = sWifiSsidLatch;
                if (latch != null) latch.countDown();
                mDisconnectHandler.post(() -> networkStateChange(mContext));
            }
        };

        // Use a dedicated handler thread so callbacks aren't blocked by main thread
        try {
            mWifiHandlerThread = new HandlerThread("WifiCallbackThread");
            mWifiHandlerThread.start();
            cm.registerNetworkCallback(wifiRequest, mWifiCallback,
                    new Handler(mWifiHandlerThread.getLooper()));
            android.util.Log.i("DSR", "registerWifiCallback: successfully registered, thread=" + mWifiHandlerThread.getId());
        } catch (Exception e) {
            android.util.Log.e("DSR", "registerWifiCallback: FAILED", e);
        }
    }

    public void unregisterWifiCallback() {
        android.util.Log.i("DSR", "unregisterWifiCallback called");
        if (mWifiCallback != null && mContext != null) {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(mWifiCallback);
            }
            mWifiCallback = null;
        }
        if (mWifiHandlerThread != null) {
            mWifiHandlerThread.quitSafely();
            mWifiHandlerThread = null;
        }
        sCachedWifiSsid = null;
        // Don't clear sTrustedWifiStandby here - it's cleared explicitly
        // when leaving trusted WiFi or when VPN thread starts connecting
    }


    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);


        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            networkStateChange(context);
        } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            boolean screenOffPause = prefs.getBoolean("screenoff", false);

            if (screenOffPause) {
                if (ProfileManager.getLastConnectedVpn() != null && !ProfileManager.getLastConnectedVpn().mPersistTun)
                    VpnStatus.logError(R.string.screen_nopersistenttun);

                screen = connectState.PENDINGDISCONNECT;
                fillTrafficData();
                if (network == connectState.DISCONNECTED || userpause == connectState.DISCONNECTED)
                    screen = connectState.DISCONNECTED;
            }
        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            // Network was disabled because screen off
            boolean connected = shouldBeConnected();
            screen = connectState.SHOULDBECONNECTED;

            /* We should connect now, cancel any outstanding disconnect timer */
            mDisconnectHandler.removeCallbacks(mDelayDisconnectRunnable);
            /* should be connected has changed because the screen is on now, connect the VPN */
            if (shouldBeConnected() != connected)
                mManagement.resume();
            else if (!shouldBeConnected())
                /*Update the reason why we are still paused */
                mManagement.pause(getPauseReason());

        }
    }

    private void fillTrafficData() {
        trafficdata.add(new Datapoint(System.currentTimeMillis(), TRAFFIC_LIMIT));
    }

    public static boolean equalsObj(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    public void networkStateChange(Context context) {
        android.util.Log.i("DSR", "networkStateChange called");
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);
        boolean ignoreNetworkState = prefs.getBoolean("ignorenetstate", false);
        if (ignoreNetworkState) {
            network = connectState.SHOULDBECONNECTED;
            return;
        }

        NetworkInfo networkInfo = getCurrentNetworkInfo(context);
        boolean sendusr1 = prefs.getBoolean("netchangereconnect", true);

        String netstatestring;
        if (networkInfo == null) {
            netstatestring = "not connected";
        } else {
            String subtype = networkInfo.getSubtypeName();
            if (subtype == null)
                subtype = "";
            String extrainfo = networkInfo.getExtraInfo();
            if (extrainfo == null)
                extrainfo = "";

			/*
            if(networkInfo.getType()==android.net.ConnectivityManager.TYPE_WIFI) {
				WifiManager wifiMgr = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
				WifiInfo wifiinfo = wifiMgr.getConnectionInfo();
				extrainfo+=wifiinfo.getBSSID();

				subtype += wifiinfo.getNetworkId();
			}*/


            netstatestring = String.format("%2$s %4$s to %1$s %3$s", networkInfo.getTypeName(),
                    networkInfo.getDetailedState(), extrainfo, subtype);
        }

        if (networkInfo != null && networkInfo.getState() == State.CONNECTED) {
            int newnet = networkInfo.getType();

            boolean pendingDisconnect = (network == connectState.PENDINGDISCONNECT);
            network = connectState.SHOULDBECONNECTED;

            // Check trusted WiFi
            boolean wasOnTrustedWifi = (trustedWifi == connectState.DISCONNECTED);
            checkTrustedWifi(context, networkInfo);
            boolean nowOnTrustedWifi = (trustedWifi == connectState.DISCONNECTED);

            android.util.Log.i("DSR", "networkStateChange: wasOnTrustedWifi=" + wasOnTrustedWifi + " nowOnTrustedWifi=" + nowOnTrustedWifi + " standby=" + sTrustedWifiStandby);
            if (nowOnTrustedWifi && !wasOnTrustedWifi && !sTrustedWifiStandby) {
                android.util.Log.i("DSR", "Stopping VPN - trusted WiFi detected, restarting in standby mode");
                VpnStatus.logInfo(R.string.trusted_wifi_pause);
                // Restart VPN service: this stops the current connection (removes tun/routes)
                // and starts a new thread that will detect trusted WiFi and wait without connecting
                sTrustedWifiStandby = true;
                VpnProfile profile = ProfileManager.getLastConnectedVpn();
                if (profile != null) {
                    VPNLaunchHelper.startOpenVpn(profile, context, "trusted WiFi standby", true);
                }
                lastNetwork = newnet;
                lastConnectedNetwork = networkInfo;
                return;
            }

            boolean sameNetwork;
            if (lastConnectedNetwork == null
                    || lastConnectedNetwork.getType() != networkInfo.getType()
                    || !equalsObj(lastConnectedNetwork.getExtraInfo(), networkInfo.getExtraInfo())
                    )
                sameNetwork = false;
            else
                sameNetwork = true;

            /* Same network, connection still 'established' */
            if (pendingDisconnect && sameNetwork) {
                mDisconnectHandler.removeCallbacks(mDelayDisconnectRunnable);
                // Reprotect the sockets just be sure
                mManagement.networkChange(true);
            } else {
                /* Different network or connection not established anymore */

                if (screen == connectState.PENDINGDISCONNECT)
                    screen = connectState.DISCONNECTED;

                if (wasOnTrustedWifi && !nowOnTrustedWifi) {
                    // Left trusted WiFi - restart VPN service to launch proxy tunnels
                    // (ydtun/sing-box were skipped when entering trusted WiFi)
                    android.util.Log.i("DSR", "Left trusted WiFi, restarting VPN with proxy");
                    VpnStatus.logInfo(R.string.trusted_wifi_resume);
                    sTrustedWifiStandby = false;
                    mDisconnectHandler.removeCallbacks(mDelayDisconnectRunnable);
                    VpnProfile profile = ProfileManager.getLastConnectedVpn();
                    if (profile != null) {
                        VPNLaunchHelper.startOpenVpn(profile, context, "leaving trusted WiFi", true);
                    }
                } else if (shouldBeConnected()) {
                    mDisconnectHandler.removeCallbacks(mDelayDisconnectRunnable);

                    if (pendingDisconnect || !sameNetwork)
                        mManagement.networkChange(sameNetwork);
                    else
                        mManagement.resume();
                }

                lastNetwork = newnet;
                lastConnectedNetwork = networkInfo;
            }
        } else if (networkInfo == null) {
            // Not connected, stop openvpn, set last connected network to no network
            lastNetwork = -1;

            // Check if we left trusted WiFi (WiFi disconnected)
            boolean wasOnTrustedWifi = (trustedWifi == connectState.DISCONNECTED);
            // WiFi is gone, so clear trusted WiFi state
            if (sCachedWifiSsid == null && wasOnTrustedWifi) {
                trustedWifi = connectState.SHOULDBECONNECTED;
                android.util.Log.i("DSR", "Left trusted WiFi (network null), restarting VPN with proxy");
                VpnStatus.logInfo(R.string.trusted_wifi_resume);
                sTrustedWifiStandby = false;
                mDisconnectHandler.removeCallbacks(mDelayDisconnectRunnable);
                VpnProfile profile = ProfileManager.getLastConnectedVpn();
                if (profile != null) {
                    VPNLaunchHelper.startOpenVpn(profile, context, "leaving trusted WiFi (disconnect)", true);
                }
            } else if (sendusr1) {
                network = connectState.PENDINGDISCONNECT;
                mDisconnectHandler.postDelayed(mDelayDisconnectRunnable, DISCONNECT_WAIT * 1000);
            }
        }


        if (!netstatestring.equals(lastStateMsg))
            VpnStatus.logInfo(R.string.netstatus, netstatestring);
        VpnStatus.logDebug(String.format("Debug state info: %s, pause: %s, shouldbeconnected: %s, network: %s ",
                netstatestring, getPauseReason(), shouldBeConnected(), network));
        lastStateMsg = netstatestring;

    }


    public boolean isUserPaused() {
        return userpause == connectState.DISCONNECTED;
    }

    private boolean shouldBeConnected() {
        return (screen == connectState.SHOULDBECONNECTED && userpause == connectState.SHOULDBECONNECTED &&
                network == connectState.SHOULDBECONNECTED && trustedWifi == connectState.SHOULDBECONNECTED);
    }

    private pauseReason getPauseReason() {
        if (userpause == connectState.DISCONNECTED)
            return pauseReason.userPause;

        if (trustedWifi == connectState.DISCONNECTED)
            return pauseReason.trustedWifi;

        if (screen == connectState.DISCONNECTED)
            return pauseReason.screenOff;

        if (network == connectState.DISCONNECTED)
            return pauseReason.noNetwork;

        return pauseReason.userPause;
    }

    /**
     * Get the current WiFi SSID using the best available API for the Android version.
     */
    static String getCurrentWifiSsid(Context context) {
        // Check cached SSID from NetworkCallback first (most reliable on Android 12+)
        String cached = sCachedWifiSsid;
        if (cached != null)
            return cached;

        // Quick check: is WiFi even connected? If not, skip the latch wait.
        boolean wifiConnected = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        wifiConnected = true;
                        break;
                    }
                }
            }
        } catch (SecurityException ignored) {
        }

        // Wait for WiFi callback to deliver SSID if latch is available and WiFi is connected
        if (wifiConnected) {
            CountDownLatch latch = sWifiSsidLatch;
            if (latch != null && latch.getCount() > 0) {
                android.util.Log.i("DSR", "getCurrentWifiSsid: waiting on latch (WiFi connected but SSID not cached yet)");
                try {
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                cached = sCachedWifiSsid;
                if (cached != null)
                    return cached;
            }
        }

        // On Android 12+ (API 31), use ConnectivityManager + NetworkCapabilities to get WifiInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    // Iterate all networks to find WiFi (active network may be VPN)
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            android.net.TransportInfo ti = caps.getTransportInfo();
                            if (ti instanceof WifiInfo) {
                                WifiInfo wifiInfo = (WifiInfo) ti;
                                if (wifiInfo.getSSID() != null) {
                                    String ssid = wifiInfo.getSSID().replace("\"", "");
                                    if (!ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
                                        return ssid;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (SecurityException ignored) {
            }
        }

        // Fallback: try NetworkInfo.getExtraInfo() (works on older APIs)
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI) {
                    String extraInfo = ni.getExtraInfo();
                    if (extraInfo != null) {
                        String ssid = extraInfo.replace("\"", "");
                        if (!ssid.isEmpty()) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        }

        // Last fallback: WifiManager
        try {
            WifiManager wifiMgr = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiMgr != null) {
                WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
                if (wifiInfo != null) {
                    String rawSsid = wifiInfo.getSSID();
                    if (rawSsid != null) {
                        String ssid = rawSsid.replace("\"", "");
                        if (!ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        }

        return null;
    }

    private void checkTrustedWifi(Context context, NetworkInfo networkInfo) {
        VpnProfile profile = ProfileManager.getLastConnectedVpn();
        if (profile == null || profile.mTrustedWifiList == null || profile.mTrustedWifiList.isEmpty()) {
            android.util.Log.i("DSR", "checkTrustedWifi: no trusted list");
            trustedWifi = connectState.SHOULDBECONNECTED;
            return;
        }

        // Always check if WiFi is connected to a trusted network, regardless of
        // which network triggered the event (active network may be Mobile/VPN while WiFi is still connected)
        String ssid = getCurrentWifiSsid(context);
        android.util.Log.i("DSR", "checkTrustedWifi: ssid=" + ssid + " trustedList=" + profile.mTrustedWifiList);
        if (ssid != null && profile.mTrustedWifiList.contains(ssid)) {
            trustedWifi = connectState.DISCONNECTED;
            return;
        }
        trustedWifi = connectState.SHOULDBECONNECTED;
    }

    private NetworkInfo getCurrentNetworkInfo(Context context) {
        ConnectivityManager conn = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        return conn.getActiveNetworkInfo();
    }
}
