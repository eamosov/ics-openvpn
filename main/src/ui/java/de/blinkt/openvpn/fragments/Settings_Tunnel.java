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
import android.widget.Spinner;

import de.blinkt.openvpn.core.ProfileManager;

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
    private EditText mYdTelemostCcUrl;
    private EditText mYdDisplayName;
    private EditText mYdTunnelKey;
    private EditText mYdTunnelId;
    private EditText mYdMaxBw;
    private SwitchMaterial mYdForceTcpRelay;
    private EditText mYdMaxFrameBudget;
    private EditText mYdMaxFps;
    private Spinner mYdLogLevel;

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

        mYdTelemostCcUrl = v.findViewById(R.id.yd_telemost_cc_url);
        mYdDisplayName = v.findViewById(R.id.yd_display_name);
        mYdTunnelKey = v.findViewById(R.id.yd_tunnel_key);
        mYdTunnelId = v.findViewById(R.id.yd_tunnel_id);
        mYdMaxBw = v.findViewById(R.id.yd_max_bw);
        mYdForceTcpRelay = v.findViewById(R.id.yd_force_tcp_relay);
        mYdMaxFrameBudget = v.findViewById(R.id.yd_max_frame_budget);
        mYdMaxFps = v.findViewById(R.id.yd_max_fps);
        mYdLogLevel = v.findViewById(R.id.yd_log_level);
        setTelemostFieldsReadOnly();

        mTunnelModeGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());

        loadFromProfile();
        return v;
    }

    private void setTelemostFieldsReadOnly() {
        makeReadOnly(mYdTelemostCcUrl);
        makeReadOnly(mYdDisplayName);
        makeReadOnly(mYdTunnelKey);
        makeReadOnly(mYdTunnelId);
        makeReadOnly(mYdMaxBw);
        makeReadOnly(mYdMaxFrameBudget);
        makeReadOnly(mYdMaxFps);
    }

    private void makeReadOnly(EditText field) {
        field.setKeyListener(null);
        field.setCursorVisible(false);
        field.setFocusable(false);
        field.setFocusableInTouchMode(false);
        field.setTextIsSelectable(true);
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
        String telemostCcUrl = telemostValue(conn, "telemost_cc_url", conn.mYdtunTelemostCcUrl);
        mYdTelemostCcUrl.setText(telemostCcUrl);
        setReadOnlyFieldVisible(mYdTelemostCcUrl, telemostCcUrl);
        String displayName = telemostValue(conn, "telemost_display_name", conn.mYdtunDisplayName);
        mYdDisplayName.setText(displayName);
        setReadOnlyFieldVisible(mYdDisplayName, displayName);
        String tunnelKey = telemostValue(conn, "telemost_tunnel_key", conn.mYdtunTunnelKey);
        mYdTunnelKey.setText(tunnelKey);
        setReadOnlyFieldVisible(mYdTunnelKey, tunnelKey);
        String tunnelId = telemostValue(conn, "telemost_tunnel_id", conn.mYdtunTunnelId);
        mYdTunnelId.setText(tunnelId);
        setReadOnlyFieldVisible(mYdTunnelId, tunnelId);
        String maxBw = telemostValue(conn, "telemost_max_bw", conn.mYdtunMaxBw);
        mYdMaxBw.setText(maxBw);
        setReadOnlyFieldVisible(mYdMaxBw, maxBw);
        mYdForceTcpRelay.setChecked(conn.mYdtunForceTcpRelay || "true".equalsIgnoreCase(telemostValue(conn, "telemost_force_tcp_relay", "")) || "1".equals(telemostValue(conn, "telemost_force_tcp_relay", "")));
        String maxFrameBudget = telemostValue(conn, "telemost_max_frame_budget", conn.mYdtunMaxFrameBudget);
        mYdMaxFrameBudget.setText(maxFrameBudget);
        setReadOnlyFieldVisible(mYdMaxFrameBudget, maxFrameBudget);
        String maxFps = telemostValue(conn, "telemost_max_fps", conn.mYdtunMaxFps);
        mYdMaxFps.setText(maxFps);
        setReadOnlyFieldVisible(mYdMaxFps, maxFps);
        mYdLogLevel.setSelection(Math.max(0, Math.min(conn.mYdtunLogLevel, mYdLogLevel.getCount() - 1)));

        updateVisibility();
    }

    private void setReadOnlyFieldVisible(EditText field, String value) {
        View container = (View) field.getParent();
        container.setVisibility(value == null || value.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String telemostValue(Connection conn, String key, String value) {
        if (value != null && !value.trim().isEmpty())
            return value;
        return customSetenvSafeValue(conn.mCustomConfiguration, key);
    }

    private String customSetenvSafeValue(String customConfig, String key) {
        if (customConfig == null)
            return "";
        for (String line : customConfig.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("setenv-safe "))
                continue;
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length < 2 || !key.equals(parts[1]))
                continue;
            return parts.length > 2 ? parts[2].trim() : "";
        }
        return "";
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

        String ydTelemostCcUrl = mYdTelemostCcUrl.getText().toString().trim();
        String ydDisplayName = mYdDisplayName.getText().toString().trim();
        String ydTunnelKey = mYdTunnelKey.getText().toString().trim();
        String ydTunnelId = mYdTunnelId.getText().toString().trim();
        String ydMaxBw = mYdMaxBw.getText().toString().trim();
        boolean ydForceTcpRelay = mYdForceTcpRelay.isChecked();
        String ydMaxFrameBudget = mYdMaxFrameBudget.getText().toString().trim();
        String ydMaxFps = mYdMaxFps.getText().toString().trim();
        int ydLogLevel = mYdLogLevel.getSelectedItemPosition();

        for (Connection conn : mProfile.mConnections) {
            conn.mTunnelType = tunnelType;

            conn.mSingBoxOverrideAddress = sbOverrideAddress;
            conn.mSingBoxOverridePort = sbOverridePort;
            conn.mSingBoxServerPort = sbServerPort;
            conn.mSingBoxUUID = sbUuid;
            conn.mSingBoxTlsServerName = sbTlsServerName;
            conn.mSingBoxTlsPublicKey = sbTlsPublicKey;
            conn.mSingBoxTlsShortId = sbTlsShortId;

            conn.mYdtunTelemostCcUrl = ydTelemostCcUrl;
            conn.mYdtunDisplayName = ydDisplayName;
            conn.mYdtunTunnelKey = ydTunnelKey;
            conn.mYdtunTunnelId = ydTunnelId;
            conn.mYdtunMaxBw = ydMaxBw;
            conn.mYdtunForceTcpRelay = ydForceTcpRelay;
            conn.mYdtunMaxFrameBudget = ydMaxFrameBudget;
            conn.mYdtunMaxFps = ydMaxFps;
            conn.mYdtunLogLevel = ydLogLevel;
        }
        ProfileManager.saveProfile(requireContext(), mProfile);
    }
}
