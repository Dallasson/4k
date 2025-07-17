package com.thumb.test

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
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
import kotlin.concurrent.fixedRateTimer

class RecordingActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var recordingBtn: MaterialButton

    private lateinit var cameraDevice: CameraDevice
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var captureSession: CameraCaptureSession

    private lateinit var outputFile: File

    private var isRecording = false
    private var droppedFrames = 0
    private var previousFrameCount = 0
    private var frameCount = 0
    private var bitrate = 0L
    private var fpsTimer: Timer? = null
    private var timer: Timer? = null
    private var recordingSeconds = 0
    private var recordingStartTime: Long = 0L

    private lateinit var fpsText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var droppedText: TextView

    private val firebaseStorage: FirebaseStorage = Firebase.storage

    companion object {
        private const val CAMERA_ID = "1"
        private const val REQUEST_CAMERA_PERMISSION = 1001
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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
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
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                frameCount++
            }
        }

        recordingBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraManager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                if (textureView.isAvailable) {
                    startPreview()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                Log.e("CameraError", "Camera open error: $error")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(surfaceTexture)

        val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(previewSurface)

        cameraDevice.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Preview", "Preview configuration failed")
            }
        }, null)
    }

    private fun startRecording() {
        mediaRecorder = MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            outputFile = File(getExternalFilesDir(null), "recorded_${System.currentTimeMillis()}.mp4")
            setOutputFile(outputFile.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoFrameRate(30)
            setVideoSize(1920, 1080)
            prepare()
        }

        val recorderSurface = mediaRecorder.surface
        val previewSurface = Surface(textureView.surfaceTexture)

        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
        }

        cameraDevice.createCaptureSession(listOf(previewSurface, recorderSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureSession.setRepeatingRequest(requestBuilder.build(), null, null)
                mediaRecorder.start()
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                startStatsMonitor()
                startRecordingTimer()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Recording", "Failed to configure recording session")
            }
        }, null)
    }

    private fun stopRecording() {
        try {
            captureSession.stopRepeating()
            captureSession.abortCaptures()
            mediaRecorder.stop()
            mediaRecorder.release()
            isRecording = false
            stopStatsMonitor()
            stopRecordingTimer()
            recordingBtn.text = "00:00"
            startPreview()
            uploadToFirebase()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRecordingTimer() {
        recordingSeconds = 0
        timer = fixedRateTimer("recordingTimer", true, 0, 1000) {
            runOnUiThread {
                val minutes = recordingSeconds / 60
                val seconds = recordingSeconds % 60
                recordingBtn.text = String.format("%02d:%02d", minutes, seconds)
                recordingSeconds++
            }
        }
    }

    private fun stopRecordingTimer() {
        timer?.cancel()
        recordingSeconds = 0
    }

    private fun uploadToFirebase() {
        val uri = Uri.fromFile(outputFile)
        val fileRef = firebaseStorage.reference.child("videos/${uri.lastPathSegment}")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Uploading...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        fileRef.putFile(uri)
            .addOnProgressListener {
                val progress = (100.0 * it.bytesTransferred / it.totalByteCount).toInt()
                progressDialog.progress = progress
            }
            .addOnSuccessListener {
                progressDialog.dismiss()
                Toast.makeText(this, "Upload complete!", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Toast.makeText(this, "Upload failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun startStatsMonitor() {
        frameCount = 0
        droppedFrames = 0
        previousFrameCount = 0

        if (recordingStartTime == 0L) {
            recordingStartTime = System.currentTimeMillis()
        }

        fpsTimer = fixedRateTimer("fpsMonitor", true, 0L, 1000L) {
            runOnUiThread {
                val fps = frameCount
                fpsText.text = "FPS: $fps"

                val dropped = (30 - fps).coerceAtLeast(0)
                droppedText.text = "Dropped: $dropped"

                val durationSeconds = ((System.currentTimeMillis() - recordingStartTime) / 1000L).coerceAtLeast(1L)
                val fileSizeBits = outputFile.length() * 8
                val bitrateEstimate = if (durationSeconds > 0) (fileSizeBits / durationSeconds) / 1000 else 0
                bitrateText.text = "Bitrate: ${bitrateEstimate}kbps"

                frameCount = 0
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun stopStatsMonitor() {
        fpsTimer?.cancel()
        fpsText.text = "FPS: 0"
        bitrateText.text = "Bitrate: 0kbps"
        droppedText.text = "Dropped: 0"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            openCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        fpsTimer?.cancel()
        timer?.cancel()
    }
}
