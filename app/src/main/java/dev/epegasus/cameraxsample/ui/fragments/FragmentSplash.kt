package dev.epegasus.cameraxsample.ui.fragments

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dev.epegasus.cameraxsample.R
import dev.epegasus.cameraxsample.databinding.FragmentSplashBinding
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.navigateTo
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.showToast

class FragmentSplash : BaseFragment<FragmentSplashBinding>() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return getView(inflater, container, R.layout.fragment_splash)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        showToast("Flash : ${hasFlash()}")

        binding.btnContinueSplash.setOnClickListener { onContinueClick() }
    }

    /** Returns true if the device has flash. False otherwise */
    private fun hasFlash(): Boolean {
        return globalContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    private fun onContinueClick() {
        if (checkCameraHardware())
            navigateTo(R.id.fragmentSplash, R.id.action_fragmentSplash_to_fragmentHome)
        else
            showToast("No Camera Found")
    }

    private fun checkCameraHardware(): Boolean {
        val cameraManager = globalContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraManager.cameraIdList.isNotEmpty()
    }
}