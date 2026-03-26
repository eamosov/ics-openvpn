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

import com.google.android.material.switchmaterial.SwitchMaterial;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.Connection;
import de.blinkt.openvpn.core.Connection.TunnelType;

public class Settings_Ydtun extends Settings_Fragment {

    private SwitchMaterial mYdtunEnable;
    private LinearLayout mSettingsGroup;
    private EditText mTelemostUrls;
    private EditText mTunnelKey;
    private EditText mTunnelId;
    private EditText mMaxBw;
    private SwitchMaterial mForceTcpRelay;
    private EditText mMaxFrameBudget;
    private EditText mMaxFps;
    private EditText mNetGateway;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.settings_ydtun, container, false);

        mYdtunEnable = v.findViewById(R.id.yd_enable);
        mSettingsGroup = v.findViewById(R.id.yd_settings_group);
        mTelemostUrls = v.findViewById(R.id.yd_telemost_urls);
        mTunnelKey = v.findViewById(R.id.yd_tunnel_key);
        mTunnelId = v.findViewById(R.id.yd_tunnel_id);
        mMaxBw = v.findViewById(R.id.yd_max_bw);
        mForceTcpRelay = v.findViewById(R.id.yd_force_tcp_relay);
        mMaxFrameBudget = v.findViewById(R.id.yd_max_frame_budget);
        mMaxFps = v.findViewById(R.id.yd_max_fps);
        mNetGateway = v.findViewById(R.id.yd_net_gateway);

        mYdtunEnable.setOnCheckedChangeListener((buttonView, isChecked) ->
                mSettingsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE)
        );

        loadFromProfile();
        return v;
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

        boolean enabled = conn.isYdtunEnabled();
        mYdtunEnable.setChecked(enabled);
        mSettingsGroup.setVisibility(enabled ? View.VISIBLE : View.GONE);

        mTelemostUrls.setText(conn.mYdtunTelemostUrls);
        mTunnelKey.setText(conn.mYdtunTunnelKey);
        mTunnelId.setText(conn.mYdtunTunnelId);
        mMaxBw.setText(conn.mYdtunMaxBw);
        mForceTcpRelay.setChecked(conn.mYdtunForceTcpRelay);
        mMaxFrameBudget.setText(conn.mYdtunMaxFrameBudget);
        mMaxFps.setText(conn.mYdtunMaxFps);
        mNetGateway.setText(conn.mYdtunNetGateway);
    }

    @Override
    protected void savePreferences() {
        if (mProfile == null || mProfile.mConnections == null)
            return;

        boolean enabled = mYdtunEnable.isChecked();
        String telemostUrls = mTelemostUrls.getText().toString().trim();
        String tunnelKey = mTunnelKey.getText().toString().trim();
        String tunnelId = mTunnelId.getText().toString().trim();
        String maxBw = mMaxBw.getText().toString().trim();
        boolean forceTcpRelay = mForceTcpRelay.isChecked();
        String maxFrameBudget = mMaxFrameBudget.getText().toString().trim();
        String maxFps = mMaxFps.getText().toString().trim();
        String netGateway = mNetGateway.getText().toString().trim();

        for (Connection conn : mProfile.mConnections) {
            conn.mTunnelType = enabled ? TunnelType.YDTUN : TunnelType.NONE;
            conn.mYdtunTelemostUrls = telemostUrls;
            conn.mYdtunTunnelKey = tunnelKey;
            conn.mYdtunTunnelId = tunnelId;
            conn.mYdtunMaxBw = maxBw;
            conn.mYdtunForceTcpRelay = forceTcpRelay;
            conn.mYdtunMaxFrameBudget = maxFrameBudget;
            conn.mYdtunMaxFps = maxFps;
            conn.mYdtunNetGateway = netGateway;
        }
    }
}
