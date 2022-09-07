package dev.epegasus.cameraxsample.ui.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import dev.epegasus.cameraxsample.R
import dev.epegasus.cameraxsample.databinding.FragmentHomeBinding
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.popFrom
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.showToast
import dev.epegasus.cameraxsample.helper.interfaces.CameraXActions
import dev.epegasus.cameraxsample.helper.managers.CameraXManager

@ExperimentalCamera2Interop
class FragmentHome : BaseFragment<FragmentHomeBinding>(), CameraXActions {

    private val cameraXManager by lazy { CameraXManager(globalContext) }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE).toTypedArray()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return getView(inflater, container, R.layout.fragment_home)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkMultiplePermissions()

        // Set up the listeners for take photo and video capture buttons
        binding.ifvCaptureHome.setOnClickListener { cameraXManager.takePhoto() }
        binding.ifvRotateHome.setOnClickListener { cameraXManager.onCameraRotateFacingClick() }
        binding.pvCameraHome.setOnTouchListener { _, event -> cameraXManager.onPreviewSurfaceListener(event) }
    }

    private fun checkMultiplePermissions() {
        // Request camera permissions
        if (allPermissionsGranted()) letsStart()
        else permissionResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(globalContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val permissionResultLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        it.values.forEach { single ->
            if (!single) {
                showToast("Permission Required")
                return@forEach
            }
            letsStart()
        }
    }

    private fun letsStart() {
        setCameraConfigs()
    }

    private fun setCameraConfigs() {
        cameraXManager.let{
            it.init(binding.pvCameraHome, this, this)
            it.setRingView(binding.ifvFocusRingHome)
            it.startCameraPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraXManager.shutdown()
    }

    /* ------------------------------------------------------ Interfaces ------------------------------------------------------ */

    override fun cameraNotFound() {
        showToast("No Camera Found")
        popFrom(R.id.fragmentHome)
    }

    override fun rotateCallback(canRotate: Boolean) {
        binding.ifvRotateHome.isEnabled = canRotate
    }

    override fun onRotateClick() {
        // Animate Rotate Icon, if you want
    }

    override fun flashCallback(hasFlash: Boolean) {
        //binding.ifvFlash.isEnabled = hasFlash
    }
}