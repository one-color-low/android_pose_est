package com.example.cameraxapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetectorOptions
typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    // 絶対実行される関数
    override fun onCreate(savedInstanceState: Bundle?) { // overrideしてるということは、継承元のAppCompatActivity()にもonCreateはあったということ。※それを明示するためにoverrideとして宣言している
        super.onCreate(savedInstanceState) //superはスーパークラス(一世代上の親クラス)の意味
        setContentView(R.layout.activity_main) // viewをセットする

        // カメラのパーミッションをリクエスト
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // 写真撮影ボタンのリスナーをセット
        camera_capture_button.setOnClickListener { takePhoto() }
        // 写真の保存ディレクトリをセット
        outputDirectory = getOutputDirectory()
        // カメラ実行機を用意(シングルスレッド？)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        // 修正可能なイメージキャプチャのユースケースに対するリファレンスを取得(?)
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        // タイムスタンプ付きの出力ファイルを作成
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        // 出力オプションを作成
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        // イメージキャプチャのリスナを用意
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            imageCapture = ImageCapture.Builder()
                .build() // Builds an immutable ImageCapture from the current state.

            /*val imageAnalyzer = ImageAnalysis.Builder() // startCameraした段階でanalyzerを用意
                .build()
                .also {// スコープ関数: 引数とする関数(cameraExecutor?)のスコープを変更するために使用
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }*/

            ImageAnalysis.Builder().build()
                ?.apply {
                    setAnalyzer(ContextCompat.getMainExecutor(context), FaceAnalyzer())
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                // 再結合の前にユースケース=設定を外す(初期化的な？)
                cameraProvider.unbindAll()

                // Bind use cases to camera
                // 解析対象はimageCapture(?)
                // カメラとユースケース=設定(これまでに作ったcameraSelectorとか)をくっつける
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc) // 設定をくっつけるのに失敗した場合
            }

        }, ContextCompat.getMainExecutor(this)) // 一個目のリスナが長いやつで、二つ目がContextCompat.xxxx
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object { // クラス内に作成されるシングルトン
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer { // ImageAnalysis.Analyzerから継承してクラスを作成, luminosity = 「明度」

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind(=巻き戻す) the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    override fun analyze(image: ImageProxy) {

        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        val pixels = data.map { it.toInt() and 0xFF }
        val luma = pixels.average()

        listener(luma)

        image.close()
    }
}

private class YourImageAnalyzer : ImageAnalysis.Analyzer { // 継承元はlumaと同じ

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind(=巻き戻す) the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    override fun analyze(imageProxy: ImageProxy) { // 引数はlumaのときと同じくImageProxy
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            // Pass image to an ML Kit Vision API
            // ...
        }
    }
}

private class FaceAnalyzer : ImageAnalysis.Analyzer {

    init {
        // 軽量モードなどの設定
        // Pose detection with streaming frames
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPerformanceMode(PoseDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        val poseDetector = PoseDetection.getClient(options) // poseDetectorの用意完了
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val image =
            imageProxy.image?.let { InputImage.fromMediaImage(it, imageProxy.imageInfo.rotationDegrees) }
        val result = image?.let {
            detector.process(it)
                .addOnSuccessListener { faces ->
                    // 検出したときの処理を書く
                }
                .addOnFailureListener { e ->
                    // 失敗したときの処理を書く
                }
        }
    }
}