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
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("auth", Context.MODE_PRIVATE) }

    private lateinit var tabSignIn: TextView
    private lateinit var tabRegister: TextView
    private lateinit var tabContainer: View
    private lateinit var layoutSignIn: View
    private lateinit var layoutRegister: View
    private lateinit var etSignInUser: EditText
    private lateinit var etSignInPass: EditText
    private lateinit var btnSignIn: Button
    private lateinit var tvForgotPassword: TextView
    private lateinit var etRegUser: EditText
    private lateinit var etRegPass: EditText
    private lateinit var etRegConfirmPass: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvError: TextView

    private var pendingResetUser: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        bindViews()
        if (hasAnyAccount()) showSignIn() else showRegister()
    }

    private fun bindViews() {
        tabSignIn        = findViewById(R.id.tabSignIn)
        tabRegister      = findViewById(R.id.tabRegister)
        tabContainer     = findViewById(R.id.tabContainer)
        layoutSignIn     = findViewById(R.id.layoutSignIn)
        layoutRegister   = findViewById(R.id.layoutRegister)
        etSignInUser     = findViewById(R.id.etSignInUser)
        etSignInPass     = findViewById(R.id.etSignInPass)
        btnSignIn        = findViewById(R.id.btnSignIn)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        etRegUser        = findViewById(R.id.etRegUser)
        etRegPass        = findViewById(R.id.etRegPass)
        etRegConfirmPass = findViewById(R.id.etRegConfirmPass)
        btnRegister      = findViewById(R.id.btnRegister)
        tvError          = findViewById(R.id.tvError)

        btnSignIn.setOnClickListener        { handleSignIn() }
        btnRegister.setOnClickListener      { handleRegister() }
        tvForgotPassword.setOnClickListener { handleForgotPassword() }
        tabSignIn.setOnClickListener        { showSignIn() }
        tabRegister.setOnClickListener      { showRegister() }
    }

    private fun showSignIn() {
        tabSignIn.setBackgroundResource(R.drawable.tab_selected)
        tabRegister.setBackgroundResource(R.drawable.tab_unselected)
        tabSignIn.setTextColor(0xFFFFFFFF.toInt())
        tabRegister.setTextColor(0xFF1565C0.toInt())
        layoutSignIn.visibility   = View.VISIBLE
        layoutRegister.visibility = View.GONE
        clearError()
    }

    private fun showRegister() {
        tabRegister.setBackgroundResource(R.drawable.tab_selected)
        tabSignIn.setBackgroundResource(R.drawable.tab_unselected)
        tabRegister.setTextColor(0xFFFFFFFF.toInt())
        tabSignIn.setTextColor(0xFF1565C0.toInt())
        layoutSignIn.visibility   = View.GONE
        layoutRegister.visibility = View.VISIBLE
        clearError()
    }

    private fun handleSignIn() {
        val username = etSignInUser.text.toString().trim()
        val password = etSignInPass.text.toString()
        if (username.isEmpty()) { showError("Enter your username"); return }
        if (password.isEmpty()) { showError("Enter your password"); return }
        val stored = getPassword(username)
        when {
            stored == null     -> showError("No account found for \"$username\". Please register first.")
            stored != password -> {
                showError("Incorrect password. Please try again.")
                etSignInPass.text.clear()
            }
            else -> goToMain(username)
        }
    }

    private fun handleRegister() {
        val username    = etRegUser.text.toString().trim()
        val password    = etRegPass.text.toString()
        val confirmPass = etRegConfirmPass.text.toString()
        if (username.isEmpty())      { showError("Enter a username"); return }
        if (username.length < 3)     { showError("Username must be at least 3 characters"); return }
        if (password.length < 4)     { showError("Password must be at least 4 characters"); return }
        if (password != confirmPass) { showError("Passwords do not match"); return }
        if (accountExists(username)) { showError("Username \"$username\" already taken. Choose another."); return }
        saveAccount(username, password)
        Toast.makeText(this, "Account created! Please sign in.", Toast.LENGTH_SHORT).show()
        etRegUser.text.clear()
        etRegPass.text.clear()
        etRegConfirmPass.text.clear()
        showSignIn()
        etSignInUser.setText(username)
        etSignInPass.requestFocus()
    }

    private fun handleForgotPassword() {
        val username = etSignInUser.text.toString().trim()
        if (username.isEmpty())       { showError("Enter your username first, then tap Forgot Password"); return }
        if (!accountExists(username)) { showError("No account found for \"$username\""); return }
        pendingResetUser = username
        val biometricManager = BiometricManager.from(this)
        val authenticators   = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS             -> showBiometricPrompt()
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Toast.makeText(
                this,
                "No screen lock set up. Please set a fingerprint or screen lock in Settings first.",
                Toast.LENGTH_LONG
            ).show()
            else -> showNewPasswordDialog()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                showNewPasswordDialog()
            }
            override fun onAuthenticationFailed() { super.onAuthenticationFailed() }
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
            .setSubtitle("Use fingerprint or screen lock to reset password for \"$pendingResetUser\"")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    private fun showNewPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val etNewPass = EditText(this).apply {
            hint      = "New password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirmNewPass = EditText(this).apply {
            hint      = "Confirm new password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etNewPass)
        layout.addView(etConfirmNewPass)
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Password")
            .setMessage("Set a new password for \"$pendingResetUser\"")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newPass     = etNewPass.text.toString()
                val confirmPass = etConfirmNewPass.text.toString()
                when {
                    newPass.length < 4    -> showError("Password must be at least 4 characters")
                    newPass != confirmPass -> showError("Passwords do not match")
                    else -> {
                        saveAccount(pendingResetUser, newPass)
                        Toast.makeText(this, "Password reset successfully!", Toast.LENGTH_SHORT).show()
                        etSignInPass.text.clear()
                        showSignIn()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goToMain(username: String) {
        prefs.edit().putString("current_user", username).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun getAccounts(): JSONObject {
        val raw = prefs.getString("accounts", "{}")!!
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun hasAnyAccount(): Boolean = getAccounts().length() > 0
    private fun accountExists(username: String): Boolean = getAccounts().has(username.lowercase())
    private fun getPassword(username: String): String? =
        getAccounts().optString(username.lowercase(), null).takeIf { it?.isNotEmpty() == true }

    private fun saveAccount(username: String, password: String) {
        val accounts = getAccounts()
        accounts.put(username.lowercase(), password)
        prefs.edit().putString("accounts", accounts.toString()).apply()
    }

    private fun showError(msg: String) {
        tvError.text       = msg
        tvError.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearError() = showError("")
}
