package com.example.myapplication.model

import org.json.JSONObject
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class representing a single cell-tower measurement.
 * Serializable so it can be passed via LocalBroadcast extras.
 */
data class CellReport(
    val deviceId      : String,
    val operator      : String,
    val networkType   : String,     // "2G" | "3G" | "4G" | "5G"
    val signalPower   : Int,        // dBm  (RSRP for LTE/NR, RSSI for 2G/3G)
    val sinrSnr       : Double = Double.NaN,
    val frequencyBand : String = "",
    val cellId        : String = "N/A",
    val macAddress    : String = "unknown",
    val timestamp     : Long   = System.currentTimeMillis(),
    // A unique ID for dedup on the server side
    val reportId      : String = UUID.randomUUID().toString()
) : Serializable {

    companion object {
        const val NET_2G = "2G"
        const val NET_3G = "3G"
        const val NET_4G = "4G"
        const val NET_5G = "5G"
        private val FMT  = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    val hasSinrSnr: Boolean get() = !sinrSnr.isNaN()

    fun getFormattedTimestamp(): String = FMT.format(Date(timestamp))

    /**
     * Serialise to JSON, including the logged-in username and mac address.
     * Called by ServerClient.sendReport().
     */
    fun toJsonWithUser(username: String): String = JSONObject().apply {
        put("report_id",      reportId)
        put("device_id",      deviceId)
        put("user_name",      username)
        put("operator",       operator)
        put("network_type",   networkType)
        put("signal_power",   signalPower)
        put("mac_address",    macAddress)
        if (hasSinrSnr) put("sinr_snr", sinrSnr) else put("sinr_snr", JSONObject.NULL)
        put("frequency_band", frequencyBand)
        put("cell_id",        cellId)
        put("timestamp",      timestamp)
    }.toString()
}