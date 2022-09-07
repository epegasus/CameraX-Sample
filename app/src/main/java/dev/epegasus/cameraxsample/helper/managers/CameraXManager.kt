package dev.epegasus.cameraxsample.helper.managers

import android.animation.Animator
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.epegasus.cameraxsample.helper.enums.CameraAspectRatio
import dev.epegasus.cameraxsample.helper.interfaces.CameraXActions
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

private const val TAG = "MyTag"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

@ExperimentalCamera2Interop
class CameraXManager(private val context: Context) {

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var cameraXActions: CameraXActions
    private lateinit var lifecycleOwner: LifecycleOwner

    private var ringView: ImageFilterView? = null
    private var cameraAspectRatio = CameraAspectRatio.FULL_SCREEN

    private val cameraXHardware by lazy { CameraXHardware(context, cameraProvider) }

    // Objects
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector? = null
    private var canRotate: Boolean = false

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector

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
            bindPreview(false)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     *      setFlashMode()
     *          1) FLASH_MODE_ON            // Start while capturing
     *          2) FLASH_MODE_OFF           // Don't Allow
     *          3) FLASH_MODE_AUTO          // Will Start in night time (if needed)
     */

    private fun bindPreview(isUpdate: Boolean) {
        // Used to bind the lifecycle of cameras to the lifecycle owner

        // Preview
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Initial Image Capture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_ON)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
        if (cameraAspectRatio == CameraAspectRatio.FULL_SCREEN) {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_ON)
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
                .setFlashMode(ImageCapture.FLASH_MODE_ON)
                .setTargetAspectRatio(ar)
                .build()
        }

        // Enable / Disable Camera Button's enable
        updateCameraSwitchButton()

        // Select front camera as a default
        if (!isUpdate)
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        // Image Analyzer
        val imageAnalyzer = ImageAnalysis.Builder().build().also {
            it.setAnalyzer(cameraExecutor, LuminosityAnalyzer {
                //Log.d(TAG, "Average luminosity: $luma")
            })
        }

        try {
            // Unbind use cases before rebinding
            cameraProvider?.unbindAll()
            // Bind use cases to camera
            camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector!!, preview, imageCapture, imageAnalyzer)
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
            cameraSelector = if (CameraSelector.DEFAULT_FRONT_CAMERA == cameraSelector)
                selectExternalOrBestCamera()
            else
                CameraSelector.DEFAULT_FRONT_CAMERA
            rotateCameraFacing()
        }
    }

    private fun rotateCameraFacing() {
        cameraProvider?.let {
            bindPreview(true)
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

    fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Sample")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onError(ex: ImageCaptureException) {
                Log.d(TAG, "Photo Capture: onError: $ex")
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val msg = "Photo capture succeeded: ${output.savedUri}"
                Log.d(TAG, "Photo Capture: onImageSaved: $msg")
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        })
    }

    /* --------------------------------------------------- Touch Listeners --------------------------------------------------- */

    fun onPreviewSurfaceListener(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
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
            it.animate().setStartDelay(500).setDuration(300).alpha(0f).setListener(object : Animator.AnimatorListener {
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
        cameraExecutor.shutdown()
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