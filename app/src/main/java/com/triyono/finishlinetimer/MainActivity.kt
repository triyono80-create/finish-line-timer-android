package com.triyono.finishlinetimer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var latestText: TextView
    private lateinit var countText: TextView
    private lateinit var recordButton: Button
    private lateinit var scanButton: Button
    private lateinit var resetButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var scanning = false
    private var lastAnalyzedAt = 0L
    private var candidate = ""
    private var candidateHits = 0
    private var lastAcceptedAt = 0L
    private val processing = AtomicBoolean(false)
    private val seen = linkedSetOf<String>()
    private val rows = mutableListOf<JSONObject>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.CAMERA] == true
        if (ok) startCamera() else statusText.text = "Izin kamera diperlukan."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
        loadRows()
        buildUi()
        requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 16)
            setBackgroundColor(0xFFEEF3F7.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        val title = TextView(this).apply {
            text = "FINISH LINE TIMER\nRACE TIMING • OFFLINE"
            textSize = 22f
            setTextColor(0xFF10283F.toInt())
            setPadding(0, 0, 0, 14)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        previewView = PreviewView(this).apply {
            // Fixed preview height leaves room for every control on smaller phones.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusText = TextView(this).apply {
            text = "Menyiapkan kamera..."
            textSize = 14f
            setTextColor(0xFF24516C.toInt())
            setPadding(0, 12, 0, 8)
        }
        latestText = TextView(this).apply {
            text = "Nomor terakhir: —"
            textSize = 20f
            setTextColor(0xFF10283F.toInt())
            setPadding(0, 6, 0, 6)
        }
        countText = TextView(this).apply {
            text = "Total finisher: ${rows.size}"
            textSize = 14f
            setTextColor(0xFF42596B.toInt())
            setPadding(0, 0, 0, 10)
        }
        scanButton = Button(this).apply {
            text = "MULAI PEMBACAAN NOMOR"
            setOnClickListener {
                scanning = !scanning
                text = if (scanning) "HENTIKAN PEMBACAAN" else "MULAI PEMBACAAN NOMOR"
                statusText.text = if (scanning) "Pemindaian aktif. Arahkan kamera ke nomor dada." else "Pemindaian dihentikan."
            }
        }
        recordButton = Button(this).apply {
            text = "MULAI REKAM VIDEO"
            setOnClickListener { toggleRecording() }
        }
        resetButton = Button(this).apply {
            text = "RESET DATA LOMBA"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            minHeight = dp(52)
            setOnClickListener { confirmResetRaceData() }
        }
        val hint = TextView(this).apply {
            text = "Pastikan nomor dada terlihat jelas. Pembacaan otomatis memerlukan dua hasil yang konsisten. Periksa hasil sebelum dipakai sebagai hasil resmi."
            textSize = 12f
            setTextColor(0xFF607486.toInt())
            setPadding(0, 8, 0, 0)
        }
        page.addView(title)
        page.addView(previewView)
        page.addView(statusText)
        page.addView(latestText)
        page.addView(countText)
        page.addView(scanButton)
        page.addView(recordButton)
        page.addView(resetButton)
        page.addView(hint)
        setContentView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image -> analyze(image) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture, analysis)
                statusText.text = "Kamera aktif. Tekan MULAI REKAM VIDEO dan MULAI PEMBACAAN NOMOR."
            } catch (e: Exception) {
                statusText.text = "Gagal mengaktifkan kamera: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!scanning || now - lastAnalyzedAt < 450 || !processing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastAnalyzedAt = now
        val media = proxy.image
        if (media == null) {
            processing.set(false); proxy.close(); return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val normalized = result.text.uppercase(Locale.ROOT)
                    .replace(" ", "").replace("-", "")
                val match = Regex("""(?<![A-Z0-9])[A-Z][0-9]{2,5}(?![A-Z0-9])""")
                    .find(normalized)?.value
                if (match != null) acceptCandidate(match)
            }
            .addOnFailureListener { statusText.post { statusText.text = "OCR: ${it.localizedMessage}" } }
            .addOnCompleteListener {
                processing.set(false)
                proxy.close()
            }
    }

    @Synchronized
    private fun acceptCandidate(value: String) {
        if (value == candidate) candidateHits++ else {
            candidate = value
            candidateHits = 1
        }
        if (candidateHits < 2) return
        val now = System.currentTimeMillis()
        if (seen.contains(value) || now - lastAcceptedAt < 1200) return
        lastAcceptedAt = now
        seen.add(value)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
        val row = JSONObject().put("bib", value).put("time", stamp).put("epoch", now)
        rows.add(row)
        saveRows()
        runOnUiThread {
            latestText.text = "Nomor terakhir: $value"
            countText.text = "Total finisher: ${rows.size}"
            statusText.text = "✓ $value tercatat pada $stamp"
            if (::tts.isInitialized) tts.speak("Nomor $value tercatat", TextToSpeech.QUEUE_ADD, null, value + now)
        }
    }

    private fun confirmResetRaceData() {
        AlertDialog.Builder(this)
            .setTitle("Reset data lomba?")
            .setMessage("Semua catatan finisher dan nomor terakhir akan dihapus dari aplikasi. Video yang sudah direkam tidak akan dihapus. Tindakan ini tidak dapat dibatalkan.")
            .setNegativeButton("BATAL", null)
            .setPositiveButton("RESET DATA") { _, _ ->
                rows.clear()
                seen.clear()
                candidate = ""
                candidateHits = 0
                lastAcceptedAt = 0L
                scanning = false
                scanButton.text = "MULAI PEMBACAAN NOMOR"
                saveRows()
                latestText.text = "Nomor terakhir: —"
                countText.text = "Total finisher: 0"
                statusText.text = "Data lomba direset. Siap memulai lomba baru."
                Toast.makeText(this, "Data finisher berhasil direset.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return
        if (recording != null) {
            recording?.stop()
            recording = null
            recordButton.text = "MULAI REKAM VIDEO"
            statusText.text = "Rekaman dihentikan. Video disimpan di folder Movies/FinishLineTimer."
            return
        }
        val name = "FinishLine_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FinishLineTimer")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        recording = capture.output.prepareRecording(this, output)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordButton.text = "BERHENTI REKAM"
                        statusText.text = "Video sedang direkam."
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) statusText.text = "Gagal menyimpan video: ${event.error}"
                        else statusText.text = "Video tersimpan di Movies/FinishLineTimer."
                    }
                }
            }
    }

    private fun loadRows() {
        val raw = getSharedPreferences("finish_timer", MODE_PRIVATE).getString("rows", "[]") ?: "[]"
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            rows.add(obj)
            seen.add(obj.optString("bib"))
        }
    }

    private fun saveRows() {
        val array = JSONArray()
        rows.forEach { array.put(it) }
        getSharedPreferences("finish_timer", MODE_PRIVATE).edit().putString("rows", array.toString()).apply()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale("id", "ID")
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        recognizer.close()
    }
}
