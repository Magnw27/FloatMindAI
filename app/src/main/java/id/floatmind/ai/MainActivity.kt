package id.floatmind.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE = 701
        private const val REQUEST_NOTIFICATIONS = 702
        private const val PREFS_NAME = "floatmind"
        private const val DEFAULT_MODEL = "openai/gpt-4o-mini"
    }

    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var statusText: TextView
    private var waitingForNotificationPermission = false

    private val background = Color.rgb(10, 15, 28)
    private val surface = Color.rgb(20, 29, 48)
    private val surfaceSoft = Color.rgb(28, 40, 63)
    private val accent = Color.rgb(88, 124, 255)
    private val textPrimary = Color.rgb(241, 245, 255)
    private val textSecondary = Color.rgb(166, 180, 205)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setBackgroundColor(this@MainActivity.background)
        }

        content.addView(label("FLOATMIND AI", 12f, accent, true).apply {
            letterSpacing = 0.18f
        })
        content.addView(label("Belajar lebih mudah.\nPahami apa yang ada di layar.", 27f, textPrimary, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(label(
            "Asisten belajar mengambang untuk membaca materi, mengenali teks, dan menjelaskan konsep melalui AI.",
            14f,
            textSecondary
        ).apply { setPadding(0, 0, 0, dp(22)) })

        val settingsCard = card()
        settingsCard.addView(label("KONEKSI AI", 12f, accent, true))
        settingsCard.addView(label("OpenRouter API key", 14f, textPrimary, true).apply {
            setPadding(0, dp(16), 0, dp(7))
        })
        apiKeyInput = EditText(this).apply {
            hint = "sk-or-..."
            setSingleLine(true)
            textSize = 14f
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceSoft, 12)
            setText(SecurePrefs.readApiKey(this@MainActivity))
        }
        settingsCard.addView(apiKeyInput, matchWrap())

        settingsCard.addView(label("Model slug", 14f, textPrimary, true).apply {
            setPadding(0, dp(16), 0, dp(7))
        })
        modelInput = EditText(this).apply {
            hint = DEFAULT_MODEL
            setSingleLine(true)
            textSize = 14f
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceSoft, 12)
            setText(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("model", DEFAULT_MODEL))
        }
        settingsCard.addView(modelInput, matchWrap())
        settingsCard.addView(label(
            "API key disimpan terenkripsi menggunakan Android Keystore. Jangan bagikan key kepada siapa pun.",
            12f,
            textSecondary
        ).apply { setPadding(0, dp(12), 0, 0) })

        val saveButton = actionButton("Simpan pengaturan", accent)
        settingsCard.addView(saveButton, buttonParams(top = 18))
        saveButton.setOnClickListener {
            if (saveSettings()) showToast("Pengaturan berhasil disimpan.")
        }
        content.addView(settingsCard, matchWrap())

        content.addView(label("SESI FLOATING AI", 12f, accent, true).apply {
            setPadding(0, dp(24), 0, dp(10))
        })
        val startButton = actionButton("Aktifkan Floating AI", Color.rgb(48, 190, 143))
        content.addView(startButton, buttonParams())
        startButton.setOnClickListener {
            if (!saveSettings()) return@setOnClickListener
            if (SecurePrefs.readApiKey(this).isBlank()) {
                showToast("Masukkan API key OpenRouter terlebih dahulu.")
                apiKeyInput.requestFocus()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                showToast("Izinkan FloatMind tampil di atas aplikasi lain, lalu aktifkan kembali.")
                return@setOnClickListener
            }
            requestNotificationPermissionOrContinue()
        }

        val stopButton = actionButton("Hentikan sesi", Color.rgb(174, 71, 86))
        content.addView(stopButton, buttonParams(top = 10))
        stopButton.setOnClickListener {
            stopService(Intent(this, CaptureOverlayService::class.java))
            setStatus("Sesi dihentikan. Kamu dapat mengaktifkannya kembali kapan saja.")
        }

        val statusCard = card().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusCard.addView(label("STATUS", 11f, accent, true))
        statusText = label(
            "Belum ada sesi aktif. Simpan pengaturan lalu aktifkan Floating AI untuk memulai.",
            13f,
            textSecondary
        ).apply { setPadding(0, dp(7), 0, 0) }
        statusCard.addView(statusText)
        content.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        content.addView(label(
            "CARA KERJA & PRIVASI\n1. Berikan izin overlay dan screen capture Android.\n2. Buka materi belajar, lalu tekan bubble AI.\n3. Tekan Scan saat kamu ingin membaca layar.\n4. OCR dilakukan di perangkat; hanya teks hasil OCR yang dikirim ke OpenRouter.\n\nJangan pindai kata sandi, percakapan pribadi, atau informasi sensitif. Beberapa aplikasi melindungi layarnya dan tidak dapat dipindai.",
            12f,
            textSecondary
        ).apply { setPadding(dp(2), dp(20), dp(2), 0) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(this@MainActivity.background)
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun requestNotificationPermissionOrContinue() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingForNotificationPermission = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        } else {
            requestScreenCaptureConsent()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && waitingForNotificationPermission) {
            waitingForNotificationPermission = false
            requestScreenCaptureConsent()
        }
    }

    private fun requestScreenCaptureConsent() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (error: Exception) {
            setStatus("Tidak dapat meminta izin screen capture: ${error.localizedMessage ?: "kesalahan tidak diketahui"}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Izin screen capture tidak diberikan. Tidak ada layar yang direkam.")
            return
        }

        val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
            putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            setStatus("FloatMind aktif. Buka materi belajar, tekan bubble AI, lalu pilih Scan.")
        } catch (error: Exception) {
            setStatus("Gagal memulai sesi: ${error.localizedMessage ?: "periksa izin aplikasi"}")
        }
    }

    private fun saveSettings(): Boolean {
        val key = apiKeyInput.text.toString().trim()
        val model = modelInput.text.toString().trim().ifBlank { DEFAULT_MODEL }
        if (model.length > 180 || !Regex("^[A-Za-z0-9._/-]+$").matches(model)) {
            showToast("Format model slug tidak valid.")
            return false
        }
        if (!SecurePrefs.saveApiKey(this, key)) {
            showToast("Gagal menyimpan API key dengan aman. Periksa dukungan keamanan perangkat.")
            return false
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("model", model)
            .apply()
        return true
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(surface, 18)
        elevation = dp(3).toFloat()
    }

    private fun actionButton(title: String, color: Int) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 14)
        minHeight = dp(50)
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun buttonParams(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(52)
    ).apply { topMargin = dp(top) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
