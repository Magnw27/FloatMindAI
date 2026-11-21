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
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE = 701
        private const val REQUEST_NOTIFICATIONS = 702
        private const val PREFS_NAME = "floatmind"
        private const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1"
    }

    private lateinit var providerSpinner: Spinner
    private lateinit var endpointInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var twoPassCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var scanModelsButton: Button
    private lateinit var checkButton: Button
    private val busy = AtomicBoolean(false)
    private var models: List<ModelOption> = emptyList()
    private var waitingForNotificationPermission = false

    private val ink = Color.rgb(238, 243, 255)
    private val muted = Color.rgb(163, 177, 203)
    private val accent = Color.rgb(125, 145, 255)
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 13, 28)
        window.navigationBarColor = Color.rgb(10, 13, 28)
        window.decorView.systemUiVisibility = 0

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(34))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(12, 17, 38), Color.rgb(24, 27, 57), Color.rgb(12, 18, 32))
            )
        }

        content.addView(label("FLOATMIND  /  STUDY COMPANION", 11f, accent, true))
        content.addView(label("Pahami layar.\nBukan sekadar membaca.", 30f, ink, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(label(
            "Asisten belajar mengambang yang mengubah materi di layar menjadi penjelasan yang lebih mudah dipahami.",
            14f, muted
        ).apply { setPadding(0, 0, 0, dp(22)) })

        val configCard = glassCard()
        configCard.addView(sectionTitle("01  /  PROVIDER & MODEL"))
        configCard.addView(fieldLabel("Pilih layanan AI"))
        providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ProviderKind.entries.map { it.label }
            )
            setSelection(prefs.getInt("provider_index", 0).coerceIn(0, ProviderKind.entries.lastIndex))
        }
        configCard.addView(providerSpinner, fieldParams())
        configCard.addView(fieldLabel("Public Base URL · Cloudflare Quick Tunnel / OmniRoute"))
        endpointInput = input("https://nama-acak.trycloudflare.com").apply {
            setText(prefs.getString("base_url", ""))
        }
        configCard.addView(endpointInput, fieldParams())
        configCard.addView(label("Tempel HTTPS Public URL dari Quick Tunnel. Tidak perlu menambahkan /v1; FloatMind akan menyesuaikan endpoint /v1 secara otomatis. URL tunnel bisa berubah saat tunnel dibuat ulang.", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
        configCard.addView(fieldLabel("API key"))
        apiKeyInput = input("Tempel API key provider").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SecurePrefs.readApiKey(this@MainActivity))
        }
        configCard.addView(apiKeyInput, fieldParams())
        configCard.addView(fieldLabel("Model · scan otomatis dari provider"))
        modelSpinner = Spinner(this)
        modelSpinner.adapter = modelAdapter(emptyList())
        configCard.addView(modelSpinner, fieldParams())
        val modelActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanModelsButton = actionButton("Scan model", accent)
        modelActions.addView(scanModelsButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        checkButton = actionButton("Check config", Color.rgb(47, 178, 145))
        modelActions.addView(checkButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        configCard.addView(modelActions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        configCard.addView(label(
            "Model gratis diprioritaskan bila provider menyertakan metadata harga atau suffix :free. Kuota dan kebijakan gratis dapat berubah.",
            11f, muted
        ).apply { setPadding(0, dp(12), 0, 0) })
        twoPassCheck = CheckBox(this).apply {
            text = "Review jawaban dengan AI tahap kedua"
            setTextColor(ink)
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("two_pass", true)
        }
        configCard.addView(twoPassCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        configCard.addView(label(
            "Dua tahap memakai dua request model: tahap pertama menyusun jawaban, tahap kedua memeriksa ketepatan dan memperbaiki hasil. Lebih lambat dan dapat memakai kuota sekitar dua kali.",
            11f, muted
        ))
        val save = actionButton("Simpan konfigurasi", Color.rgb(92, 112, 230))
        configCard.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(16) })
        content.addView(configCard, fieldParams(top = 4))

        val sessionCard = glassCard()
        sessionCard.addView(sectionTitle("02  /  FLOATING SESSION"))
        sessionCard.addView(label(
            "Aktifkan bubble FloatMind di atas aplikasi belajar. Layar hanya diproses setelah kamu menekan tombol Scan.",
            13f, muted
        ))
        val start = actionButton("Aktifkan Floating AI", Color.rgb(44, 176, 139))
        sessionCard.addView(start, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        val stop = actionButton("Hentikan sesi", Color.rgb(173, 78, 99))
        sessionCard.addView(stop, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        content.addView(sessionCard, fieldParams(top = 14))

        val statusCard = glassCard()
        statusCard.addView(sectionTitle("SYSTEM STATUS"))
        statusText = label(
            "Belum ada pemeriksaan konfigurasi. Scan model lalu jalankan Check config sebelum memulai.",
            13f, muted
        )
        statusCard.addView(statusText)
        content.addView(statusCard, fieldParams(top = 14))

        val aboutCard = glassCard()
        aboutCard.addView(sectionTitle("ABOUT FLOATMIND"))
        aboutCard.addView(label(
            "FloatMind AI adalah proyek asisten belajar Android yang berfokus pada pemahaman materi, OCR di perangkat, dan pilihan provider AI yang fleksibel.",
            13f, ink
        ))
        aboutCard.addView(label(
            "Dikembangkan oleh Arif, pelajar dan developer dari Indonesia.",
            12f, muted
        ).apply { setPadding(0, dp(12), 0, 0) })
        aboutCard.addView(label("FLOATMIND AI  •  VERSION 2.0", 10f, accent, true).apply {
            setPadding(0, dp(14), 0, 0)
        })
        content.addView(aboutCard, fieldParams(top = 14))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val kind = ProviderKind.entries[position]
                endpointInput.isEnabled = kind == ProviderKind.OPENAI_COMPATIBLE
                endpointInput.alpha = if (endpointInput.isEnabled) 1f else 0.45f
                if (kind == ProviderKind.OPENROUTER && endpointInput.text.isNullOrBlank()) {
                    endpointInput.setText(DEFAULT_ENDPOINT)
                }
                models = emptyList()
                modelSpinner.adapter = modelAdapter(emptyList())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        scanModelsButton.setOnClickListener { scanModels() }
        checkButton.setOnClickListener { checkConfiguration() }
        save.setOnClickListener { if (saveSettings()) toast("Konfigurasi tersimpan dengan aman.") }
        start.setOnClickListener {
            if (!saveSettings()) return@setOnClickListener
            requestOverlayAndCapture()
        }
        stop.setOnClickListener {
            stopService(Intent(this, CaptureOverlayService::class.java))
            setStatus("Sesi dihentikan.")
        }
        endpointInput.isEnabled = selectedProvider() == ProviderKind.OPENAI_COMPATIBLE
        endpointInput.alpha = if (endpointInput.isEnabled) 1f else 0.45f
    }

    private fun scanModels() {
        if (!busy.compareAndSet(false, true)) return
        val config = readConfig(requireModel = false)
        if (config == null) { busy.set(false); return }
        setBusy(true, "Mengambil daftar model dari ${config.provider.label}…")
        Thread {
            try {
                val found = AiProvider.listModels(config)
                runOnUiThread {
                    models = found
                    modelSpinner.adapter = modelAdapter(found)
                    val saved = prefs.getString("model", null)
                    val preferred = found.indexOfFirst { it.id == saved }.takeIf { it >= 0 }
                        ?: found.indexOfFirst { it.free }.takeIf { it >= 0 } ?: 0
                    if (found.isNotEmpty()) modelSpinner.setSelection(preferred)
                    setBusy(false, "Ditemukan ${found.size} model. Model gratis yang terdeteksi ditempatkan di urutan atas.")
                    busy.set(false)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false, "Scan gagal: ${error.message ?: "periksa provider dan koneksi."}")
                    busy.set(false)
                }
            }
        }.start()
    }

    private fun checkConfiguration() {
        if (!busy.compareAndSet(false, true)) return
        val config = readConfig(requireModel = true)
        if (config == null) { busy.set(false); return }
        setBusy(true, "Memeriksa daftar model dan menguji respons model…")
        Thread {
            try {
                val result = AiProvider.check(config)
                runOnUiThread {
                    setBusy(false, "✓ $result")
                    busy.set(false)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false, "✕ Check config gagal: ${error.message ?: "kesalahan tidak diketahui"}")
                    busy.set(false)
                }
            }
        }.start()
    }

    private fun readConfig(requireModel: Boolean): AiConfig? {
        val provider = selectedProvider()
        val key = apiKeyInput.text.toString().trim()
        val model = selectedModel()
        val endpoint = endpointInput.text.toString().trim()
        if (provider != ProviderKind.OPENAI_COMPATIBLE && key.isBlank()) {
            toast("Masukkan API key provider terlebih dahulu.")
            return null
        }
        if (provider == ProviderKind.OPENAI_COMPATIBLE && endpoint.isBlank()) {
            toast("Masukkan Base URL 9Router / OmniRoute.")
            return null
        }
        if (requireModel && model.isBlank()) {
            toast("Scan model dan pilih model terlebih dahulu.")
            return null
        }
        return AiConfig(provider, key, model, endpoint)
    }

    private fun saveSettings(): Boolean {
        val config = readConfig(requireModel = true) ?: return false
        if (config.provider == ProviderKind.OPENAI_COMPATIBLE) {
            val valid = config.baseUrl.startsWith("https://") ||
                config.baseUrl.startsWith("http://localhost") ||
                config.baseUrl.startsWith("http://127.0.0.1")
            if (!valid) { toast("Base URL wajib HTTPS (HTTP hanya untuk localhost)."); return false }
        }
        if (!SecurePrefs.saveApiKey(this, config.apiKey)) {
            toast("Gagal menyimpan API key secara terenkripsi.")
            return false
        }
        prefs.edit()
            .putInt("provider_index", config.provider.ordinal)
            .putString("base_url", config.baseUrl)
            .putString("model", config.model)
            .putBoolean("two_pass", twoPassCheck.isChecked)
            .apply()
        return true
    }

    private fun selectedProvider(): ProviderKind =
        ProviderKind.entries[providerSpinner.selectedItemPosition.coerceIn(0, ProviderKind.entries.lastIndex)]

    private fun selectedModel(): String =
        if (modelSpinner.selectedItemPosition in models.indices) models[modelSpinner.selectedItemPosition].id else ""

    private fun modelAdapter(items: List<ModelOption>): ArrayAdapter<String> {
        val values = if (items.isEmpty()) listOf("Scan model untuk memuat pilihan") else
            items.map { if (it.free) "✦  ${it.id}  ·  FREE" else it.id }
        return ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun requestOverlayAndCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Izinkan FloatMind tampil di atas aplikasi lain, lalu aktifkan kembali.")
            return
        }
        requestNotificationPermissionOrContinue()
    }

    private fun requestNotificationPermissionOrContinue() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingForNotificationPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else requestScreenCaptureConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
            setStatus("Tidak dapat meminta izin screen capture: ${error.localizedMessage ?: "unknown"}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Izin screen capture tidak diberikan.")
            return
        }
        val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
            putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            setStatus("FloatMind aktif. Tekan bubble lalu Scan saat materi siap.")
        } catch (error: Exception) {
            setStatus("Gagal memulai sesi: ${error.localizedMessage ?: "periksa izin aplikasi"}")
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        scanModelsButton.isEnabled = !busy
        checkButton.isEnabled = !busy
        statusText.text = message
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xE6232D4B.toInt(), 0xD91A2440.toInt())
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0x553D5B91)
        }
        elevation = dp(5).toFloat()
    }

    private fun sectionTitle(value: String) = label(value, 11f, accent, true).apply {
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(14))
    }

    private fun fieldLabel(value: String) = label(value, 12f, muted, true).apply {
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
        }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(muted)
        isSingleLine = true
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = GradientDrawable().apply {
            setColor(0xCC111A30.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x553D5B91)
        }
    }

    private fun actionButton(title: String, color: Int) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, shiftColor(color, 0.78f))
        ).apply { cornerRadius = dp(13).toFloat() }
        stateListAnimator = null
    }

    private fun shiftColor(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(),
        (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt()
    )

    private fun fieldParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
