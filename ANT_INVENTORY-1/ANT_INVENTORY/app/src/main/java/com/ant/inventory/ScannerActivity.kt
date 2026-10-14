package com.ant.inventory

import android.annotation.SuppressLint
import android.content.Intent
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

        // Starting zoom (0 = none, 1 = max).
        private const val START_ZOOM = 0.2f
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var btnTorch: Button
    private lateinit var seekZoom: SeekBar
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var torchOn = false
    private var scanned = false

    // All common barcode types are read.
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
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417
                )
                .build()
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        previewView = findViewById(R.id.previewView)
        tvHint      = findViewById(R.id.tvScanHint)
        btnTorch    = findViewById(R.id.btnTorch)
        seekZoom    = findViewById(R.id.seekZoom)
        findViewById<ImageButton>(R.id.btnCancelScan).setOnClickListener {
            setResult(RESULT_CANCELED)
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
            // Full-HD analysis picture: the camera default is much smaller and a dense
            // barcode cannot be read from it.
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
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (scanned) return@addOnSuccessListener
                // As soon as a barcode is detected it is accepted. If the picture holds
                // several, the longest one is taken (the label barcode, not a short one).
                val value = barcodes
                    .mapNotNull { it.rawValue }
                    .filter { it.isNotBlank() }
                    .maxByOrNull { it.length }

                if (value == null) {
                    tvHint.text = "No barcode detected.\nTap the screen to focus, use Light / Zoom if needed."
                    return@addOnSuccessListener
                }
                scanned = true
                setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, value))
                finish()
            }
            .addOnFailureListener { }
            .addOnCompleteListener { imageProxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }
}
