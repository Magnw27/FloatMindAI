package id.floatmind.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureOverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val ACTION_STOP = "id.floatmind.ai.STOP"
        private const val CHANNEL_ID = "floatmind_capture"
        private const val NOTIFICATION_ID = 91
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: TextView
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var resultView: TextView? = null
    private var scanButton: Button? = null
    private val capturePending = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("FloatMindCapture").apply { start() }
    private val worker by lazy { Handler(workerThread.looper) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        createNotification()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = readProjectionIntent(intent)
        if (code != Activity.RESULT_OK || data == null) {
            stopSession()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mainHandler.post { stopSession() } }
            }, worker)
            setupCapture()
            showBubble()
        } catch (error: Exception) {
            Toast.makeText(this, "Gagal memulai screen capture: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            stopSession()
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "FloatMind screen session", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, CaptureOverlayService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        notification.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FloatMind AI aktif")
            .setContentText("Layar hanya dipindai saat tombol Scan ditekan.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hentikan", stopPending).build())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification.build())
    }

    @Suppress("DEPRECATION")
    private fun readProjectionIntent(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        }
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!capturePending.compareAndSet(true, false)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage()
            if (image == null) {
                showCaptureError("Frame layar belum tersedia. Coba Scan lagi.")
                return@setOnImageAvailableListener
            }
            val bitmap = try { imageToBitmap(image) } catch (e: Exception) {
                showCaptureError("Gagal memproses frame: ${e.localizedMessage}")
                null
            } finally { image.close() }
            if (bitmap != null) runOcr(bitmap)
        }, worker)
        virtualDisplay = projection?.createVirtualDisplay(
            "FloatMindScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, worker
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == image.width) return padded
        val output = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return output
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "FM"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF8294FF.toInt(), 0xFF465CE0.toInt())).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x887F9BFF.toInt()) }
            setPadding(dp(17), dp(13), dp(17), dp(13))
            elevation = dp(8).toFloat()
            setOnTouchListener(BubbleTouchListener())
            setOnClickListener { togglePanel() }
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = resources.displayMetrics.heightPixels / 3 }
        windowManager.addView(bubble, bubbleParams)
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    try { windowManager.updateViewLayout(bubble, params) } catch (error: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }
            }
            return false
        }
    }

    private fun togglePanel() {
        if (panel != null) {
            try { windowManager.removeView(panel) } catch (error: Exception) {}
            panel = null
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF02A3655.toInt(), 0xF018243D.toInt())).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x665E7FC1) }
            elevation = dp(12).toFloat()
        }
        val title = TextView(this).apply { text = "FloatMind AI"; textSize = 19f; setTextColor(-1) }
        val hint = TextView(this).apply {
            text = "Buka materi atau soal latihan, lalu scan. Jangan gunakan untuk menyalin jawaban saat ujian berlangsung."
            textSize = 12f; setTextColor(0xFFCBD5E1.toInt()); setPadding(0, dp(6), 0, dp(10))
        }
        val output = TextView(this).apply {
            text = "Hasil penjelasan akan muncul di sini."
            textSize = 14f; setTextColor(-1); setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF263449.toInt())
        }
        val scan = Button(this).apply { text = "Scan layar & jelaskan" }
        val close = Button(this).apply { text = "Tutup panel" }
        box.addView(title); box.addView(hint)
        box.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, dp(260)))
        box.addView(scan); box.addView(close)
        resultView = output; scanButton = scan
        scan.setOnClickListener { requestScreenScan() }
        close.setOnClickListener { togglePanel() }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        panel = box
        windowManager.addView(box, params)
    }

    private fun requestScreenScan() {
        val output = resultView ?: return
        val button = scanButton ?: return
        if (!capturePending.compareAndSet(false, true)) return
        button.isEnabled = false
        output.text = "Memindai layar..."
        setOverlayVisible(false)
        worker.postDelayed({
            if (capturePending.compareAndSet(true, false)) {
                showCaptureError("Waktu capture habis. Pastikan sesi berbagi layar masih aktif, lalu coba lagi.")
            }
        }, 4500)
    }

    private fun runOcr(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                bitmap.recycle()
                val extracted = result.text.trim()
                if (extracted.isBlank()) showCaptureError("Teks tidak terdeteksi. Coba perbesar teks atau pilih halaman yang lebih jelas.")
                else askOpenRouter(extracted)
            }
            .addOnFailureListener { error ->
                bitmap.recycle()
                showCaptureError("OCR gagal: ${error.localizedMessage ?: "kesalahan tidak diketahui"}")
            }
    }

    private fun askOpenRouter(extracted: String) {
        val preferences = getSharedPreferences("floatmind", Context.MODE_PRIVATE)
        val provider = ProviderKind.entries.getOrNull(preferences.getInt("provider_index", 0))
            ?: ProviderKind.OPENROUTER
        val config = AiConfig(
            provider = provider,
            apiKey = SecurePrefs.readApiKey(this),
            model = preferences.getString("model", "").orEmpty(),
            baseUrl = preferences.getString("base_url", "").orEmpty()
        )
        val twoPass = preferences.getBoolean("two_pass", true)
        if (config.model.isBlank()) {
            showCaptureError("Model belum dipilih. Buka FloatMind, scan model, lalu simpan konfigurasi.")
            return
        }
        val sourceText = extracted.take(14000)
        showResult("Menyusun penjelasan dengan ${config.model}…")
        worker.post {
            try {
                val systemPrompt = "Kamu adalah tutor belajar yang akurat dan jelas. Jelaskan konsep dan langkah berpikir dengan bahasa Indonesia. Jangan mengarang fakta; jika informasi dari teks kurang, nyatakan keterbatasannya. Jika tampak seperti ujian atau penilaian yang sedang berlangsung, ajarkan konsep dan metode, bukan jawaban untuk disalin."
                val draft = AiProvider.complete(
                    config,
                    systemPrompt,
                    "Teks yang terbaca dari layar:\n$sourceText\n\nJelaskan materi dan cara berpikir untuk menyelesaikan latihan ini. Susun jawaban ringkas, bertahap, dan mudah dipahami.",
                    900
                )
                val finalAnswer = if (twoPass) {
                    showResult("Memeriksa konsistensi dan ketepatan jawaban…")
                    AiProvider.complete(
                        config,
                        "Kamu adalah reviewer akademik yang teliti. Periksa draf tutor terhadap sumber. Koreksi kesalahan, hapus klaim yang tidak didukung, perjelas langkah yang lompat, dan pertahankan bagian yang sudah benar. Jangan menambahkan fakta yang tidak dapat dipastikan. Keluarkan hanya jawaban final untuk pelajar dalam bahasa Indonesia.",
                        "SUMBER TEKS:\n$sourceText\n\nDRAF JAWABAN:\n$draft\n\nTinjau dan perbaiki draf tersebut. Jika ada informasi yang tidak tersedia di sumber, jelaskan batasannya.",
                        1000
                    )
                } else draft
                showResult(finalAnswer)
            } catch (error: Exception) {
                showCaptureError("Permintaan AI gagal: ${error.localizedMessage ?: "periksa konfigurasi provider, model, dan koneksi."}")
            }
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.INVISIBLE
        mainHandler.post {
            if (::bubble.isInitialized) bubble.visibility = state
            panel?.visibility = state
        }
    }

    private fun showResult(message: String) {
        mainHandler.post {
            if (::bubble.isInitialized) bubble.visibility = View.VISIBLE
            panel?.visibility = View.VISIBLE
            resultView?.text = message
            scanButton?.isEnabled = true
        }
    }

    private fun showCaptureError(message: String) = showResult(message)

    private fun stopSession() {
        capturePending.set(false)
        try { if (::windowManager.isInitialized && ::bubble.isInitialized) windowManager.removeView(bubble) } catch (error: Exception) {}
        try { if (::windowManager.isInitialized && panel != null) windowManager.removeView(panel) } catch (error: Exception) {}
        panel = null; resultView = null; scanButton = null
        try { virtualDisplay?.release() } catch (error: Exception) {}
        try { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() } catch (error: Exception) {}
        virtualDisplay = null; imageReader = null
        try { projection?.stop() } catch (error: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSessionResourcesOnly()
        try { recognizer.close() } catch (error: Exception) {}
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun stopSessionResourcesOnly() {
        try { if (::windowManager.isInitialized && ::bubble.isInitialized && bubble.isAttachedToWindow) windowManager.removeView(bubble) } catch (error: Exception) {}
        try { if (::windowManager.isInitialized && panel?.isAttachedToWindow == true) windowManager.removeView(panel) } catch (error: Exception) {}
        try { virtualDisplay?.release() } catch (error: Exception) {}
        try { imageReader?.close() } catch (error: Exception) {}
        virtualDisplay = null; imageReader = null; panel = null
        try { projection?.stop() } catch (error: Exception) {}
        projection = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
