package com.example.myapplication.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

/**
 * WelcomeActivity — shown only once (on first launch), or every time the user logs out.
 *
 * Flow:
 *   First launch   → WelcomeActivity → HomeActivity
 *   Subsequent     → HomeActivity directly (skip welcome)
 *   Logout         → clear "logged_in" pref → WelcomeActivity
 *
 * Auth is LOCAL-ONLY (SharedPreferences).  Replace with a real backend call if needed.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Views
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var cardLogin: com.google.android.material.card.MaterialCardView
    private lateinit var cardRegister: com.google.android.material.card.MaterialCardView
    private lateinit var etLoginUser: com.google.android.material.textfield.TextInputEditText
    private lateinit var etLoginPass: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnLogin: com.google.android.material.button.MaterialButton
    private lateinit var tvSwitchToRegister: TextView
    private lateinit var etRegUser: com.google.android.material.textfield.TextInputEditText
    private lateinit var etRegPass: com.google.android.material.textfield.TextInputEditText
    private lateinit var etRegPass2: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnRegister: com.google.android.material.button.MaterialButton
    private lateinit var tvSwitchToLogin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        // Skip welcome if already logged in
        if (prefs.getBoolean("logged_in", false)) {
            launch(); return
        }

        setContentView(R.layout.activity_welcome)
        bindViews()
        animateEntrance()
        setupListeners()
    }

    private fun bindViews() {
        tvTitle          = findViewById(R.id.tv_welcome_title)
        tvSubtitle       = findViewById(R.id.tv_welcome_subtitle)
        cardLogin        = findViewById(R.id.card_login)
        cardRegister     = findViewById(R.id.card_register)
        etLoginUser      = findViewById(R.id.et_login_username)
        etLoginPass      = findViewById(R.id.et_login_password)
        btnLogin         = findViewById(R.id.btn_login)
        tvSwitchToRegister = findViewById(R.id.tv_go_register)
        etRegUser        = findViewById(R.id.et_reg_username)
        etRegPass        = findViewById(R.id.et_reg_password)
        etRegPass2       = findViewById(R.id.et_reg_password2)
        btnRegister      = findViewById(R.id.btn_register)
        tvSwitchToLogin  = findViewById(R.id.tv_go_login)
    }

    private fun animateEntrance() {
        val fadeSlide = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        tvTitle.startAnimation(fadeSlide)
        tvSubtitle.startAnimation(fadeSlide)
    }

    private fun setupListeners() {
        // --- Login ---
        btnLogin.setOnClickListener {
            val user = etLoginUser.text?.toString()?.trim() ?: ""
            val pass = etLoginPass.text?.toString() ?: ""
            if (user.isEmpty()) { etLoginUser.error = "Enter username"; return@setOnClickListener }
            if (pass.isEmpty()) { etLoginPass.error = "Enter password"; return@setOnClickListener }

            val savedPass = prefs.getString("pass_$user", null)
            if (savedPass == null) {
                Toast.makeText(this, "No account found. Please register first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (savedPass != pass) {
                etLoginPass.error = "Incorrect password"
                return@setOnClickListener
            }
            loginSuccess(user)
        }

        tvSwitchToRegister.setOnClickListener {
            cardLogin.visibility = View.GONE
            cardRegister.visibility = View.VISIBLE
        }

        // --- Register ---
        btnRegister.setOnClickListener {
            val user  = etRegUser.text?.toString()?.trim() ?: ""
            val pass  = etRegPass.text?.toString() ?: ""
            val pass2 = etRegPass2.text?.toString() ?: ""
            if (user.length < 3) { etRegUser.error = "Min 3 characters"; return@setOnClickListener }
            if (pass.length < 6) { etRegPass.error = "Min 6 characters"; return@setOnClickListener }
            if (pass != pass2)   { etRegPass2.error = "Passwords don't match"; return@setOnClickListener }
            if (prefs.contains("pass_$user")) {
                etRegUser.error = "Username already taken"; return@setOnClickListener
            }
            prefs.edit().putString("pass_$user", pass).apply()
            loginSuccess(user)
        }

        tvSwitchToLogin.setOnClickListener {
            cardRegister.visibility = View.GONE
            cardLogin.visibility = View.VISIBLE
        }
    }

    private fun loginSuccess(username: String) {
        prefs.edit()
            .putBoolean("logged_in", true)
            .putString("username", username)
            .apply()
        launch()
    }

    private fun launch() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
