package com.example.myapplication.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

/**
 * HomeActivity — central navigation hub.
 * Back-press from here asks the user whether to log out (since there's
 * nowhere else to go — WelcomeActivity is behind a login wall).
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val prefs    = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "User") ?: "User"

        supportActionBar?.title = "Home"

        findViewById<TextView>(R.id.tv_home_greeting).text = "Hello, $username 👋"

        // Nav cards
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_nav_monitor)
            .setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_nav_stats)
            .setOnClickListener { startActivity(Intent(this, MyStatsActivity::class.java)) }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_nav_settings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // Logout button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_logout)
            .setOnClickListener { confirmLogout(prefs) }

        // Back-press on HomeActivity → offer logout instead of crashing back to Welcome
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmLogout(prefs) }
        })
    }

    private fun confirmLogout(prefs: android.content.SharedPreferences) {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log out") { _, _ ->
                prefs.edit().putBoolean("logged_in", false).apply()
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}