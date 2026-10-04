package com.ant.inventory

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }

    private lateinit var tabEmail: TextView
    private lateinit var tabPhone: TextView
    private lateinit var layoutEmail: View
    private lateinit var layoutPhone: View
    private lateinit var layoutOtp: View
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnEmailLogin: Button
    private lateinit var btnEmailRegister: Button
    private lateinit var etPhone: EditText
    private lateinit var btnSendOtp: Button
    private lateinit var etOtp: EditText
    private lateinit var btnVerifyOtp: Button
    private lateinit var tvResendOtp: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private var verificationId: String = ""
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (auth.currentUser != null) { goToMain(); return }
        setContentView(R.layout.activity_login)
        bindViews()
        setupTabs()
        setupListeners()
    }

    private fun bindViews() {
        tabEmail         = findViewById(R.id.tabEmail)
        tabPhone         = findViewById(R.id.tabPhone)
        layoutEmail      = findViewById(R.id.layoutEmail)
        layoutPhone      = findViewById(R.id.layoutPhone)
        layoutOtp        = findViewById(R.id.layoutOtp)
        etEmail          = findViewById(R.id.etEmail)
        etPassword       = findViewById(R.id.etPassword)
        btnEmailLogin    = findViewById(R.id.btnEmailLogin)
        btnEmailRegister = findViewById(R.id.btnEmailRegister)
        etPhone          = findViewById(R.id.etPhone)
        btnSendOtp       = findViewById(R.id.btnSendOtp)
        etOtp            = findViewById(R.id.etOtp)
        btnVerifyOtp     = findViewById(R.id.btnVerifyOtp)
        tvResendOtp      = findViewById(R.id.tvResendOtp)
        progressBar      = findViewById(R.id.progressBar)
        tvError          = findViewById(R.id.tvError)
    }

    private fun setupTabs() {
        showEmailTab()
        tabEmail.setOnClickListener { showEmailTab() }
        tabPhone.setOnClickListener { showPhoneTab() }
    }

    private fun showEmailTab() {
        tabEmail.setBackgroundResource(R.drawable.tab_selected)
        tabPhone.setBackgroundResource(R.drawable.tab_unselected)
        tabEmail.setTextColor(0xFFFFFFFF.toInt())
        tabPhone.setTextColor(0xFF1565C0.toInt())
        layoutEmail.visibility = View.VISIBLE
        layoutPhone.visibility = View.GONE
        layoutOtp.visibility   = View.GONE
        clearError()
    }

    private fun showPhoneTab() {
        tabPhone.setBackgroundResource(R.drawable.tab_selected)
        tabEmail.setBackgroundResource(R.drawable.tab_unselected)
        tabPhone.setTextColor(0xFFFFFFFF.toInt())
        tabEmail.setTextColor(0xFF1565C0.toInt())
        layoutEmail.visibility = View.GONE
        layoutPhone.visibility = View.VISIBLE
        layoutOtp.visibility   = View.GONE
        clearError()
    }

    private fun setupListeners() {
        btnEmailLogin.setOnClickListener    { loginWithEmail() }
        btnEmailRegister.setOnClickListener { registerWithEmail() }
        btnSendOtp.setOnClickListener       { sendOtp() }
        btnVerifyOtp.setOnClickListener     { verifyOtp() }
        tvResendOtp.setOnClickListener      { resendOtp() }
    }

    private fun loginWithEmail() {
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (email.isEmpty() || password.isEmpty()) { showError("Enter email and password"); return }
        showLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { goToMain() }
            .addOnFailureListener { showError(friendlyError(it.message)); showLoading(false) }
    }

    private fun registerWithEmail() {
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (email.isEmpty())     { showError("Enter an email address"); return }
        if (password.length < 6) { showError("Password must be at least 6 characters"); return }
        showLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { goToMain() }
            .addOnFailureListener { showError(friendlyError(it.message)); showLoading(false) }
    }

    private fun sendOtp() {
        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty()) { showError("Enter phone number with country code e.g. +441234567890"); return }
        showLoading(true)
        PhoneAuthProvider.verifyPhoneNumber(
            PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(phoneCallbacks)
                .build()
        )
    }

    private fun resendOtp() {
        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty() || resendToken == null) { showPhoneTab(); return }
        showLoading(true)
        PhoneAuthProvider.verifyPhoneNumber(
            PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(phoneCallbacks)
                .setForceResendingToken(resendToken!!)
                .build()
        )
    }

    private val phoneCallbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            signInWithCredential(credential)
        }
        override fun onVerificationFailed(e: FirebaseException) {
            showError(friendlyError(e.message)); showLoading(false)
        }
        override fun onCodeSent(vId: String, token: PhoneAuthProvider.ForceResendingToken) {
            verificationId = vId
            resendToken    = token
            showLoading(false)
            layoutPhone.visibility = View.GONE
            layoutOtp.visibility   = View.VISIBLE
            clearError()
            Toast.makeText(this@LoginActivity, "OTP sent!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyOtp() {
        val otp = etOtp.text.toString().trim()
        if (otp.length < 6) { showError("Enter the 6-digit OTP"); return }
        showLoading(true)
        signInWithCredential(PhoneAuthProvider.getCredential(verificationId, otp))
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { goToMain() }
            .addOnFailureListener { showError(friendlyError(it.message)); showLoading(false) }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility     = if (show) View.VISIBLE else View.GONE
        btnEmailLogin.isEnabled    = !show
        btnEmailRegister.isEnabled = !show
        btnSendOtp.isEnabled       = !show
        btnVerifyOtp.isEnabled     = !show
    }

    private fun showError(msg: String) {
        tvError.text       = msg
        tvError.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearError() = showError("")

    private fun friendlyError(msg: String?): String = when {
        msg == null                                -> "Something went wrong. Please try again."
        msg.contains("password")                  -> "Incorrect password."
        msg.contains("no user record")            -> "No account found. Please register first."
        msg.contains("already in use")            -> "Email already registered. Please sign in."
        msg.contains("badly formatted")           -> "Invalid email address."
        msg.contains("invalid-phone-number")      -> "Invalid phone number. Use format: +441234567890"
        msg.contains("invalid-verification-code") -> "Wrong OTP. Please check and try again."
        msg.contains("too-many-requests")         -> "Too many attempts. Please wait and try again."
        else                                      -> msg
    }
}
