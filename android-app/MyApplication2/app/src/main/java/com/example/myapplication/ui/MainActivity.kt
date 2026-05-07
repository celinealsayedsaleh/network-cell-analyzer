package com.example.myapplication.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.myapplication.R
import com.example.myapplication.model.CellReport
import com.example.myapplication.service.CellMonitorService
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var tvOperator    : TextView
    private lateinit var tvNetworkType : TextView
    private lateinit var tvSignalPower : TextView
    private lateinit var tvSinr        : TextView
    private lateinit var tvBand        : TextView
    private lateinit var tvCellId      : TextView
    private lateinit var tvTimestamp   : TextView
    private lateinit var tvStatus      : TextView
    private lateinit var card2g        : MaterialCardView
    private lateinit var card3g        : MaterialCardView
    private lateinit var card4g        : MaterialCardView
    private lateinit var card5g        : MaterialCardView
    private lateinit var fabStats      : ExtendedFloatingActionButton
    private var serviceStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startMonitorService()
        } else {
            // Show which permissions are missing
            val denied = grants.filterValues { !it }.keys.joinToString(", ") {
                when (it) {
                    Manifest.permission.READ_PHONE_STATE   -> "Phone State"
                    Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
                    else -> it.substringAfterLast('.')
                }
            }
            Snackbar.make(
                fabStats,
                "Permissions required: $denied. Go to Settings → App Permissions to enable.",
                Snackbar.LENGTH_LONG
            ).setAction("Settings") {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                )
            }.show()
        }
    }

    private val cellReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val report = intent.getSerializableExtra(CellMonitorService.EXTRA_CELL_REPORT) as? CellReport
            report?.let { updateUi(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Enable back button — MainActivity is a child of HomeActivity
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvOperator    = findViewById(R.id.tv_operator)
        tvNetworkType = findViewById(R.id.tv_network_type)
        tvSignalPower = findViewById(R.id.tv_signal_power)
        tvSinr        = findViewById(R.id.tv_sinr)
        tvBand        = findViewById(R.id.tv_band)
        tvCellId      = findViewById(R.id.tv_cell_id)
        tvTimestamp   = findViewById(R.id.tv_timestamp)
        tvStatus      = findViewById(R.id.tv_status)
        card2g        = findViewById(R.id.card_2g)
        card3g        = findViewById(R.id.card_3g)
        card4g        = findViewById(R.id.card_4g)
        card5g        = findViewById(R.id.card_5g)
        fabStats      = findViewById(R.id.fab_stats)

        val btnStart = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_start)
        val btnStop  = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stop)

        btnStart.setOnClickListener {
            if (!serviceStarted) {
                if (hasPermissions()) {
                    startMonitorService()
                    tvStatus.text = "Monitoring Active…"
                    Snackbar.make(it, "Service Started", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(it, "Requesting permissions…", Snackbar.LENGTH_SHORT).show()
                    requestPermissions()
                }
            } else {
                Snackbar.make(it, "Already monitoring", Snackbar.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            if (serviceStarted) {
                stopService(Intent(this, CellMonitorService::class.java))
                serviceStarted = false
                tvStatus.text  = "Service Stopped"
                tvOperator.text    = "—"
                tvNetworkType.text = "OFFLINE"
                tvSignalPower.text = "— dBm"
                tvSinr.text        = "—"
                tvBand.text        = "—"
                tvCellId.text      = "—"
                resetNetworkCards()
                Snackbar.make(it, "Monitoring Stopped", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(it, "Service is not running", Snackbar.LENGTH_SHORT).show()
            }
        }

        fabStats.setOnClickListener {
            startActivity(Intent(this, MyStatsActivity::class.java))
        }

        if (hasPermissions()) startMonitorService() else requestPermissions()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cellReceiver, IntentFilter(CellMonitorService.ACTION_CELL_UPDATE)
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cellReceiver)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateUi(r: CellReport) {
        tvOperator.text    = r.operator
        tvNetworkType.text = r.networkType
        tvSignalPower.text = "${r.signalPower} dBm"
        tvSinr.text        = if (r.hasSinrSnr) "%.1f dB".format(r.sinrSnr) else "N/A"
        tvBand.text        = r.frequencyBand.ifEmpty { "N/A" }
        tvCellId.text      = r.cellId.ifEmpty { "N/A" }
        tvTimestamp.text   = r.getFormattedTimestamp()
        tvStatus.text      = "Sending to server…"

        val inactive = getColor(R.color.card_inactive)
        card2g.setCardBackgroundColor(if (r.networkType == CellReport.NET_2G) getColor(R.color.accent_2g) else inactive)
        card3g.setCardBackgroundColor(if (r.networkType == CellReport.NET_3G) getColor(R.color.accent_3g) else inactive)
        card4g.setCardBackgroundColor(if (r.networkType == CellReport.NET_4G) getColor(R.color.accent_4g) else inactive)
        card5g.setCardBackgroundColor(if (r.networkType == CellReport.NET_5G) getColor(R.color.accent_5g) else inactive)
    }

    private fun resetNetworkCards() {
        val inactive = getColor(R.color.card_inactive)
        card2g.setCardBackgroundColor(inactive)
        card3g.setCardBackgroundColor(inactive)
        card4g.setCardBackgroundColor(inactive)
        card5g.setCardBackgroundColor(inactive)
    }

    private fun startMonitorService() {
        if (!serviceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, CellMonitorService::class.java))
            serviceStarted = true
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
    }

    private fun hasPermissions() = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}