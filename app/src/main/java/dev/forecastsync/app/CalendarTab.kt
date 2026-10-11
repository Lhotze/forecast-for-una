package dev.forecastsync.app

import android.Manifest
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The "Calendar" page: which calendars go to the Agenda glance, how often, and a button to send now. */
class CalendarTab(private val act: AppCompatActivity, private val scope: CoroutineScope) {

    companion object {
        const val PERMISSION_REQUEST = 3
    }

    private val prefs = Prefs(act)
    private val list = act.findViewById<LinearLayout>(R.id.calList)
    private val permButton = act.findViewById<MaterialButton>(R.id.btnCalPermission)
    private val status = act.findViewById<TextView>(R.id.calStatus)
    private var sendJob: Job? = null

    init {
        val folder = act.findViewById<TextInputEditText>(R.id.calFolderInput)
        folder.setText(prefs.calFolder)
        folder.doAfterTextChanged { prefs.calFolder = it?.toString().orEmpty().uppercase() }

        val interval = act.findViewById<MaterialButtonToggleGroup>(R.id.calIntervalGroup)
        interval.check(when (prefs.calIntervalMin) { 10 -> R.id.calBtn10; 20 -> R.id.calBtn20; else -> R.id.calBtn30 })
        interval.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                prefs.calIntervalMin = when (id) { R.id.calBtn10 -> 10; R.id.calBtn20 -> 20; else -> 30 }
                if (prefs.calAutoSync) CalendarScheduler.start(act, prefs.calIntervalMin)
            }
        }

        val auto = act.findViewById<MaterialSwitch>(R.id.calAutoSwitch)
        auto.isChecked = prefs.calAutoSync
        auto.setOnCheckedChangeListener { _, on ->
            prefs.calAutoSync = on
            if (on) CalendarScheduler.start(act, prefs.calIntervalMin) else CalendarScheduler.cancel(act)
        }

        permButton.setOnClickListener {
            act.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), PERMISSION_REQUEST)
        }
        act.findViewById<MaterialButton>(R.id.btnCalSend).setOnClickListener {
            sendJob?.cancel()
            status.text = "Sending ..."
            sendJob = scope.launch { SyncEngine.runCalendar(act.applicationContext); showStatus() }
        }
        refresh()
    }

    /** Called when the page is shown again or the permission answer arrives. */
    fun refresh() {
        val granted = CalendarClient.hasPermission(act)
        permButton.visibility = if (granted) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (granted) {
            val cals = CalendarClient.listCalendars(act)
            if (cals.isEmpty()) {
                list.addView(TextView(act).apply { text = "No calendars found on this phone." })
            }
            val chosen = prefs.calIds.toMutableSet()
            for (c in cals) {
                list.addView(MaterialCheckBox(act).apply {
                    text = if (c.account.isNotBlank() && c.account != c.name) "${c.name}\n${c.account}" else c.name
                    isChecked = c.id in chosen
                    setOnCheckedChangeListener { _, on ->
                        if (on) chosen += c.id else chosen -= c.id
                        prefs.calIds = chosen.toSet()
                    }
                })
            }
        }
        showStatus()
    }

    private fun showStatus() {
        val t = if (prefs.calStatusTime > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.calStatusTime)) else "-"
        status.text = "Last run ($t):\n${prefs.calStatus}"
    }
}
