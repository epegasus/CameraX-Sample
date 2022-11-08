package dev.epegasus.camera.managers

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

class CameraXHardware(private val context: Context, private val cameraProvider: ProcessCameraProvider?) {

    fun isAnyCameraAvailable(): Boolean {
        return hasBackCamera() || hasFrontCamera()
    }

    /** Returns true if the device has flash. False otherwise */
    fun hasFlash(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    /** Returns true if the device has an available back camera. False otherwise */
    fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

}