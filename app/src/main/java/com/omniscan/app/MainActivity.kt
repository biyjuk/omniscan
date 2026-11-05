package com.omniscan.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val languages = listOf(
        "en" to "English", "hi" to "हिन्दी (Hindi)", "mr" to "मराठी (Marathi)",
        "gu" to "Gujarati", "ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada",
        "bn" to "Bengali", "ur" to "Urdu", "es" to "Spanish", "fr" to "French",
        "de" to "German", "ja" to "Japanese", "zh" to "Chinese", "ar" to "Arabic",
        "ru" to "Russian", "pt" to "Portuguese", "it" to "Italian"
    )
    private val sizes = intArrayOf(22, 28, 34, 40)

    private lateinit var preview: PreviewView
    private lateinit var statusText: TextView
    private lateinit var banner: TextView
    private lateinit var scroll: ScrollView
    private lateinit var resultText: TextView
    private lateinit var btnLang: MaterialButton
    private lateinit var btnSize: MaterialButton
    private lateinit var btnScript: MaterialButton
    private lateinit var btnListen: MaterialButton
    private lateinit var btnPause: MaterialButton

    private val prefs by lazy { getSharedPreferences("omniscan", Context.MODE_PRIVATE) }
    private var targetLang = "en"
    private var sizeIdx = 1
    private var devanagari = false

    @Volatile private var paused = false
    @Volatile private var busy = false
    private var lastRun = 0L
    private var lastKey = ""
    private var lastWords: Set<String> = emptySet()
    private var gen = 0
    private var currentSpeech = ""

    private lateinit var analysisExecutor: ExecutorService
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val deva by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    private val barcode by lazy { BarcodeScanning.getClient() }
    private val langId by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else statusText.text = "Camera permission is needed. Tap the screen to try again."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        banner = findViewById(R.id.banner)
        scroll = findViewById(R.id.scroll)
        resultText = findViewById(R.id.resultText)
        btnLang = findViewById(R.id.btnLang)
        btnSize = findViewById(R.id.btnSize)
        btnScript = findViewById(R.id.btnScript)
        btnListen = findViewById(R.id.btnListen)
        btnPause = findViewById(R.id.btnPause)

        analysisExecutor = Executors.newSingleThreadExecutor()
        targetLang = prefs.getString("lang", "en") ?: "en"
        sizeIdx = prefs.getInt("size", 1).coerceIn(0, sizes.size - 1)
        devanagari = prefs.getBoolean("deva", false)
        applyUi()

        btnLang.setOnClickListener { chooseLanguage() }
        btnSize.setOnClickListener {
            sizeIdx = (sizeIdx + 1) % sizes.size
            prefs.edit().putInt("size", sizeIdx).apply()
            applyUi()
        }
        btnScript.setOnClickListener {
            devanagari = !devanagari
            prefs.edit().putBoolean("deva", devanagari).apply()
            lastKey = ""
            lastWords = emptySet()
            applyUi()
        }
        btnPause.setOnClickListener {
            paused = !paused
            btnPause.text = if (paused) "Resume" else "Pause"
            statusText.text = if (paused) "Paused. Text will stay on screen." else "Scanning…"
        }
        btnListen.setOnClickListener { toggleSpeech() }
        statusText.setOnClickListener { ensureCamera() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { resetListen() }
                    override fun onError(utteranceId: String?) { resetListen() }
                })
                applyTtsLanguage()
            }
        }

        ensureCamera()
    }

    // ---------- UI helpers ----------

    private fun languageName(): String = languages.firstOrNull { it.first == targetLang }?.second ?: "English"

    private fun applyUi() {
        btnLang.text = languageName()
        btnSize.text = "Text: " + sizes[sizeIdx]
        btnScript.text = if (devanagari) "Script: Hindi/Marathi" else "Script: English"
        resultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[sizeIdx].toFloat())
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this)
            .setTitle("Show text in")
            .setItems(languages.map { it.second }.toTypedArray()) { _, i ->
                targetLang = languages[i].first
                prefs.edit().putString("lang", targetLang).apply()
                lastKey = ""
                lastWords = emptySet()
                applyUi()
                applyTtsLanguage()
            }
            .show()
    }

    private fun showBanner(msg: String?, color: Int) {
        if (msg == null) {
            banner.visibility = View.GONE
            return
        }
        banner.text = msg
        banner.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f
        }
        banner.visibility = View.VISIBLE
    }

    private fun show(body: String, bannerMsg: String?, bannerColor: Int) {
        resultText.text = body
        showBanner(bannerMsg, bannerColor)
        currentSpeech = (if (bannerMsg != null) "$bannerMsg. " else "") + body
        scroll.scrollTo(0, 0)
    }

    // ---------- Camera ----------

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1200, 1600))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
                statusText.text = "Scanning…"
            } catch (e: Exception) {
                statusText.text = "Could not start the camera"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = SystemClock.elapsedRealtime()
        if (media == null || paused || busy || now - lastRun < 1200) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val recognizer = if (devanagari) deva else latin
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.length < 3) {
                    finishFrame(proxy)
                } else if (MedicineParser.looksLikeMedicine(text)) {
                    barcode.process(input).addOnCompleteListener { t ->
                        val hasCode = t.isSuccessful && !t.result.isNullOrEmpty()
                        handleMedicine(text, hasCode)
                        finishFrame(proxy)
                    }
                } else {
                    handleSign(text)
                    finishFrame(proxy)
                }
            }
            .addOnFailureListener { finishFrame(proxy) }
    }

    private fun finishFrame(proxy: ImageProxy) {
        proxy.close()
        busy = false
    }

    // ---------- Signs: translate + hazard alerts ----------

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return inter.toFloat() / union >= 0.8f
    }

    private fun handleSign(text: String) {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (similar(words, lastWords)) return
        lastWords = words
        lastKey = ""
        val myGen = ++gen
        statusText.text = "Reading sign…"
        langId.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                if (myGen != gen) return@addOnSuccessListener
                val src = if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
                if (src == null || src == targetLang) {
                    showSign(text, text, myGen)
                } else {
                    translate(text, src, targetLang) { out ->
                        if (myGen == gen) showSign(out ?: text, text + " " + (out ?: ""), myGen)
                    }
                }
            }
            .addOnFailureListener { if (myGen == gen) showSign(text, text, myGen) }
    }

    private fun showSign(body: String, forHazard: String, myGen: Int) {
        statusText.text = "Sign detected"
        val hazard = HazardDetector.check(forHazard)
        localize(hazard) { msg ->
            if (myGen == gen) show(body, msg, Color.parseColor("#B91C1C"))
        }
    }

    // ---------- Medicine ----------

    private fun handleMedicine(text: String, hasCode: Boolean) {
        val info = MedicineParser.parse(text, hasCode)
        val summary = MedicineParser.summary(info)
        val key = "m:$summary|${info.expiryStatus}"
        if (key == lastKey) return
        lastKey = key
        lastWords = emptySet()
        val myGen = ++gen
        statusText.text = "Medicine label detected"

        val (bannerText, bannerColor) = expiryBanner(info)
        localize(summary) { body ->
            localize(bannerText) { b ->
                if (myGen == gen) show(body ?: summary, b, bannerColor)
            }
        }
    }

    private fun expiryBanner(i: MedicineInfo): Pair<String, Int> = when (i.expiryStatus) {
        ExpiryStatus.EXPIRED -> "EXPIRED (${i.expiryLabel}). Do not use this medicine." to Color.parseColor("#B91C1C")
        ExpiryStatus.SOON -> "Expires soon: ${i.expiryLabel}. Check with your pharmacist." to Color.parseColor("#B45309")
        ExpiryStatus.OK -> "Not expired. Expiry: ${i.expiryLabel}." to Color.parseColor("#047857")
        ExpiryStatus.UNKNOWN -> "Expiry date not found. Move closer and hold steady." to Color.parseColor("#475569")
    }

    // ---------- Translation ----------

    /** Translates English text into the chosen language (or returns it unchanged for English). */
    private fun localize(text: String?, cb: (String?) -> Unit) {
        when {
            text == null -> cb(null)
            targetLang == "en" -> cb(text)
            else -> translate(text, "en", targetLang) { out -> cb(out ?: text) }
        }
    }

    private fun translate(text: String, src: String, tgt: String, cb: (String?) -> Unit) {
        val key = "$src>$tgt"
        val tr = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
        }
        statusText.text = "Translating…"
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { _ ->
                tr.translate(text)
                    .addOnSuccessListener { out -> cb(out) }
                    .addOnFailureListener { cb(null) }
            }
            .addOnFailureListener {
                statusText.text = "Connect to the internet once to download this language"
                cb(null)
            }
    }

    // ---------- Speech ----------

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        if (!ttsReady) return
        val r = engine.setLanguage(Locale.forLanguageTag(targetLang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "Voice for this language is not installed on your phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleSpeech() {
        val engine = tts
        if (engine == null || !ttsReady) {
            Toast.makeText(this, "Voice is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (speaking) {
            engine.stop()
            resetListen()
        } else if (currentSpeech.isNotBlank()) {
            speaking = true
            btnListen.text = "Stop"
            engine.speak(currentSpeech, TextToSpeech.QUEUE_FLUSH, null, "omni")
        }
    }

    private fun resetListen() {
        runOnUiThread {
            speaking = false
            btnListen.text = "Listen"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        translators.values.forEach { it.close() }
        latin.close()
        deva.close()
        barcode.close()
        langId.close()
        analysisExecutor.shutdown()
    }
}
