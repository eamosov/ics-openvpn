/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager

class Settings_Trusted_Wifi : Fragment() {
    private lateinit var mProfile: VpnProfile
    private lateinit var mAdapter: TrustedWifiAdapter
    private lateinit var mEmptyView: TextView

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Show dialog regardless of permission result - user can type SSID manually
            showAddDialog()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileUuid = requireArguments().getString(requireActivity().packageName + ".profileUUID")
        mProfile = ProfileManager.get(activity, profileUuid)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.trusted_wifi_networks, container, false)

        mEmptyView = v.findViewById(R.id.empty_view)
        val recyclerView = v.findViewById<RecyclerView>(R.id.trusted_wifi_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        mAdapter = TrustedWifiAdapter(mProfile.mTrustedWifiList.toMutableList().sorted().toMutableList())
        recyclerView.adapter = mAdapter
        updateEmptyView()

        val fab = v.findViewById<FloatingActionButton>(R.id.add_trusted_wifi)
        fab.setOnClickListener { onAddClicked() }

        return v
    }

    private fun updateEmptyView() {
        if (mAdapter.itemCount == 0) {
            mEmptyView.visibility = View.VISIBLE
        } else {
            mEmptyView.visibility = View.GONE
        }
    }

    private fun hasWifiPermissions(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            return hasLocation && hasNearby
        }
        return hasLocation
    }

    private fun onAddClicked() {
        if (hasWifiPermissions()) {
            showAddDialog()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun getCurrentSsid(): String? {
        try {
            val wifiMgr = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiMgr?.connectionInfo
            if (wifiInfo != null && wifiInfo.ssid != null) {
                val ssid = wifiInfo.ssid.replace("\"", "")
                if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                    return ssid
                }
            }
        } catch (_: SecurityException) {
        }
        return null
    }

    private fun showAddDialog() {
        val editText = EditText(requireContext())
        editText.hint = getString(R.string.trusted_wifi_ssid_hint)
        editText.setSingleLine()

        val currentSsid = getCurrentSsid()
        if (currentSsid != null) {
            editText.setText(currentSsid)
            editText.selectAll()
        }

        val padding = (16 * resources.displayMetrics.density).toInt()
        editText.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_trusted_wifi)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ssid = editText.text.toString().trim()
                if (ssid.isNotEmpty()) {
                    mProfile.mTrustedWifiList.add(ssid)
                    mAdapter.updateList(mProfile.mTrustedWifiList.toMutableList().sorted().toMutableList())
                    updateEmptyView()
                    ProfileManager.saveProfile(requireContext(), mProfile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeWifi(ssid: String) {
        mProfile.mTrustedWifiList.remove(ssid)
        mAdapter.updateList(mProfile.mTrustedWifiList.toMutableList().sorted().toMutableList())
        updateEmptyView()
        ProfileManager.saveProfile(requireContext(), mProfile)
    }

    inner class TrustedWifiAdapter(private var items: MutableList<String>) :
        RecyclerView.Adapter<TrustedWifiAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ssidText: TextView = view.findViewById(R.id.wifi_ssid)
            val removeButton: ImageButton = view.findViewById(R.id.wifi_remove)
        }

        fun updateList(newItems: MutableList<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.trusted_wifi_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ssid = items[position]
            holder.ssidText.text = ssid
            holder.removeButton.setOnClickListener { removeWifi(ssid) }
        }

        override fun getItemCount() = items.size
    }
}
