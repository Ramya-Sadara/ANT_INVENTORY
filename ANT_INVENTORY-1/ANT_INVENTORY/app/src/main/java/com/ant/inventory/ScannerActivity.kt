package com.ant.inventory

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
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

class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BARCODE = "barcode_value"

        // The same value must be read this many frames in a row before it is accepted.
        private const val CONFIRM_READS = 2

        // Starting zoom (0 = none, 1 = max). A little zoom lets you hold the phone further
        // away, where the camera can focus properly on a dense barcode.
        private const val START_ZOOM = 0.2f

        // A real label looks like:  QCEXR6900286672CC600      120
        // = a long first word (order + part), spaces, then the quantity at the end.
        // Any barcode on the screen that looks like this is accepted automatically,
        // wherever it is in the picture. Anything else must be accepted with the button.
        private val LABEL_PATTERN = Regex("^\\S{11,}[\\s\\u00A0]+\\d+(\\.\\d+)?$")
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var btnUseAnyway: Button
    private lateinit var btnTorch: Button
    private lateinit var seekZoom: SeekBar
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var torchOn = false

    // Created once and reused for every camera frame. All common 1D barcode types are read
    // so the label barcode is never missed because of its type.
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E
                )
                .build()
        )
    }

    private var scanned = false
    private var lastValue: String? = null
    private var sameCount = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        previewView  = findViewById(R.id.previewView)
        tvHint       = findViewById(R.id.tvScanHint)
        btnUseAnyway = findViewById(R.id.btnUseAnyway)
        btnTorch     = findViewById(R.id.btnTorch)
        seekZoom     = findViewById(R.id.seekZoom)
        findViewById<ImageButton>(R.id.btnCancelScan).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        // Lets the user accept a barcode that did not match the label pattern.
        btnUseAnyway.setOnClickListener {
            val text = btnUseAnyway.tag as? String ?: return@setOnClickListener
            scanned = true
            setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, text))
            finish()
        }
        // Torch on/off.
        btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            btnTorch.text = if (torchOn) "Light ON" else "Light OFF"
        }
        // Zoom slider.
        seekZoom.progress = (START_ZOOM * 100).toInt()
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Tap anywhere on the picture to focus there.
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
            true
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
                val cam = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                camera = cam
                cam.cameraControl.setLinearZoom(START_ZOOM)
                if (!cam.cameraInfo.hasFlashUnit()) btnTorch.visibility = View.GONE
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
                val seen = barcodes.filter { it.rawValue?.isNotBlank() == true }

                // 1) Any barcode that looks like the label barcode is taken, wherever it is.
                val value = seen
                    .mapNotNull { it.rawValue }
                    .firstOrNull { LABEL_PATTERN.matches(it.trim()) }

                if (value == null) {
                    // 2) Otherwise show the barcode nearest the middle and let the user decide.
                    val other = seen
                        .minByOrNull { distanceFromCenter(it.boundingBox, width, height) }
                        ?.rawValue
                    if (other != null) {
                        tvHint.text = "Not a label barcode: $other\nMove to the long label barcode, or tap the button"
                        btnUseAnyway.tag = other.trim()
                        btnUseAnyway.text = "USE ANYWAY: ${other.trim()}"
                        btnUseAnyway.visibility = View.VISIBLE
                    } else {
                        tvHint.text = "No barcode detected.\nTap the screen to focus, use Light / Zoom if needed."
                        btnUseAnyway.visibility = View.GONE
                    }
                    lastValue = null
                    sameCount = 0
                    return@addOnSuccessListener
                }

                tvHint.text = "Reading… hold steady"
                btnUseAnyway.visibility = View.GONE
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

    private fun distanceFromCenter(box: Rect?, width: Int, height: Int): Float {
        if (box == null) return Float.MAX_VALUE
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
