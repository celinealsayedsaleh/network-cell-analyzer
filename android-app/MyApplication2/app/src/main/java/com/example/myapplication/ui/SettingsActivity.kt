package com.example.myapplication.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.example.myapplication.network.ServerClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var etHost: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var client: ServerClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // TOOLBAR FIX (THIS WAS MISSING BEFORE)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        client = ServerClient(this)

        etHost = findViewById(R.id.et_server_host)
        etPort = findViewById(R.id.et_server_port)

        etHost.setText(client.host)
        etPort.setText(client.port.toString())

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            save()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun save() {
        val host = etHost.text?.toString()?.trim() ?: ""
        val portStr = etPort.text?.toString()?.trim() ?: ""

        if (host.isEmpty()) {
            etHost.error = "Enter IP address"
            return
        }

        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: run {
                etPort.error = "Enter valid port (1–65535)"
                return
            }

        client.saveAddress(host, port)

        Toast.makeText(this, "Saved: $host:$port", Toast.LENGTH_SHORT).show()
        finish()
    }
}