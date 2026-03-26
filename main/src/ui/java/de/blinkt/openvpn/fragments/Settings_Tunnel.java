/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import com.google.android.material.switchmaterial.SwitchMaterial;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.Connection;
import de.blinkt.openvpn.core.Connection.TunnelType;

public class Settings_Tunnel extends Settings_Fragment {

    private RadioGroup mTunnelModeGroup;
    private LinearLayout mSbSettingsGroup;
    private LinearLayout mYdSettingsGroup;

    // Sing-box fields
    private EditText mSbOverrideAddress;
    private EditText mSbOverridePort;
    private EditText mSbServerPort;
    private EditText mSbUuid;
    private EditText mSbTlsServerName;
    private EditText mSbTlsPublicKey;
    private EditText mSbTlsShortId;

    // Ydtun fields
    private EditText mYdTelemostUrls;
    private EditText mYdTunnelKey;
    private EditText mYdTunnelId;
    private EditText mYdMaxBw;
    private SwitchMaterial mYdForceTcpRelay;
    private EditText mYdMaxFrameBudget;
    private EditText mYdMaxFps;
    private EditText mYdNetGateway;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.settings_tunnel, container, false);

        mTunnelModeGroup = v.findViewById(R.id.tunnel_mode_group);
        mSbSettingsGroup = v.findViewById(R.id.sb_settings_group);
        mYdSettingsGroup = v.findViewById(R.id.yd_settings_group);

        mSbOverrideAddress = v.findViewById(R.id.sb_override_address);
        mSbOverridePort = v.findViewById(R.id.sb_override_port);
        mSbServerPort = v.findViewById(R.id.sb_server_port);
        mSbUuid = v.findViewById(R.id.sb_uuid);
        mSbTlsServerName = v.findViewById(R.id.sb_tls_server_name);
        mSbTlsPublicKey = v.findViewById(R.id.sb_tls_public_key);
        mSbTlsShortId = v.findViewById(R.id.sb_tls_short_id);

        mYdTelemostUrls = v.findViewById(R.id.yd_telemost_urls);
        mYdTunnelKey = v.findViewById(R.id.yd_tunnel_key);
        mYdTunnelId = v.findViewById(R.id.yd_tunnel_id);
        mYdMaxBw = v.findViewById(R.id.yd_max_bw);
        mYdForceTcpRelay = v.findViewById(R.id.yd_force_tcp_relay);
        mYdMaxFrameBudget = v.findViewById(R.id.yd_max_frame_budget);
        mYdMaxFps = v.findViewById(R.id.yd_max_fps);
        mYdNetGateway = v.findViewById(R.id.yd_net_gateway);

        mTunnelModeGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());

        loadFromProfile();
        return v;
    }

    private void updateVisibility() {
        int checkedId = mTunnelModeGroup.getCheckedRadioButtonId();
        mSbSettingsGroup.setVisibility(checkedId == R.id.tunnel_mode_singbox ? View.VISIBLE : View.GONE);
        mYdSettingsGroup.setVisibility(checkedId == R.id.tunnel_mode_ydtun ? View.VISIBLE : View.GONE);
    }

    private Connection getFirstConnection() {
        if (mProfile.mConnections != null && mProfile.mConnections.length > 0)
            return mProfile.mConnections[0];
        return null;
    }

    private void loadFromProfile() {
        Connection conn = getFirstConnection();
        if (conn == null)
            return;

        switch (conn.mTunnelType) {
            case SINGBOX:
                mTunnelModeGroup.check(R.id.tunnel_mode_singbox);
                break;
            case YDTUN:
                mTunnelModeGroup.check(R.id.tunnel_mode_ydtun);
                break;
            default:
                mTunnelModeGroup.check(R.id.tunnel_mode_none);
                break;
        }

        // Sing-box fields
        mSbOverrideAddress.setText(conn.mSingBoxOverrideAddress);
        mSbOverridePort.setText(conn.mSingBoxOverridePort);
        mSbServerPort.setText(conn.mSingBoxServerPort);
        mSbUuid.setText(conn.mSingBoxUUID);
        mSbTlsServerName.setText(conn.mSingBoxTlsServerName);
        mSbTlsPublicKey.setText(conn.mSingBoxTlsPublicKey);
        mSbTlsShortId.setText(conn.mSingBoxTlsShortId);

        // Ydtun fields
        mYdTelemostUrls.setText(conn.mYdtunTelemostUrls);
        mYdTunnelKey.setText(conn.mYdtunTunnelKey);
        mYdTunnelId.setText(conn.mYdtunTunnelId);
        mYdMaxBw.setText(conn.mYdtunMaxBw);
        mYdForceTcpRelay.setChecked(conn.mYdtunForceTcpRelay);
        mYdMaxFrameBudget.setText(conn.mYdtunMaxFrameBudget);
        mYdMaxFps.setText(conn.mYdtunMaxFps);
        mYdNetGateway.setText(conn.mYdtunNetGateway);

        updateVisibility();
    }

    @Override
    protected void savePreferences() {
        if (mProfile == null || mProfile.mConnections == null)
            return;

        int checkedId = mTunnelModeGroup.getCheckedRadioButtonId();
        TunnelType tunnelType;
        if (checkedId == R.id.tunnel_mode_singbox)
            tunnelType = TunnelType.SINGBOX;
        else if (checkedId == R.id.tunnel_mode_ydtun)
            tunnelType = TunnelType.YDTUN;
        else
            tunnelType = TunnelType.NONE;

        String sbOverrideAddress = mSbOverrideAddress.getText().toString().trim();
        String sbOverridePort = mSbOverridePort.getText().toString().trim();
        String sbServerPort = mSbServerPort.getText().toString().trim();
        String sbUuid = mSbUuid.getText().toString().trim();
        String sbTlsServerName = mSbTlsServerName.getText().toString().trim();
        String sbTlsPublicKey = mSbTlsPublicKey.getText().toString().trim();
        String sbTlsShortId = mSbTlsShortId.getText().toString().trim();

        String ydTelemostUrls = mYdTelemostUrls.getText().toString().trim();
        String ydTunnelKey = mYdTunnelKey.getText().toString().trim();
        String ydTunnelId = mYdTunnelId.getText().toString().trim();
        String ydMaxBw = mYdMaxBw.getText().toString().trim();
        boolean ydForceTcpRelay = mYdForceTcpRelay.isChecked();
        String ydMaxFrameBudget = mYdMaxFrameBudget.getText().toString().trim();
        String ydMaxFps = mYdMaxFps.getText().toString().trim();
        String ydNetGateway = mYdNetGateway.getText().toString().trim();

        for (Connection conn : mProfile.mConnections) {
            conn.mTunnelType = tunnelType;

            conn.mSingBoxOverrideAddress = sbOverrideAddress;
            conn.mSingBoxOverridePort = sbOverridePort;
            conn.mSingBoxServerPort = sbServerPort;
            conn.mSingBoxUUID = sbUuid;
            conn.mSingBoxTlsServerName = sbTlsServerName;
            conn.mSingBoxTlsPublicKey = sbTlsPublicKey;
            conn.mSingBoxTlsShortId = sbTlsShortId;

            conn.mYdtunTelemostUrls = ydTelemostUrls;
            conn.mYdtunTunnelKey = ydTunnelKey;
            conn.mYdtunTunnelId = ydTunnelId;
            conn.mYdtunMaxBw = ydMaxBw;
            conn.mYdtunForceTcpRelay = ydForceTcpRelay;
            conn.mYdtunMaxFrameBudget = ydMaxFrameBudget;
            conn.mYdtunMaxFps = ydMaxFps;
            conn.mYdtunNetGateway = ydNetGateway;
        }
    }
}
