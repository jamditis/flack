package tech.amditis.slackcarbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/**
 * Phone-side setup and status, used while parked. Shows permissions, the on/off
 * switch and content-free counts. Plain platform views keep the app small.
 */
class StatusActivity : Activity() {

    private lateinit var access: TextView
    private lateinit var posting: TextView
    private lateinit var enabled: Switch
    private lateinit var diagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        access = TextView(this)
        column.addView(access)
        column.addView(button(R.string.open_access_settings) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        posting = TextView(this)
        column.addView(posting)
        column.addView(button(R.string.request_post_permission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        // refresh() attaches the change listener after it sets the saved state.
        enabled = Switch(this).apply { setText(R.string.enable_switch) }
        column.addView(enabled)

        column.addView(TextView(this).apply {
            setText(R.string.car_setup_note)
            setPadding(0, pad, 0, pad)
        })

        diagnostics = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(diagnostics)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        access.setText(if (hasAccess) R.string.access_on else R.string.access_off)
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        posting.setText(if (canPost) R.string.posting_on else R.string.posting_off)
        enabled.setOnCheckedChangeListener(null)
        enabled.isChecked = BridgeState.isEnabled(this)
        enabled.setOnCheckedChangeListener { _, on -> BridgeState.setEnabled(this, on); refresh() }
        val listener = getString(if (BridgeState.listenerConnected) R.string.listener_connected else R.string.listener_not_connected)
        diagnostics.text = getString(R.string.status_report, listener, BridgeState.diagnostics())
    }

    private fun button(label: Int, onClick: (View) -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener(onClick)
    }
}
