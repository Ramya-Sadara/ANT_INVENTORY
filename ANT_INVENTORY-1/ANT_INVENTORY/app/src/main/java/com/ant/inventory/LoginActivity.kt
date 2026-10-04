package com.ant.inventory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LoginActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("auth", Context.MODE_PRIVATE) }

    private lateinit var layoutSetPin: View
    private lateinit var layoutEnterPin: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var etNewPin: EditText
    private lateinit var etConfirmPin: EditText
    private lateinit var btnSetPin: Button
    private lateinit var etPin: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvForgotPin: TextView
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        bindViews()
        if (isPinSet()) showEnterPin() else showSetPin()
    }

    private fun bindViews() {
        layoutSetPin   = findViewById(R.id.layoutSetPin)
        layoutEnterPin = findViewById(R.id.layoutEnterPin)
        tvTitle        = findViewById(R.id.tvTitle)
        tvSubtitle     = findViewById(R.id.tvSubtitle)
        etNewPin       = findViewById(R.id.etNewPin)
        etConfirmPin   = findViewById(R.id.etConfirmPin)
        btnSetPin      = findViewById(R.id.btnSetPin)
        etPin          = findViewById(R.id.etPin)
        btnLogin       = findViewById(R.id.btnLogin)
        tvForgotPin    = findViewById(R.id.tvForgotPin)
        tvError        = findViewById(R.id.tvError)

        btnSetPin.setOnClickListener   { handleSetPin() }
        btnLogin.setOnClickListener    { handleLogin() }
        tvForgotPin.setOnClickListener { handleForgotPin() }
    }

    private fun showSetPin() {
        tvTitle.text    = "ANT INVENTORY"
        tvSubtitle.text = "Create a 4-digit PIN to secure the app"
        layoutSetPin.visibility   = View.VISIBLE
        layoutEnterPin.visibility = View.GONE
        clearError()
    }

    private fun showEnterPin() {
        tvTitle.text    = "ANT INVENTORY"
        tvSubtitle.text = "Enter your PIN to continue"
        layoutSetPin.visibility   = View.GONE
        layoutEnterPin.visibility = View.VISIBLE
        clearError()
    }

    private fun handleSetPin() {
        val newPin     = etNewPin.text.toString().trim()
        val confirmPin = etConfirmPin.text.toString().trim()
        if (newPin.length < 4)            { showError("PIN must be at least 4 digits"); return }
        if (newPin != confirmPin)          { showError("PINs do not match"); return }
        if (!newPin.all { it.isDigit() }) { showError("PIN must contain digits only"); return }
        savePin(newPin)
        Toast.makeText(this, "PIN set successfully!", Toast.LENGTH_SHORT).show()
        goToMain()
    }

    private fun handleLogin() {
        val entered = etPin.text.toString().trim()
        if (entered.isEmpty()) { showError("Enter your PIN"); return }
        if (entered == getPin()) {
            goToMain()
        } else {
            showError("Incorrect PIN. Please try again.")
            etPin.text.clear()
        }
    }

    // ── Forgot PIN — requires biometric or device credential ─────────────────

    private fun handleForgotPin() {
        val biometricManager = BiometricManager.from(this)
        val authenticators   = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Toast.makeText(
                    this,
                    "No screen lock set up on this device. Please set a fingerprint or screen lock in Settings first.",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                askCurrentPinToReset()
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                doResetPin()
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Single attempt failed — prompt stays open, user can retry
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    showError("Authentication failed: $errString")
                }
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify Identity")
            .setSubtitle("Use your fingerprint or screen lock to reset the PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    private fun askCurrentPinToReset() {
        val input = EditText(this).apply {
            hint      = "Enter current PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 20, 40, 20)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Verify Current PIN")
            .setMessage("Enter your current PIN to reset it")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered == getPin()) {
                    doResetPin()
                } else {
                    showError("Incorrect PIN. Reset cancelled.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doResetPin() {
        clearPin()
        showSetPin()
        etNewPin.text.clear()
        etConfirmPin.text.clear()
        Toast.makeText(this, "PIN cleared. Set a new PIN.", Toast.LENGTH_SHORT).show()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ── PIN storage (local only) ──────────────────────────────────────────────

    private fun isPinSet(): Boolean  = prefs.getString("pin", null) != null
    private fun getPin(): String?    = prefs.getString("pin", null)
    private fun savePin(pin: String) = prefs.edit().putString("pin", pin).apply()
    private fun clearPin()           = prefs.edit().remove("pin").apply()

    private fun showError(msg: String) {
        tvError.text       = msg
        tvError.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearError() = showError("")
}
