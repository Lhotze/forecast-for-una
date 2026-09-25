package dev.forecastsync.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var keyLayout: TextInputLayout
    private lateinit var keyInput: TextInputEditText
    private lateinit var folderInput: TextInputEditText
    private lateinit var deviceInput: MaterialAutoCompleteTextView
    private lateinit var providerGroup: MaterialButtonToggleGroup
    private lateinit var intervalGroup: MaterialButtonToggleGroup
    private lateinit var autoSwitch: MaterialSwitch
    private var devices: List<BluetoothDevice> = emptyList()
    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Two tabs: settings and a guide to what the watch shows.
        val tabs = findViewById<TabLayout>(R.id.tabs)
        val settingsPage = findViewById<View>(R.id.settingsPage)
        val guidePage = findViewById<View>(R.id.guidePage)
        GuideContent.build(this, findViewById<LinearLayout>(R.id.guideContent))
        tabs.addTab(tabs.newTab().setText("Settings"))
        tabs.addTab(tabs.newTab().setText("Guide"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                settingsPage.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                guidePage.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        status = findViewById(R.id.status)
        keyLayout = findViewById(R.id.keyLayout)
        keyInput = findViewById(R.id.keyInput)
        folderInput = findViewById(R.id.folderInput)
        deviceInput = findViewById(R.id.deviceInput)
        providerGroup = findViewById(R.id.providerGroup)
        intervalGroup = findViewById(R.id.intervalGroup)
        autoSwitch = findViewById(R.id.autoSwitch)

        keyInput.setText(prefs.apiKey)
        folderInput.setText(prefs.appFolder)
        keyInput.doAfterTextChanged { prefs.apiKey = it?.toString().orEmpty() }
        folderInput.doAfterTextChanged { prefs.appFolder = it?.toString().orEmpty().uppercase() }

        providerGroup.check(if (prefs.provider == "owm") R.id.btnOwm else R.id.btnOpenMeteo)
        keyLayout.visibility = if (prefs.provider == "owm") View.VISIBLE else View.GONE
        providerGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                prefs.provider = if (id == R.id.btnOwm) "owm" else "openmeteo"
                keyLayout.visibility = if (id == R.id.btnOwm) View.VISIBLE else View.GONE
            }
        }

        intervalGroup.check(when (prefs.intervalMin) { 10 -> R.id.btn10; 20 -> R.id.btn20; else -> R.id.btn30 })
        intervalGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                prefs.intervalMin = when (id) { R.id.btn10 -> 10; R.id.btn20 -> 20; else -> 30 }
                if (prefs.autoSync) Scheduler.start(this, prefs.intervalMin)
            }
        }

        autoSwitch.isChecked = prefs.autoSync
        autoSwitch.setOnCheckedChangeListener { _, on ->
            prefs.autoSync = on
            if (on) Scheduler.start(this, prefs.intervalMin) else Scheduler.cancel(this)
        }

        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener {
            syncJob?.cancel()
            status.text = "Sending ..."
            syncJob = scope.launch { SyncEngine.run(applicationContext); showStatus() }
        }
        findViewById<MaterialButton>(R.id.btnPermissions).setOnClickListener { requestPermissions() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }

        refreshDevices()
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        showStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showStatus() {
        val t = if (prefs.statusTime > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.statusTime)) else "-"
        status.text = "Last run ($t):\n${prefs.status}"
    }

    private fun btGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun refreshDevices() {
        if (!btGranted()) {
            deviceInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("Bluetooth permission missing")))
            deviceInput.setText("Bluetooth permission missing", false)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        devices = adapter?.bondedDevices?.sortedBy { it.name ?: "" } ?: emptyList()
        val labels = devices.map { "${it.name ?: "?"}  (${it.address})" }
        deviceInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1,
            if (labels.isEmpty()) listOf("no paired devices") else labels))

        val saved = devices.indexOfFirst { it.address == prefs.deviceAddress }
        val guess = devices.indexOfFirst { (it.name ?: "").contains("UNA", ignoreCase = true) }
        val idx = if (saved >= 0) saved else guess
        if (idx >= 0) {
            deviceInput.setText(labels[idx], false)
            prefs.deviceAddress = devices[idx].address
        }
        deviceInput.setOnItemClickListener { _, _, pos, _ ->
            devices.getOrNull(pos)?.let { prefs.deviceAddress = it.address }
        }
    }

    private fun requestPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else requestBackgroundLocation()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDevices()
        if (requestCode == 1 && LocationHelper.hasPermission(this)) requestBackgroundLocation()
    }

    /** Needed so the scheduled run can read the position while the app is closed. Optional: the last position is reused otherwise. */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
