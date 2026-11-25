package com.omniscan.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.google.mlkit.vision.text.Text
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
    private lateinit var selection: SelectionView
    private lateinit var statusText: TextView
    private lateinit var banner: TextView
    private lateinit var scroll: ScrollView
    private lateinit var resultText: TextView
    private lateinit var btnLang: MaterialButton
    private lateinit var btnSize: MaterialButton
    private lateinit var btnScript: MaterialButton
    private lateinit var btnListen: MaterialButton
    private lateinit var btnCheck: MaterialButton

    private val prefs by lazy { getSharedPreferences("omniscan", Context.MODE_PRIVATE) }
    private var targetLang = "en"
    private var sizeIdx = 1
    private var devanagari = false

    // Check = read ONLY the selected box, once, when the user taps the button
    @Volatile private var captureRequested = false
    @Volatile private var busy = false
    @Volatile private var frameFrac = floatArrayOf(0.1f, 0.25f, 0.9f, 0.75f)
    @Volatile private var viewAspect = 0.75f
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
        if (granted) startCamera() else statusText.text = "Camera permission is needed. Tap here to try again."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        selection = findViewById(R.id.selection)
        statusText = findViewById(R.id.statusText)
        banner = findViewById(R.id.banner)
        scroll = findViewById(R.id.scroll)
        resultText = findViewById(R.id.resultText)
        btnLang = findViewById(R.id.btnLang)
        btnSize = findViewById(R.id.btnSize)
        btnScript = findViewById(R.id.btnScript)
        btnListen = findViewById(R.id.btnListen)
        btnCheck = findViewById(R.id.btnCheck)

        analysisExecutor = Executors.newSingleThreadExecutor()
        targetLang = prefs.getString("lang", "en") ?: "en"
        sizeIdx = prefs.getInt("size", 1).coerceIn(0, sizes.size - 1)
        devanagari = prefs.getBoolean("deva", false)
        applyUi()

        selection.onChanged = { updateFrame() }
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFrame() }

        btnLang.setOnClickListener { chooseLanguage() }
        btnSize.setOnClickListener {
            sizeIdx = (sizeIdx + 1) % sizes.size
            prefs.edit().putInt("size", sizeIdx).apply()
            applyUi()
        }
        btnScript.setOnClickListener {
            devanagari = !devanagari
            prefs.edit().putBoolean("deva", devanagari).apply()
            applyUi()
        }
        btnCheck.setOnClickListener {
            if (busy) return@setOnClickListener
            updateFrame()
            statusText.text = "Checking the selected area…"
            captureRequested = true
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
        statusText.text = "Done. Move the box and tap Check again."
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
                statusText.text = "Drag the box over the text, then tap Check"
            } catch (e: Exception) {
                statusText.text = "Could not start the camera"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Keeps the selected box (as fractions of the preview) in sync. */
    private fun updateFrame() {
        val pw = preview.width
        val ph = preview.height
        if (pw == 0 || ph == 0) return
        frameFrac = selection.fractions()
        viewAspect = pw.toFloat() / ph
    }

    /** Crops the camera frame to exactly the box the user selected. */
    private fun cropToSelection(proxy: ImageProxy): Bitmap {
        val src = proxy.toBitmap()
        val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val bw = rotated.width
        val bh = rotated.height
        val va = viewAspect
        // Preview uses FILL_CENTER, so the visible area is a centred crop with the view's aspect ratio
        var vw = bw
        var vh = bh
        if (bw.toFloat() / bh > va) vw = (bh * va).toInt() else vh = (bw / va).toInt()
        val vx = (bw - vw) / 2
        val vy = (bh - vh) / 2
        val f = frameFrac
        val x = (vx + f[0] * vw).toInt().coerceIn(0, bw - 2)
        val y = (vy + f[1] * vh).toInt().coerceIn(0, bh - 2)
        val w = ((f[2] - f[0]) * vw).toInt().coerceIn(1, bw - x)
        val h = ((f[3] - f[1]) * vh).toInt().coerceIn(1, bh - y)
        return Bitmap.createBitmap(rotated, x, y, w, h)
    }

    private fun analyze(proxy: ImageProxy) {
        if (!captureRequested || busy) {
            proxy.close()
            return
        }
        captureRequested = false
        busy = true
        val bmp = try {
            cropToSelection(proxy)
        } catch (e: Exception) {
            null
        }
        proxy.close()
        if (bmp == null) {
            busy = false
            runOnUiThread { statusText.text = "Could not capture. Try again." }
            return
        }
        val input = InputImage.fromBitmap(bmp, 0)
        val recognizer = if (devanagari) deva else latin
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val lines = cleanLines(result)
                val allText = lines.joinToString("\n") { it.first }
                if (allText.length < 2) {
                    busy = false
                    statusText.text = "No text found in the box. Move it over the text and tap Check."
                } else if (MedicineParser.looksLikeMedicine(allText)) {
                    val nameGuess = lines
                        .filter { l -> l.first.count { ch -> ch.isLetter() } >= 4 }
                        .maxByOrNull { l -> l.second }?.first
                    barcode.process(input).addOnCompleteListener { t ->
                        val hasCode = t.isSuccessful && !t.result.isNullOrEmpty()
                        handleMedicine(allText, hasCode, nameGuess)
                        busy = false
                    }
                } else {
                    handleSign(allText)
                    busy = false
                }
            }
            .addOnFailureListener {
                busy = false
                statusText.text = "Could not read the text. Try again."
            }
    }

    /** Text lines with their pixel height; drops garbage lines (mostly symbols/noise). */
    private fun cleanLines(result: Text): List<Pair<String, Int>> =
        result.textBlocks.flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val t = line.text.trim()
                val good = t.count { ch -> ch.isLetterOrDigit() }
                if (t.length >= 2 && good >= t.length * 0.6) Pair(t, box.height()) else null
            }

    // ---------- Text in the box: translate + hazard alerts ----------

    private fun handleSign(text: String) {
        val myGen = ++gen
        statusText.text = "Reading…"
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
        val hazard = HazardDetector.check(forHazard)
        localize(hazard) { msg ->
            if (myGen == gen) show(body, msg, Color.parseColor("#B91C1C"))
        }
    }

    // ---------- Medicine ----------

    private fun handleMedicine(text: String, hasCode: Boolean, nameGuess: String?) {
        val info = MedicineParser.parse(text, hasCode, nameGuess)
        val summary = MedicineParser.summary(info)
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
        ExpiryStatus.UNKNOWN -> "Expiry date not found in the box. Include the EXP line." to Color.parseColor("#475569")
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
