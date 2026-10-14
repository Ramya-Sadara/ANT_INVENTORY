package com.ant.inventory

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BARCODE = "barcode_value"

        // The same value must be read this many frames in a row before it is accepted.
        private const val CONFIRM_READS = 2

        // Only a barcode whose centre is inside this middle area of the picture is accepted
        // (fractions of the picture width / height).
        private const val CENTER_X_TOLERANCE = 0.25f
        private const val CENTER_Y_TOLERANCE = 0.10f

        // A real label looks like:  QCEXR6900286672CC600      120
        // = a long first word (order + part), spaces, then the quantity at the end.
        // Anything else (e.g. a 12-digit shipping barcode) is ignored.
        private val LABEL_PATTERN = Regex("^\\S{16,}[\\s\\u00A0]+\\d+(\\.\\d+)?$")
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var cameraExecutor: ExecutorService

    // Created once and reused for every camera frame. Only the formats a long label
    // barcode can use are read; retail codes (EAN/UPC/ITF) and QR codes are ignored.
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93
                )
                .build()
        )
    }

    private var scanned = false
    private var lastValue: String? = null
    private var sameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        previewView = findViewById(R.id.previewView)
        tvHint      = findViewById(R.id.tvScanHint)
        findViewById<ImageButton>(R.id.btnCancelScan).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // Higher resolution so a dense label barcode is decoded correctly.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                runOnUiThread { tvHint.text = "Camera error: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy) {
        if (scanned) { imageProxy.close(); return }
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        // Picture size as the user sees it (after rotation).
        val upright = rotation == 90 || rotation == 270
        val width  = if (upright) imageProxy.height else imageProxy.width
        val height = if (upright) imageProxy.width else imageProxy.height

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (scanned) return@addOnSuccessListener
                // Take the barcode nearest the middle of the frame; ignore the rest.
                val inFrame = barcodes
                    .filter { it.rawValue?.isNotBlank() == true }
                    .mapNotNull { b -> b.boundingBox?.let { box -> b to box } }
                    .filter { (_, box) -> isInsideFrame(box, width, height) }
                    .minByOrNull { (_, box) -> distanceFromCenter(box, width, height) }
                    ?.first?.rawValue

                // Reject anything that does not look like the label barcode.
                val value = inFrame?.takeIf { LABEL_PATTERN.matches(it.trim()) }
                if (inFrame != null && value == null) {
                    tvHint.text = "Ignored: $inFrame\nAim at the long label barcode"
                } else if (inFrame == null) {
                    tvHint.text = "Put ONLY the label barcode inside the frame and hold steady"
                }

                if (value == null) {
                    lastValue = null
                    sameCount = 0
                    return@addOnSuccessListener
                }
                if (value == lastValue) sameCount++ else { lastValue = value; sameCount = 1 }

                if (sameCount >= CONFIRM_READS) {
                    scanned = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, value))
                    finish()
                }
            }
            .addOnFailureListener { }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun isInsideFrame(box: Rect, width: Int, height: Int): Boolean =
        abs(box.exactCenterX() - width / 2f) <= width * CENTER_X_TOLERANCE &&
        abs(box.exactCenterY() - height / 2f) <= height * CENTER_Y_TOLERANCE

    private fun distanceFromCenter(box: Rect, width: Int, height: Int): Float {
        val dx = box.exactCenterX() - width / 2f
        val dy = box.exactCenterY() - height / 2f
        return dx * dx + dy * dy
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }
}
