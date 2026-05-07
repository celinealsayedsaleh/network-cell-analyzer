package com.example.myapplication.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.myapplication.model.CellReport
import com.example.myapplication.network.ServerClient
import com.example.myapplication.ui.MainActivity

class CellMonitorService : Service() {

    companion object {
        const val ACTION_CELL_UPDATE = "com.example.myapplication.CELL_UPDATE"
        const val EXTRA_CELL_REPORT  = "cell_report"
        private const val POLL_MS    = 10_000L
        private const val CHANNEL_ID = "cell_monitor"
        private const val NOTIF_ID   = 1001
        private const val TAG        = "CellMonitorService"
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var collector: CellInfoCollector
    private lateinit var client: ServerClient
    private var running = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            collector.collect()?.let { report ->
                val intent = Intent(ACTION_CELL_UPDATE).putExtra(EXTRA_CELL_REPORT, report)
                LocalBroadcastManager.getInstance(this@CellMonitorService).sendBroadcast(intent)
                client.sendReport(report)
                Log.d(TAG, "Polled: ${report.networkType} ${report.signalPower} dBm")
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        collector    = CellInfoCollector(this)
        client       = ServerClient(this)
        workerThread = HandlerThread("CellWorker").also { it.start() }
        handler      = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!running) { running = true; handler.post(pollTask) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollTask)
        workerThread.quitSafely()
        client.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Cell Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Cell Analyzer")
            .setContentText("Monitoring cell info every 10 s")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
