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

public class Settings_SingBox extends Settings_Fragment {

    private SwitchMaterial mSbEnable;
    private LinearLayout mSettingsGroup;
    private EditText mOverrideAddress;
    private EditText mOverridePort;
    private EditText mServerPort;
    private EditText mUuid;
    private EditText mTlsServerName;
    private EditText mTlsPublicKey;
    private EditText mTlsShortId;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.settings_singbox, container, false);

        mSbEnable = v.findViewById(R.id.sb_enable);
        mSettingsGroup = v.findViewById(R.id.sb_settings_group);
        mOverrideAddress = v.findViewById(R.id.sb_override_address);
        mOverridePort = v.findViewById(R.id.sb_override_port);
        mServerPort = v.findViewById(R.id.sb_server_port);
        mUuid = v.findViewById(R.id.sb_uuid);
        mTlsServerName = v.findViewById(R.id.sb_tls_server_name);
        mTlsPublicKey = v.findViewById(R.id.sb_tls_public_key);
        mTlsShortId = v.findViewById(R.id.sb_tls_short_id);

        mSbEnable.setOnCheckedChangeListener((buttonView, isChecked) ->
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

        mSbEnable.setChecked(conn.mSingBoxEnable);
        mSettingsGroup.setVisibility(conn.mSingBoxEnable ? View.VISIBLE : View.GONE);

        mOverrideAddress.setText(conn.mSingBoxOverrideAddress);
        mOverridePort.setText(conn.mSingBoxOverridePort);
        mServerPort.setText(conn.mSingBoxServerPort);
        mUuid.setText(conn.mSingBoxUUID);
        mTlsServerName.setText(conn.mSingBoxTlsServerName);
        mTlsPublicKey.setText(conn.mSingBoxTlsPublicKey);
        mTlsShortId.setText(conn.mSingBoxTlsShortId);
    }

    @Override
    protected void savePreferences() {
        if (mProfile == null || mProfile.mConnections == null)
            return;

        boolean enabled = mSbEnable.isChecked();
        String overrideAddress = mOverrideAddress.getText().toString().trim();
        String overridePort = mOverridePort.getText().toString().trim();
        String serverPort = mServerPort.getText().toString().trim();
        String uuid = mUuid.getText().toString().trim();
        String tlsServerName = mTlsServerName.getText().toString().trim();
        String tlsPublicKey = mTlsPublicKey.getText().toString().trim();
        String tlsShortId = mTlsShortId.getText().toString().trim();

        // Apply to all connections
        for (Connection conn : mProfile.mConnections) {
            conn.mSingBoxEnable = enabled;
            conn.mSingBoxOverrideAddress = overrideAddress;
            conn.mSingBoxOverridePort = overridePort;
            conn.mSingBoxServerPort = serverPort;
            conn.mSingBoxUUID = uuid;
            conn.mSingBoxTlsServerName = tlsServerName;
            conn.mSingBoxTlsPublicKey = tlsPublicKey;
            conn.mSingBoxTlsShortId = tlsShortId;
        }
    }
}
