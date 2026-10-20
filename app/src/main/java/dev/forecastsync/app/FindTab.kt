package dev.forecastsync.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.DateFormat
import java.util.Date

/** The "Find" page: settings of the Find my Phone alarm and buttons to try it. */
class FindTab(private val act: AppCompatActivity) {

    companion object {
        const val PERMISSION_REQUEST = 4
    }

    private val prefs = Prefs(act)
    private val status = act.findViewById<TextView>(R.id.findStatus)
    private val intervalInput = act.findViewById<MaterialAutoCompleteTextView>(R.id.findIntervalInput)
    private val dndSwitch = act.findViewById<MaterialSwitch>(R.id.findDndSwitch)
    private val permButton = act.findViewById<MaterialButton>(R.id.btnFindPermissions)
    private val dndButton = act.findViewById<MaterialButton>(R.id.btnFindDnd)
    private val exactButton = act.findViewById<MaterialButton>(R.id.btnFindExact)

    // Kept in a field: SharedPreferences holds listeners weakly. Shows the service's progress live.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "findStatus" || key == "findStatusTime") act.runOnUiThread { showStatus() }
    }

    init {
        val folder = act.findViewById<TextInputEditText>(R.id.findFolderInput)
        folder.setText(prefs.findFolder)
        folder.doAfterTextChanged { prefs.findFolder = it?.toString().orEmpty().uppercase() }

        intervalInput.setAdapter(ArrayAdapter(act, android.R.layout.simple_list_item_1, FindRule.INTERVALS.map { it.second }))
        intervalInput.setText(FindRule.intervalLabel(prefs.findIntervalSec), false)
        intervalInput.setOnItemClickListener { _, _, pos, _ ->
            val seconds = FindRule.INTERVALS[pos].first
            if (seconds > 0 && !permissionsOk()) {
                intervalInput.setText(FindRule.intervalLabel(prefs.findIntervalSec), false)
                prefs.saveFindStatus("Grant the Bluetooth and notification permission first")
                return@setOnItemClickListener
            }
            prefs.findIntervalSec = seconds
            applyService()
        }

        dndSwitch.isChecked = prefs.findDnd
        dndSwitch.setOnCheckedChangeListener { _, on -> prefs.findDnd = on }

        permButton.setOnClickListener {
            val need = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) need += Manifest.permission.BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need += Manifest.permission.POST_NOTIFICATIONS
            val missing = need.filter { ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) act.requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST) else refresh()
        }
        dndButton.setOnClickListener {
            act.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        exactButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                act.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${act.packageName}")))
            }
        }

        act.findViewById<MaterialButton>(R.id.btnFindCheck).setOnClickListener { run(FindService.ACTION_CHECK_NOW) }
        act.findViewById<MaterialButton>(R.id.btnFindTest).setOnClickListener { run(FindService.ACTION_TEST) }
        act.findViewById<MaterialButton>(R.id.btnFindStop).setOnClickListener { run(FindService.ACTION_STOP_ALARM) }
        refresh()
    }

    private fun permissionsOk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(act, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun applyService() {
        try {
            FindService.apply(act)
        } catch (e: Exception) {
            prefs.saveFindStatus("Could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun run(action: String) {
        if (!permissionsOk()) {
            prefs.saveFindStatus("Grant the Bluetooth and notification permission first")
            return
        }
        try {
            FindService.send(act, action)
        } catch (e: Exception) {
            prefs.saveFindStatus("Could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun onResume() {
        prefs.registerListener(listener)
        refresh()
    }

    fun onPause() {
        prefs.unregisterListener(listener)
    }

    /** Called when the page is shown again or a permission answer arrives. */
    fun refresh() {
        val nm = act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dndGranted = nm.isNotificationPolicyAccessGranted
        dndButton.text = if (dndGranted) "Do Not Disturb access: allowed" else "Allow Do Not Disturb access"
        exactButton.visibility = if (FindService.canScheduleExact(act)) View.GONE else View.VISIBLE
        permButton.visibility = if (permissionsOk() && notificationsOk()) View.GONE else View.VISIBLE
        showStatus()
    }

    private fun notificationsOk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun showStatus() {
        val t = if (prefs.findStatusTime > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.findStatusTime)) else "-"
        status.text = "Last check ($t):\n${prefs.findStatus}"
    }
}
