package dev.epegasus.camera

import android.animation.Animator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaScannerConnection
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import dev.epegasus.camera.enums.CameraAspectRatio
import dev.epegasus.camera.interfaces.CameraXActions
import dev.epegasus.camera.managers.CameraXHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

private const val TAG = "MyTag"

/**
 * @param cameraFacing
 *          CameraSelector.DEFAULT_FRONT_CAMERA
 *          CameraSelector.DEFAULT_BACK_CAMERA
 * @param flashMode
 *          ImageCapture.FLASH_MODE_OFF
 *          ImageCapture.FLASH_MODE_ON
 */

@ExperimentalCamera2Interop
class CameraXManager(private val context: Context, private var cameraFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA, private var flashMode: Int = ImageCapture.FLASH_MODE_OFF) {

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var cameraXActions: CameraXActions
    private lateinit var lifecycleOwner: LifecycleOwner

    private var ringView: ImageFilterView? = null
    private var cameraAspectRatio = CameraAspectRatio.ASPECT_RATIO_4_3

    private val cameraXHardware by lazy { CameraXHardware(context, cameraProvider) }

    // Objects
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var canRotate: Boolean = false

    private var cameraExecutor: ExecutorService? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null

    fun init(previewView: PreviewView, lifecycleOwner: LifecycleOwner, cameraXActions: CameraXActions) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        this.cameraXActions = cameraXActions
        cameraExecutor = Executors.newSingleThreadExecutor()
        registerTouchListener()
    }

    /* ----------------------------------------------------- Init Views ----------------------------------------------------- */
    /**
     *  ifvRingView: (optional)
     *      Add a view which will appear on user Click and animate as a focusing circle
     */
    fun setRingView(ringView: ImageFilterView) {
        this.ringView = ringView
    }

    fun setAspectRatio(cameraAspectRatio: CameraAspectRatio) {
        this.cameraAspectRatio = cameraAspectRatio
    }

    fun toggleFlashMode(flashMode: Int) {
        this.flashMode = flashMode
        imageCapture?.flashMode = flashMode
    }

    /**
     *  Zoom to Pinch
     */

    private fun registerTouchListener() {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Get the camera's current zoom ratio
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 0F

                // Get the pinch gesture's scaling factor
                val delta = detector.scaleFactor

                // Update the camera's zoom ratio. This is an asynchronous operation that returns
                // a ListenableFuture, allowing you to listen to when the operation completes.
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                // Return true, as the event was handled
                return true
            }
        })
    }

    fun startCameraPreview() {
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = cameraProviderFuture.get()
        cameraXActions.flashCallback(cameraXHardware.hasFlash())
        if (!cameraXHardware.isAnyCameraAvailable()) {
            cameraXActions.cameraNotFound()
            return
        }
        cameraProviderFuture.addListener({
            bindPreview()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     *      setFlashMode()
     *          1) FLASH_MODE_ON            // Start while capturing
     *          2) FLASH_MODE_OFF           // Don't Allow
     *          3) FLASH_MODE_AUTO          // Will Start in night time (if needed)
     */

    private fun bindPreview() {
        // Used to bind the lifecycle of cameras to the lifecycle owner
        // Preview
        val preview = Preview.Builder()
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Initial Image Capture
        if (cameraAspectRatio == CameraAspectRatio.FULL_SCREEN) {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()
            (previewView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = null
        } else {
            val ar = if (cameraAspectRatio == CameraAspectRatio.ASPECT_RATIO_4_3) {
                (previewView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = CameraAspectRatio.ASPECT_RATIO_4_3.toString()
                AspectRatio.RATIO_4_3
            } else {
                (previewView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = CameraAspectRatio.ASPECT_RATIO_9_16.toString()
                AspectRatio.RATIO_16_9
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .setTargetAspectRatio(ar)
                .build()
        }

        // Enable / Disable Camera Button's enable
        updateCameraSwitchButton()

        // Image Analyzer
        val imageAnalyzer = ImageAnalysis.Builder().build().also {
            it.setAnalyzer(cameraExecutor!!, LuminosityAnalyzer {
                //Log.d(TAG, "Average luminosity: $luma")
            })
        }

        try {
            // Unbind use cases before rebinding
            cameraProvider?.unbindAll()
            // Bind use cases to camera
            camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraFacing, preview, imageCapture, imageAnalyzer)
        } catch (ex: Exception) {
            Log.d(TAG, "Use case binding failed : $ex")
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        canRotate = try {
            cameraXHardware.hasBackCamera() && cameraXHardware.hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            false
        }
        cameraXActions.rotateCallback(canRotate)
    }

    /* --------------------------------------------------- Click Listeners --------------------------------------------------- */

    fun onCameraRotateFacingClick() {
        if (canRotate) {
            cameraFacing = if (CameraSelector.DEFAULT_FRONT_CAMERA == cameraFacing)
                selectExternalOrBestCamera()
            else
                CameraSelector.DEFAULT_FRONT_CAMERA
            rotateCameraFacing()
            cameraXActions.onRotateClick()
        }
    }

    private fun rotateCameraFacing() {
        cameraProvider?.let {
            bindPreview()
        } ?: kotlin.run {
            cameraProvider = ProcessCameraProvider.getInstance(context).get()
            rotateCameraFacing()
        }
    }

    private fun selectExternalOrBestCamera(): CameraSelector {
        cameraProvider?.let { provider ->
            val cameraInfoList = provider.availableCameraInfos.map {
                Camera2CameraInfo.from(it)
            }.sortedByDescending {
                // HARDWARE_LEVEL is Int type, with the order of:
                // LEGACY < LIMITED < FULL < LEVEL_3 < EXTERNAL
                it.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            }
            return when {
                cameraInfoList.isNotEmpty() -> {
                    CameraSelector.Builder().addCameraFilter {
                        it.filter { camInfo ->
                            // cameraInfoList[0] is either EXTERNAL or best built-in camera
                            val thisCamId = Camera2CameraInfo.from(camInfo).cameraId
                            thisCamId == cameraInfoList[0].cameraId
                        }
                    }.build()
                }

                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
        } ?: return CameraSelector.DEFAULT_FRONT_CAMERA
    }

    fun takePhoto(myFilesDirectory: String, callback: (isSuccess: Boolean, message: String, file: File) -> Unit) {
        // Get a stable reference of the modifiable image capture use case

        val folder = File(myFilesDirectory)
        if (!folder.exists()) {
            folder.mkdir()
        }

        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val picName = "PCM_${System.currentTimeMillis()}.png"

        /*val outputOptions: ImageCapture.OutputFileOptions = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, picName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$myFilesDirectory")
            }
            ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
        } else {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(path, "$myFilesDirectory/$picName.png")
            file.parentFile?.mkdirs()
            ImageCapture.OutputFileOptions.Builder(file).build()
        }*/
        // Dummy File
        val file = File(context.cacheDir, picName)
        file.createNewFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        // Create output options object which contains file + metadata

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onError(ex: ImageCaptureException) {
                Log.d(TAG, "Photo Capture: onError: $ex")

                callback.invoke(false, ex.message ?: "Failed to Save Image", file)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val msg = "Photo capture succeeded: Pictures/$myFilesDirectory"
                Log.d(TAG, "Photo Capture: onImageSaved: $msg")

                val asyncTask = CoroutineScope(Dispatchers.IO).async {
                    checkForExifRotation(file.toString())
                }

                CoroutineScope(Dispatchers.Main).launch {
                    val filePath = asyncTask.await()
                    //val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(output.savedUri?.toFile()?.extension)
                    MediaScannerConnection.scanFile(context, arrayOf(output.savedUri?.path), null) { path, _ ->
                        Log.d(TAG, "Image capture scanned into media store: $path")
                        callback.invoke(true, msg, File(filePath))
                    }
                }
            }
        })
    }

    private fun checkForExifRotation(filePath: String): String {
        val exif = ExifInterface(filePath)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val bitmap = BitmapFactory.decodeFile(filePath)
        val newBitmap = if (rotation != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else {
            bitmap
        }
        val fileOutputStream = FileOutputStream(filePath)
        newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream)
        fileOutputStream.close()
        return filePath
    }

    /* --------------------------------------------------- Touch Listeners --------------------------------------------------- */

    fun onPreviewSurfaceListener(event: MotionEvent): Boolean {
        scaleGestureDetector?.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (!this::previewView.isInitialized) {
                    return false
                }
                // Get the MeteringPointFactory from PreviewView
                val factory = previewView.meteringPointFactory

                // Create a MeteringPoint from the tap coordinates
                val point = factory.createPoint(event.x, event.y)

                // Create a MeteringAction from the MeteringPoint, you can configure it to specify the metering mode
                val action = FocusMeteringAction.Builder(point).build()

                // Trigger the focus and metering. The method returns a ListenableFuture since the operation
                // is asynchronous. You can use it get notified when the focus is successful or if it fails.
                Log.d(TAG, "onPreviewSurfaceListener: Focusing")
                animateFocusRing(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(action)
                return true
            }

            else -> return false
        }
    }

    private fun animateFocusRing(x: Float, y: Float) {
        ringView?.let {
            // Move the focus ring so that its center is at the tap location (x, y)
            val width: Int = it.width
            val height: Int = it.height
            it.x = x - width / 2
            it.y = y - height / 2

            // Show focus ring
            it.visibility = View.VISIBLE
            it.alpha = 1f

            // Animate the focus ring to disappear
            it.animate().setStartDelay(500).setDuration(600).alpha(0f).setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {

                }

                override fun onAnimationEnd(animator: Animator) {
                    it.visibility = View.INVISIBLE
                }

                override fun onAnimationCancel(animation: Animator) {

                }

                override fun onAnimationRepeat(animation: Animator) {

                }
            })
        }
    }

    fun shutdown() {
        cameraExecutor?.shutdown()
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
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
}