package com.example.myapplication.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.*
import android.telephony.CellInfo
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.myapplication.model.CellReport
import java.net.NetworkInterface

class CellInfoCollector(private val context: Context) {

    private val tag = "CellInfoCollector"
    private val tm  = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val deviceId = resolveDeviceId()

    fun collect(): CellReport? {
        if (!hasPermissions()) { Log.w(tag, "Missing permissions"); return null }
        val cells = try { tm.allCellInfo } catch (e: SecurityException) { return null }
        if (cells.isNullOrEmpty()) return null
        return (cells.firstOrNull { it.isRegistered } ?: cells[0]).let { build(it) }
    }

    private fun build(cell: android.telephony.CellInfo): CellReport? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> fromNr(cell)
        cell is CellInfoLte   -> fromLte(cell)
        cell is CellInfoWcdma -> fromWcdma(cell)
        cell is CellInfoGsm   -> fromGsm(cell)
        else                  -> null
    }

    // ── 5G NR ────────────────────────────────────────────────────────────────
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun fromNr(cell: CellInfoNr): CellReport {
        val id  = cell.cellIdentity as CellIdentityNr
        val sig = cell.cellSignalStrength as CellSignalStrengthNr

        // ssRsrp is the primary signal measure for NR (in dBm, already scaled)
        val rsrp = sig.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: sig.dbm

        // ssSinr: Android returns it in units of 0.5 dB (range −23..40 maps to −46..80)
        // We divide by 2 to get real dB. UNAVAILABLE sentinel is Int.MIN_VALUE or Int.MAX_VALUE.
        val sinr = sig.ssSinr
            .takeIf { it != Int.MIN_VALUE && it != Int.MAX_VALUE && it != CellInfo.UNAVAILABLE }
            ?.let { it / 2.0 }
            ?: Double.NaN

        val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bandFromNrarfcn(id.nrarfcn)
        } else {
            "5G NR"
        }

        return CellReport(
            deviceId      = deviceId,
            operator      = operatorName(),
            networkType   = CellReport.NET_5G,
            signalPower   = rsrp,
            sinrSnr       = sinr,
            frequencyBand = band,
            cellId        = if (id.nci != CellInfo.UNAVAILABLE.toLong()) id.nci.toString() else "N/A",
            macAddress    = resolveMacAddress()
        )
    }

    // ── 4G LTE ───────────────────────────────────────────────────────────────
    private fun fromLte(cell: CellInfoLte): CellReport {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength
        val eci = id.ci

        // rssnr from Android is in units of 1/10 dB on API < Q, and raw dB on Q+.
        // The correct approach: on Q+ use rssnr directly (it's already dB*10 → divide by 10).
        // UNAVAILABLE is returned as Int.MAX_VALUE or CellInfo.UNAVAILABLE (= Int.MIN_VALUE on some builds).
        val sinr: Double = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val raw = sig.rssnr
            when {
                raw == Int.MAX_VALUE         -> Double.NaN
                raw == CellInfo.UNAVAILABLE  -> Double.NaN
                raw == 0                     -> Double.NaN  // modem not ready / unsupported
                raw < -230 || raw > 300      -> Double.NaN  // out of 3GPP spec range
                else -> raw / 10.0
            }
        } else {
            Double.NaN
        }

        return CellReport(
            deviceId      = deviceId,
            operator      = operatorName(),
            networkType   = CellReport.NET_4G,
            signalPower   = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: sig.dbm,
            sinrSnr       = sinr,
            frequencyBand = bandFromEarfcn(id.earfcn),
            cellId        = if (eci != CellInfo.UNAVAILABLE) "${eci ushr 8}-$eci" else "N/A",
            macAddress    = resolveMacAddress()
        )
    }

    // ── 3G WCDMA ─────────────────────────────────────────────────────────────
    private fun fromWcdma(cell: CellInfoWcdma): CellReport {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength
        return CellReport(
            deviceId      = deviceId,
            operator      = operatorName(),
            networkType   = CellReport.NET_3G,
            signalPower   = sig.dbm,
            frequencyBand = bandFromUarfcn(id.uarfcn),
            cellId        = if (id.lac != CellInfo.UNAVAILABLE && id.cid != CellInfo.UNAVAILABLE)
                "${id.lac}-${id.cid}" else "N/A",
            macAddress    = resolveMacAddress()
        )
    }

    // ── 2G GSM ───────────────────────────────────────────────────────────────
    private fun fromGsm(cell: CellInfoGsm): CellReport {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength
        return CellReport(
            deviceId      = deviceId,
            operator      = operatorName(),
            networkType   = CellReport.NET_2G,
            signalPower   = sig.dbm,
            frequencyBand = bandFromArfcn(id.arfcn),
            cellId        = if (id.lac != CellInfo.UNAVAILABLE && id.cid != CellInfo.UNAVAILABLE)
                "${id.lac}-${id.cid}" else "N/A",
            macAddress    = resolveMacAddress()
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun operatorName() = tm.networkOperatorName.ifEmpty { "Unknown" }

    /**
     * Resolves the device MAC address.
     * On Android 6+, WifiInfo.macAddress returns "02:00:00:00:00:00" as a privacy measure.
     * We fall back to reading from /sys/class/net/wlan0/address or NetworkInterface.
     */
    private fun resolveMacAddress(): String {
        // Try NetworkInterface first (works on many devices even without Wi-Fi connected)
        try {
            val iface = NetworkInterface.getByName("wlan0")
            if (iface != null) {
                val mac = iface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    return mac.joinToString(":") { "%02X".format(it) }
                }
            }
        } catch (_: Exception) {}

        // Fallback: read from sysfs (requires no special permission on most devices)
        try {
            val file = java.io.File("/sys/class/net/wlan0/address")
            if (file.exists()) {
                val addr = file.readText().trim()
                if (addr.isNotEmpty() && addr != "00:00:00:00:00:00") return addr
            }
        } catch (_: Exception) {}

        return "unknown"
    }

    // ── Band lookups ──────────────────────────────────────────────────────────
    private fun bandFromEarfcn(e: Int): String {
        if (e < 0 || e == CellInfo.UNAVAILABLE) return ""
        return when {
            e <= 599    -> "Band 1 (2100 MHz)"
            e <= 1199   -> "Band 2 (1900 MHz)"
            e <= 1949   -> "Band 3 (1800 MHz)"
            e <= 2649   -> "Band 5 (850 MHz)"
            e <= 3449   -> "Band 7 (2600 MHz)"
            e <= 3799   -> "Band 8 (900 MHz)"
            e <= 6449   -> "Band 20 (800 MHz)"
            else        -> "EARFCN $e"
        }
    }

    private fun bandFromUarfcn(u: Int): String {
        if (u < 0 || u == CellInfo.UNAVAILABLE) return ""
        return when (u) {
            in 10562..10838 -> "Band I (2100 MHz)"
            in 4357..4458   -> "Band V (850 MHz)"
            in 2712..2863   -> "Band VIII (900 MHz)"
            else            -> "UARFCN $u"
        }
    }

    private fun bandFromArfcn(a: Int): String {
        if (a < 0 || a == CellInfo.UNAVAILABLE) return ""
        return when {
            a <= 124 -> "GSM 900"
            a <= 251 -> "GSM 850"
            a <= 885 -> "DCS 1800"
            else     -> "ARFCN $a"
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun bandFromNrarfcn(nrarfcn: Int): String {
        if (nrarfcn <= 0) return "5G NR"
        return when {
            nrarfcn in 1..599999     -> "5G FR1 Sub-6 GHz"
            nrarfcn in 600000..2016666 -> "5G FR2 mmWave"
            else                     -> "5G NR (NRARFCN $nrarfcn)"
        }
    }

    private fun resolveDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"

    private fun hasPermissions() =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)  == PackageManager.PERMISSION_GRANTED
}