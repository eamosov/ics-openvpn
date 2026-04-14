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
import android.widget.Spinner;

import com.google.android.material.switchmaterial.SwitchMaterial;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.Connection;
import de.blinkt.openvpn.core.Connection.TunnelType;

public class Settings_Ydtun extends Settings_Fragment {

    private SwitchMaterial mYdtunEnable;
    private LinearLayout mSettingsGroup;
    private EditText mTelemostUrls;
    private EditText mTunnelKey;
    private SwitchMaterial mForceTcpRelay;
    private EditText mNetGateway;
    private Spinner mLogLevel;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.settings_ydtun, container, false);

        mYdtunEnable = v.findViewById(R.id.yd_enable);
        mSettingsGroup = v.findViewById(R.id.yd_settings_group);
        mTelemostUrls = v.findViewById(R.id.yd_telemost_urls);
        mTunnelKey = v.findViewById(R.id.yd_tunnel_key);
        mForceTcpRelay = v.findViewById(R.id.yd_force_tcp_relay);
        mNetGateway = v.findViewById(R.id.yd_net_gateway);
        mLogLevel = v.findViewById(R.id.yd_log_level);

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
        mForceTcpRelay.setChecked(conn.mYdtunForceTcpRelay);
        mNetGateway.setText(conn.mYdtunNetGateway);
        mLogLevel.setSelection(conn.mYdtunLogLevel);
    }

    @Override
    protected void savePreferences() {
        if (mProfile == null || mProfile.mConnections == null)
            return;

        boolean enabled = mYdtunEnable.isChecked();
        String telemostUrls = mTelemostUrls.getText().toString().trim();
        String tunnelKey = mTunnelKey.getText().toString().trim();
        boolean forceTcpRelay = mForceTcpRelay.isChecked();
        String netGateway = mNetGateway.getText().toString().trim();
        int logLevel = mLogLevel.getSelectedItemPosition();

        for (Connection conn : mProfile.mConnections) {
            conn.mTunnelType = enabled ? TunnelType.YDTUN : TunnelType.NONE;
            conn.mYdtunTelemostUrls = telemostUrls;
            conn.mYdtunTunnelKey = tunnelKey;
            conn.mYdtunForceTcpRelay = forceTcpRelay;
            conn.mYdtunNetGateway = netGateway;
            conn.mYdtunLogLevel = logLevel;
        }
    }
}
