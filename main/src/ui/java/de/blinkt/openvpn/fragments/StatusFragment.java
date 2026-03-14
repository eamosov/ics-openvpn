/*
 * Copyright (c) 2012-2024 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.blinkt.openvpn.R;

public class StatusFragment extends Fragment {

    private static final int CHECK_INTERVAL_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_PING_HOSTS = 10;

    private static final String PREF_PING_HOSTS = "status_ping_hosts";

    private Handler mHandler;
    private ExecutorService mExecutor;
    private boolean mRunning;

    private TextView mLastUpdate;
    private LinearLayout mPingContainer;

    private static final Pattern IP_PATTERN = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$");

    private static class ServiceCard {
        final String name;
        final String url;
        View indicator;
        TextView status;
        TextView details;

        ServiceCard(String name, String url) {
            this.name = name;
            this.url = url;
        }

        void bind(View cardView) {
            ((TextView) cardView.findViewById(R.id.card_title)).setText(name);
            indicator = cardView.findViewById(R.id.card_indicator);
            status = cardView.findViewById(R.id.card_status);
            details = cardView.findViewById(R.id.card_details);
        }
    }

    private final ServiceCard[] mServices = {
        new ServiceCard("ifconfig.me", "https://ifconfig.me/all.json"),
        new ServiceCard("yandex.ru/internet", "https://yandex.ru/internet/"),
        new ServiceCard("api.ipify.org", "https://api.ipify.org/?format=json"),
        new ServiceCard("tunnelblick.net", "https://tunnelblick.net/ipinfo"),
    };

    private final int[] mCardIds = {
        R.id.card_ifconfig,
        R.id.card_yandex,
        R.id.card_ipify,
        R.id.card_tunnelblick,
    };

    private static class PingCard {
        final String host;
        View indicator;
        TextView status;
        TextView details;

        PingCard(String host) {
            this.host = host;
        }

        void bind(View cardView) {
            ((TextView) cardView.findViewById(R.id.card_title)).setText(host);
            indicator = cardView.findViewById(R.id.card_indicator);
            status = cardView.findViewById(R.id.card_status);
            details = cardView.findViewById(R.id.card_details);
        }
    }

    private final List<PingCard> mPingHosts = new ArrayList<>();

    private final Runnable mCheckRunnable = this::performChecks;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_status, container, false);
        mLastUpdate = view.findViewById(R.id.status_last_update);
        mPingContainer = view.findViewById(R.id.ping_cards_container);

        for (int i = 0; i < mServices.length; i++) {
            View card = view.findViewById(mCardIds[i]);
            mServices[i].bind(card);
        }

        buildPingCards(inflater);

        ImageButton editBtn = view.findViewById(R.id.ping_edit_button);
        editBtn.setOnClickListener(v -> showEditDialog());

        mHandler = new Handler(Looper.getMainLooper());

        return view;
    }

    private void buildPingCards(LayoutInflater inflater) {
        mPingContainer.removeAllViews();
        mPingHosts.clear();

        List<String> hosts = loadPingHosts();
        for (String host : hosts) {
            View card = inflater.inflate(R.layout.status_card_item, mPingContainer, false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            mPingContainer.addView(card);

            PingCard ping = new PingCard(host);
            ping.bind(card);
            mPingHosts.add(ping);
        }
    }

    private void rebuildPingCards() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        buildPingCards(inflater);
        restartExecutor();
    }

    private void restartExecutor() {
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdownNow();
        }
        mExecutor = Executors.newFixedThreadPool(Math.max(1, mServices.length + mPingHosts.size()));
    }

    @Override
    public void onResume() {
        super.onResume();
        restartExecutor();
        mRunning = true;
        mHandler.post(mCheckRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacks(mCheckRunnable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
    }

    private void performChecks() {
        if (!mRunning || mExecutor == null || mExecutor.isShutdown())
            return;

        for (ServiceCard service : mServices) {
            mExecutor.submit(() -> {
                CheckResult result = checkService(service);
                if (mRunning) {
                    mHandler.post(() -> updateCard(service, result));
                }
            });
        }

        for (PingCard ping : mPingHosts) {
            mExecutor.submit(() -> {
                CheckResult result = doPing(ping.host);
                if (mRunning) {
                    mHandler.post(() -> updatePingCard(ping, result));
                }
            });
        }

        if (mLastUpdate != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            mLastUpdate.setText("Last update: " + sdf.format(new Date()));
        }

        mHandler.postDelayed(mCheckRunnable, CHECK_INTERVAL_MS);
    }

    private void updateCard(ServiceCard service, CheckResult result) {
        if (!isAdded() || service.status == null)
            return;

        if (result.reachable) {
            service.indicator.setBackgroundResource(R.drawable.status_indicator_green);
            service.status.setText("Reachable (" + result.latencyMs + " ms)");
            if (result.ip != null) {
                service.details.setText("IP: " + result.ip);
                service.details.setVisibility(View.VISIBLE);
            } else {
                service.details.setVisibility(View.GONE);
            }
        } else {
            service.indicator.setBackgroundResource(R.drawable.status_indicator_red);
            service.status.setText(result.error != null ? result.error : "Unreachable");
            service.details.setVisibility(View.GONE);
        }
    }

    private CheckResult checkService(ServiceCard service) {
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(service.url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readBody(conn);
                long latency = System.currentTimeMillis() - start;
                conn.disconnect();
                String ip = parseIp(service.url, body);
                return new CheckResult(true, ip, null, latency);
            } else {
                conn.disconnect();
                return new CheckResult(false, null, "HTTP " + code, 0);
            }
        } catch (java.net.SocketTimeoutException e) {
            return new CheckResult(false, null, "Timeout", 0);
        } catch (Exception e) {
            return new CheckResult(false, null, "Error", 0);
        }
    }

    private String readBody(HttpURLConnection conn) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private CheckResult doPing(String host) {
        try {
            long start = System.currentTimeMillis();
            InetAddress addr = InetAddress.getByName(host);
            boolean reachable = addr.isReachable(CONNECT_TIMEOUT_MS);
            long latency = System.currentTimeMillis() - start;
            if (reachable) {
                return new CheckResult(true, addr.getHostAddress(), null, latency);
            } else {
                // isReachable may fail without root; fall back to TCP connect on port 443
                return doPingTcp(host, 443);
            }
        } catch (Exception e) {
            // Fall back to TCP connect
            return doPingTcp(host, 443);
        }
    }

    private CheckResult doPingTcp(String host, int port) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            long start = System.currentTimeMillis();
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
            long latency = System.currentTimeMillis() - start;
            socket.close();
            return new CheckResult(true, addr.getHostAddress(), null, latency);
        } catch (java.net.SocketTimeoutException e) {
            return new CheckResult(false, null, "Timeout", 0);
        } catch (Exception e) {
            return new CheckResult(false, null, "Unreachable", 0);
        }
    }

    private void updatePingCard(PingCard ping, CheckResult result) {
        if (!isAdded() || ping.status == null)
            return;

        if (result.reachable) {
            ping.indicator.setBackgroundResource(R.drawable.status_indicator_green);
            ping.status.setText(result.latencyMs + " ms");
            if (result.ip != null) {
                ping.details.setText(result.ip);
                ping.details.setVisibility(View.VISIBLE);
            } else {
                ping.details.setVisibility(View.GONE);
            }
        } else {
            ping.indicator.setBackgroundResource(R.drawable.status_indicator_red);
            ping.status.setText(result.error != null ? result.error : "Unreachable");
            ping.details.setVisibility(View.GONE);
        }
    }

    // --- Ping hosts persistence ---

    private List<String> loadPingHosts() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String raw = prefs.getString(PREF_PING_HOSTS, "");
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    private void savePingHosts(List<String> hosts) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit().putString(PREF_PING_HOSTS, String.join("\n", hosts)).apply();
    }

    // --- Edit dialog ---

    private void showEditDialog() {
        List<String> hosts = loadPingHosts();

        LinearLayout dialogLayout = new LinearLayout(requireContext());
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        dialogLayout.setPadding(pad, pad, pad, 0);

        LinearLayout listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.addView(listContainer);

        for (String host : hosts) {
            addHostRow(listContainer, hosts, host);
        }

        // Add new host row
        LinearLayout addRow = new LinearLayout(requireContext());
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addRowLp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        addRow.setLayoutParams(addRowLp);

        EditText input = new EditText(requireContext());
        input.setHint("hostname");
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputLp);
        addRow.addView(input);

        ImageButton addBtn = new ImageButton(requireContext());
        addBtn.setImageResource(android.R.drawable.ic_input_add);
        addBtn.setBackground(null);
        addBtn.setOnClickListener(v -> {
            String newHost = input.getText().toString().trim();
            if (newHost.isEmpty()) {
                return;
            }
            if (!HOSTNAME_PATTERN.matcher(newHost).matches()) {
                Toast.makeText(requireContext(), "Invalid hostname", Toast.LENGTH_SHORT).show();
                return;
            }
            if (hosts.contains(newHost)) {
                Toast.makeText(requireContext(), "Already in list", Toast.LENGTH_SHORT).show();
                return;
            }
            if (hosts.size() >= MAX_PING_HOSTS) {
                Toast.makeText(requireContext(), "Maximum " + MAX_PING_HOSTS + " servers", Toast.LENGTH_SHORT).show();
                return;
            }
            hosts.add(newHost);
            addHostRow(listContainer, hosts, newHost);
            input.setText("");
        });
        addRow.addView(addBtn);

        dialogLayout.addView(addRow);

        new AlertDialog.Builder(requireContext())
            .setTitle("Ping Servers")
            .setView(dialogLayout)
            .setPositiveButton("OK", (dialog, which) -> {
                savePingHosts(hosts);
                rebuildPingCards();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addHostRow(LinearLayout container, List<String> hosts, String host) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (4 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(requireContext());
        label.setText(host);
        label.setTextSize(16);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        row.addView(label);

        ImageButton removeBtn = new ImageButton(requireContext());
        removeBtn.setImageResource(android.R.drawable.ic_delete);
        removeBtn.setBackground(null);
        removeBtn.setOnClickListener(v -> {
            hosts.remove(host);
            container.removeView(row);
        });
        row.addView(removeBtn);

        container.addView(row);
    }

    private String parseIp(String url, String body) {
        // ifconfig.me/all.json returns {"ip_addr": "x.x.x.x", ...}
        if (url.contains("ifconfig.me")) {
            Pattern p = Pattern.compile("\"ip_addr\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        }

        // yandex.ru/internet embeds JSON with "v4":"x.x.x.x"
        if (url.contains("yandex.ru")) {
            Pattern p = Pattern.compile("\"v4\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        }

        // api.ipify.org returns {"ip": "x.x.x.x"}
        if (url.contains("ipify.org")) {
            Pattern p = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        }

        // tunnelblick.net/ipinfo returns "client_ip,port,server_ip"
        if (url.contains("tunnelblick.net")) {
            String[] parts = body.trim().split(",");
            if (parts.length >= 1) return parts[0];
        }

        // Fallback: find any public IP in body
        Matcher ipM = IP_PATTERN.matcher(body);
        while (ipM.find()) {
            String ip = ipM.group(1);
            if (!ip.startsWith("0.") && !ip.startsWith("127.") && !ip.startsWith("10.") && !ip.startsWith("192.168.")) {
                return ip;
            }
        }
        return null;
    }

    private static class CheckResult {
        final boolean reachable;
        final String ip;
        final String error;
        final long latencyMs;

        CheckResult(boolean reachable, String ip, String error, long latencyMs) {
            this.reachable = reachable;
            this.ip = ip;
            this.error = error;
            this.latencyMs = latencyMs;
        }
    }
}
