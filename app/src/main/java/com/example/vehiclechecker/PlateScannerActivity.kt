package com.example.vehiclechecker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Live camera view that scans for UK number plates using ML Kit text recognition.
 * When a confident plate is found it finishes and returns it via RESULT_PLATE.
 */
class PlateScannerActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var finished = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission needed to scan plates", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plate_scanner)
        statusText = findViewById(R.id.tvScanStatus)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val previewView = findViewById<PreviewView>(R.id.previewView)
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analyzerExecutor, ::analyzeFrame)
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private val platePattern: Pattern = Pattern.compile(
        "(?i)\\b([A-HJ-PR-Y]{2}[0-9]{2}\\s?[A-HJ-PR-Y]{3}|[A-HJ-PR-Y][0-9]{1,3}\\s?[A-HJ-PR-Y]{3})\\b"
    )

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.replace("\n", " ")
                val matcher = platePattern.matcher(text)
                if (matcher.find()) {
                    val plate = matcher.group(1)?.replace(" ", "")?.uppercase() ?: ""
                    if (plate.length in 5..8 && !finished) {
                        finished = true
                        val result = Intent().putExtra(RESULT_PLATE, plate)
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    }
                }
            }
            .addOnCompleteListener { image.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        recognizer.close()
    }

    companion object {
        const val RESULT_PLATE = "scanned_plate"
    }
}
