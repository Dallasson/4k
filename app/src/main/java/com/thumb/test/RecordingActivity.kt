package com.thumb.test

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import kotlin.concurrent.fixedRateTimer

class RecordingActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var recordingBtn: MaterialButton

    private var cameraDevice: CameraDevice? = null
    private var mediaRecorder: MediaRecorder? = null
    private var captureSession: CameraCaptureSession? = null

    private lateinit var outputFile: File
    private var isRecording = false

    private var frameCount = 0
    private var fpsTimer: Timer? = null
    private var timer: Timer? = null
    private var recordingSeconds = 0

    private lateinit var fpsText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var droppedText: TextView

    private val firebaseStorage: FirebaseStorage = Firebase.storage

    private var cameraId: String? = null
    private var isHighSpeedCapable = false
    private var selectedPreviewFpsRange: Range<Int> = Range(30, 30)
    private var selectedRecordingFpsRange: Range<Int> = Range(30, 30)

    private lateinit var cameraHandler: Handler
    private lateinit var cameraThread: HandlerThread

    companion object {
        private const val DEFAULT_CAMERA_ID = "1"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 12000000
        private const val TARGET_FPS_HIGH = 60
        private const val TARGET_FPS_NORMAL = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_recording)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        textureView = findViewById(R.id.textureView)
        recordingBtn = findViewById(R.id.recordingBtn)
        fpsText = findViewById(R.id.fpsText)
        bitrateText = findViewById(R.id.bitrateText)
        droppedText = findViewById(R.id.droppedText)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { frameCount++ }
        }

        recordingBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!textureView.isAvailable) return
        try {
            cameraId = DEFAULT_CAMERA_ID
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            isHighSpeedCapable = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val availablePreviewFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            selectedPreviewFpsRange = availablePreviewFpsRanges?.firstOrNull { it.upper >= TARGET_FPS_HIGH } ?: Range(TARGET_FPS_NORMAL, TARGET_FPS_NORMAL)
            if (isHighSpeedCapable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val highSpeedRangesForSize = map?.getHighSpeedVideoFpsRangesFor(Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                selectedRecordingFpsRange = highSpeedRangesForSize?.firstOrNull { it.upper >= TARGET_FPS_HIGH } ?: Range(TARGET_FPS_NORMAL, TARGET_FPS_NORMAL)
            } else {
                selectedRecordingFpsRange = availablePreviewFpsRanges?.firstOrNull { it.upper >= TARGET_FPS_NORMAL } ?: Range(TARGET_FPS_NORMAL, TARGET_FPS_NORMAL)
            }
            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Toast.makeText(this@RecordingActivity, "Camera error: $error", Toast.LENGTH_LONG).show()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPreview() {
        val cameraDevice = this.cameraDevice ?: return
        try {
            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            val previewSurface = Surface(surfaceTexture)
            val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedPreviewFpsRange)
            }
            cameraDevice.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@RecordingActivity, "Preview configuration failed", Toast.LENGTH_SHORT).show()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("Preview", "Error in startPreview: ${e.message}")
        }
    }

    private fun startRecording() {
        val cameraDevice = this.cameraDevice ?: return
        mediaRecorder = MediaRecorder()
        setupMediaRecorder()

        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        val previewSurface = Surface(surfaceTexture)
        val recorderSurface = mediaRecorder!!.surface

        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRecordingFpsRange)
        }

        cameraDevice.createCaptureSession(listOf(previewSurface, recorderSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureSession?.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                mediaRecorder?.start()
                isRecording = true
                startMetrics()
                recordingBtn.text = "Stop"
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(this@RecordingActivity, "Recording configuration failed", Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)
    }

    private fun setupMediaRecorder() {
        outputFile = File(externalCacheDir, "recording_${System.currentTimeMillis()}.mp4")
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(VIDEO_BITRATE)
            setVideoFrameRate(selectedRecordingFpsRange.upper)
            setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun stopRecording() {
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        recordingBtn.text = "Record"
        stopMetrics()
        openCamera()
        uploadToFirebase(outputFile)
    }

    private fun uploadToFirebase(file: File) {
        val uri = Uri.fromFile(file)
        val ref = firebaseStorage.reference.child("recordings/${file.name}")
        val dialog = ProgressDialog(this).apply {
            setTitle("Uploading")
            setMessage("Please wait...")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            show()
        }
        ref.putFile(uri).addOnProgressListener {
            val percent = (100.0 * it.bytesTransferred / it.totalByteCount).toInt()
            dialog.progress = percent
        }.addOnSuccessListener {
            dialog.dismiss()
            Toast.makeText(this, "Upload complete", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            dialog.dismiss()
            Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMetrics() {
        frameCount = 0
        recordingSeconds = 0
        fpsTimer = fixedRateTimer("fpsTimer", initialDelay = 0, period = 1000) {
            runOnUiThread {
                fpsText.text = "FPS: $frameCount"
                bitrateText.text = "Bitrate: ${VIDEO_BITRATE / 1000} kbps"
                droppedText.text = "Dropped: 0"
                frameCount = 0
            }
        }
    }

    private fun stopMetrics() {
        fpsTimer?.cancel()
        fpsTimer = null
    }
}