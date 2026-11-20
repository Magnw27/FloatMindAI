package id.floatmind.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val requestCapture = 701
    private val requestNotifications = 702
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("floatmind", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "FloatMind AI"
            textSize = 28f
            setTextColor(0xFF172033.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Asisten belajar layar untuk membaca materi dan memahami soal latihan."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(18))
        })
        root.addView(TextView(this).apply { text = "OPENROUTER API KEY"; textSize = 12f })
        apiKeyInput = EditText(this).apply {
            hint = "Tempel API key milikmu"
            singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SecurePrefs.readApiKey(this@MainActivity))
        }
        root.addView(apiKeyInput, matchWrap())
        root.addView(TextView(this).apply {
            text = "MODEL SLUG"
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
        modelInput = EditText(this).apply {
            hint = "contoh: openai/gpt-4o-mini"
            singleLine = true
            setText(prefs.getString("model", "openai/gpt-4o-mini"))
        }
        root.addView(modelInput, matchWrap())

        val save = Button(this).apply { text = "Simpan pengaturan" }
        root.addView(save)
        save.setOnClickListener { saveSettings(); toast("Pengaturan disimpan. API key dienkripsi di perangkat.") }

        val start = Button(this).apply { text = "Aktifkan Floating AI" }
        root.addView(start)
        start.setOnClickListener {
            saveSettings()
            if (SecurePrefs.readApiKey(this).isBlank()) {
                toast("Masukkan API key OpenRouter terlebih dahulu")
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                toast("Izinkan FloatMind tampil di atas aplikasi lain, lalu tekan Aktifkan lagi.")
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
        }

        val stop = Button(this).apply { text = "Hentikan Floating AI" }
        root.addView(stop)
        stop.setOnClickListener {
            stopService(Intent(this, CaptureOverlayService::class.java))
            toast("Sesi FloatMind dihentikan")
        }

        statusText = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
            text = "Privasi: layar hanya diproses setelah kamu menekan Scan. Teks OCR dikirim ke OpenRouter untuk dijelaskan."
        }
        root.addView(statusText, matchWrap())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveSettings() {
        SecurePrefs.saveApiKey(this, apiKeyInput.text.toString().trim())
        getSharedPreferences("floatmind", Context.MODE_PRIVATE).edit()
            .putString("model", modelInput.text.toString().trim().ifBlank { "openai/gpt-4o-mini" })
            .apply()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode != RESULT_OK || data == null) {
                toast("Izin screen capture tidak diberikan")
                return
            }
            val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureOverlayService.EXTRA_PROJECTION_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            toast("Sesi aktif. Buka halaman materi, lalu tekan bubble AI.")
        }
    }

    private fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
