package com.example.myapplication.ui

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.example.myapplication.network.ServerClient
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MyStatsActivity : AppCompatActivity() {

    private lateinit var tvFrom             : TextView
    private lateinit var tvTo               : TextView
    private lateinit var btnFetch           : com.google.android.material.button.MaterialButton
    private lateinit var progress           : ProgressBar
    private lateinit var layoutCharts       : LinearLayout
    private lateinit var pieNetworkType     : PieChart
    private lateinit var barSignal          : BarChart
    private lateinit var barSinr            : BarChart
    private lateinit var barSignalDevice    : BarChart
    private lateinit var tvOperatorBreakdown: TextView
    private lateinit var tvNoData           : TextView
    private lateinit var client             : ServerClient

    private val fromCal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -7) }
    private val toCal   = Calendar.getInstance()
    private val fmt     = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private var deviceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_stats)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        supportActionBar?.title = "My Statistics"

        deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"

        client              = ServerClient(this)
        tvFrom              = findViewById(R.id.tv_my_from_date)
        tvTo                = findViewById(R.id.tv_my_to_date)
        btnFetch            = findViewById(R.id.btn_my_fetch)
        progress            = findViewById(R.id.my_progress_bar)
        layoutCharts        = findViewById(R.id.layout_charts)
        pieNetworkType      = findViewById(R.id.pie_network_type)
        barSignal           = findViewById(R.id.bar_signal)
        barSinr             = findViewById(R.id.bar_sinr)
        barSignalDevice     = findViewById(R.id.bar_signal_device)
        tvOperatorBreakdown = findViewById(R.id.tv_operator_breakdown)
        tvNoData            = findViewById(R.id.tv_no_data)

        refreshDates()
        setupChartDefaults()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_my_pick_from)
            .setOnClickListener { pickDate(fromCal) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_my_pick_to)
            .setOnClickListener { pickDate(toCal) }
        btnFetch.setOnClickListener { fetchStats() }

        fetchStats()
    }

    // Enable the back arrow in the toolbar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun pickDate(target: Calendar) {
        DatePickerDialog(this, { _, y, m, d ->
            target.set(y, m, d, 0, 0, 0); refreshDates()
        }, target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshDates() {
        tvFrom.text = fmt.format(fromCal.time)
        tvTo.text   = fmt.format(toCal.time)
    }

    private fun fetchStats() {
        if (fromCal.after(toCal)) {
            Toast.makeText(this, "'From' date must be before 'To' date", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility     = View.VISIBLE
        layoutCharts.visibility = View.GONE
        tvNoData.visibility     = View.GONE
        btnFetch.isEnabled      = false

        client.fetchStats(
            fromCal.timeInMillis,
            toCal.timeInMillis + 86_400_000L - 1,
            deviceId,
            object : ServerClient.Callback {
                override fun onSuccess(json: String) = runOnUiThread {
                    progress.visibility = View.GONE
                    btnFetch.isEnabled  = true
                    renderStats(json)
                }
                override fun onError(error: String) = runOnUiThread {
                    progress.visibility = View.GONE
                    btnFetch.isEnabled  = true
                    val friendlyMsg = when {
                        error.contains("connect", ignoreCase = true) ||
                                error.contains("refused", ignoreCase = true) ->
                            "Cannot reach server.\n\nCheck that your Flask server is running and the IP/port in Settings is correct."
                        error.contains("timeout", ignoreCase = true) ->
                            "Connection timed out.\n\nMake sure your phone and server are on the same Wi-Fi network."
                        error.contains("404") ->
                            "Server returned 404. Ensure you're running the latest server version."
                        else ->
                            "Error loading stats:\n$error\n\nCheck server address in Settings."
                    }
                    tvNoData.text       = friendlyMsg
                    tvNoData.visibility = View.VISIBLE
                }
            })
    }

    private fun renderStats(json: String) {
        try {
            val root    = JSONObject(json)
            var hasData = false

            // 1. Pie — network type time share
            root.optJSONArray("network_type_time")?.let { arr ->
                if (arr.length() > 0) {
                    hasData = true
                    val entries = mutableListOf<PieEntry>()
                    val colors  = mutableListOf<Int>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        entries.add(PieEntry(o.getDouble("percentage").toFloat(), o.getString("type")))
                        colors.add(typeColor(o.getString("type")))
                    }
                    val ds = PieDataSet(entries, "").apply {
                        this.colors       = colors
                        valueTextSize     = 13f
                        valueTextColor    = Color.WHITE
                        valueFormatter    = PercentFormatter(pieNetworkType)
                    }
                    pieNetworkType.data = PieData(ds)
                    pieNetworkType.invalidate()
                }
            }

            // 2. Bar — avg signal power per type
            root.optJSONArray("avg_signal_per_type")?.let { arr ->
                if (arr.length() > 0) {
                    hasData = true
                    val labels  = mutableListOf<String>()
                    val entries = mutableListOf<BarEntry>()
                    val colors  = mutableListOf<Int>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        labels.add(o.getString("type"))
                        entries.add(BarEntry(i.toFloat(), -o.getDouble("avg_dbm").toFloat()))
                        colors.add(typeColor(o.getString("type")))
                    }
                    val ds = BarDataSet(entries, "Avg signal power (|dBm|)").apply {
                        this.colors   = colors
                        valueTextSize = 12f
                        valueTextColor = resolveAttrColor(android.R.attr.textColorPrimary)
                    }
                    barSignal.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                    barSignal.xAxis.labelCount     = labels.size
                    barSignal.data                 = BarData(ds).also { it.barWidth = 0.5f }
                    barSignal.invalidate()
                }
            }

            // 3. Bar — avg SINR per type
            root.optJSONArray("avg_sinr_per_type")?.let { arr ->
                if (arr.length() > 0) {
                    val labels  = mutableListOf<String>()
                    val entries = mutableListOf<BarEntry>()
                    val colors  = mutableListOf<Int>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.isNull("avg_sinr")) continue
                        labels.add(o.getString("type"))
                        entries.add(BarEntry(i.toFloat(), o.getDouble("avg_sinr").toFloat()))
                        colors.add(typeColor(o.getString("type")))
                    }
                    if (entries.isNotEmpty()) {
                        hasData = true
                        val ds = BarDataSet(entries, "Avg SINR / SNR (dB)").apply {
                            this.colors    = colors
                            valueTextSize  = 12f
                            valueTextColor = resolveAttrColor(android.R.attr.textColorPrimary)
                        }
                        barSinr.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                        barSinr.xAxis.labelCount     = labels.size
                        barSinr.data                 = BarData(ds).also { it.barWidth = 0.5f }
                        barSinr.invalidate()
                        barSinr.visibility = View.VISIBLE
                    }
                }
            }

            // 4. Bar — avg signal power per device
            root.optJSONArray("avg_signal_per_device")?.let { arr ->
                if (arr.length() > 0) {
                    hasData = true
                    val labels  = mutableListOf<String>()
                    val entries = mutableListOf<BarEntry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        labels.add(o.getString("device_id"))
                        entries.add(BarEntry(i.toFloat(), -o.getDouble("avg_dbm").toFloat()))
                    }
                    val ds = BarDataSet(entries, "Avg signal power per device (|dBm|)").apply {
                        color          = typeColor("4G")
                        valueTextSize  = 11f
                        valueTextColor = resolveAttrColor(android.R.attr.textColorPrimary)
                    }
                    barSignalDevice.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                    barSignalDevice.xAxis.labelCount     = labels.size
                    barSignalDevice.data                 = BarData(ds).also { it.barWidth = 0.5f }
                    barSignalDevice.invalidate()
                }
            }

            // 5. Operator text breakdown
            root.optJSONArray("operator_time")?.let { arr ->
                if (arr.length() > 0) {
                    hasData = true
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        sb.append("${o.getString("operator")}  —  ${"%.1f".format(o.getDouble("percentage"))}%\n")
                    }
                    tvOperatorBreakdown.text = sb.toString().trimEnd()
                }
            }

            if (hasData) {
                layoutCharts.visibility = View.VISIBLE
            } else {
                tvNoData.text       = "No data found for the selected date range.\nTry extending the range or check that the app has sent measurements."
                tvNoData.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            tvNoData.text       = "Failed to parse server response: ${e.message}\n\nMake sure the server is the latest version."
            tvNoData.visibility = View.VISIBLE
        }
    }

    private fun setupChartDefaults() {
        val textColor = resolveAttrColor(android.R.attr.textColorSecondary)

        pieNetworkType.apply {
            description.isEnabled    = false
            isDrawHoleEnabled        = true
            holeRadius               = 46f
            transparentCircleRadius  = 50f
            setHoleColor(Color.TRANSPARENT)
            legend.textColor         = textColor
            legend.textSize          = 12f
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(13f)
            setUsePercentValues(true)
            setCenterText("Network\nTime Share")
            setCenterTextSize(13f)
            setCenterTextColor(textColor)
            animateY(900)
        }

        setupBarChart(barSignal, "Signal strength ← stronger", textColor)
        barSignal.axisLeft.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float) = "-${value.toInt()} dBm"
        }

        setupBarChart(barSinr, "SINR / SNR (dB)", textColor)
        barSinr.visibility = View.GONE

        setupBarChart(barSignalDevice, "Avg signal power per device", textColor)
        barSignalDevice.axisLeft.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float) = "-${value.toInt()} dBm"
        }
    }

    private fun setupBarChart(chart: BarChart, label: String, colorVal: Int) {
        chart.apply {
            description.isEnabled  = false
            setFitBars(true)
            setPinchZoom(false)
            setDrawGridBackground(false)
            legend.textColor       = colorVal
            legend.textSize        = 12f
            axisRight.isEnabled    = false
            axisLeft.textColor     = colorVal
            axisLeft.gridColor     = Color.argb(30, 128, 128, 128)
            xAxis.apply {
                position       = XAxis.XAxisPosition.BOTTOM
                textColor      = colorVal
                setDrawGridLines(false)
                granularity    = 1f
            }
            animateY(700)
        }
    }

    private fun typeColor(type: String): Int = when (type) {
        "2G"  -> Color.parseColor("#3B8BD4")  // blue
        "3G"  -> Color.parseColor("#E07B2E")  // orange
        "4G"  -> Color.parseColor("#1A6FC4")  // primary blue
        "5G"  -> Color.parseColor("#15803D")  // green
        else  -> Color.parseColor("#888780")  // gray
    }

    private fun resolveAttrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c  = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return c
    }
}