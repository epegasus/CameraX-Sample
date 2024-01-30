package dev.epegasus.cameraxsample.ui.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import dev.epegasus.camera.CameraXManager
import dev.epegasus.camera.interfaces.CameraXActions
import dev.epegasus.cameraxsample.R
import dev.epegasus.cameraxsample.databinding.FragmentHomeBinding
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.popFrom
import dev.epegasus.cameraxsample.helper.extensions.FragmentExtensions.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

@ExperimentalCamera2Interop
class FragmentHome : BaseFragment<FragmentHomeBinding>(), CameraXActions {

    private val cameraXManager by lazy { CameraXManager(globalContext) }
    private val myFilesDirectory by lazy { globalContext.resources.getString(R.string.app_name) }
    private val picName get() = "CX_${System.currentTimeMillis()}"

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
        binding.ifvCaptureHome.setOnClickListener { onTakePhoto() }
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
        cameraXManager.let {
            it.init(binding.pvCameraHome, this, this)
            it.setRingView(binding.ifvFocusRingHome)
            it.startCameraPreview()
        }
    }

    private fun onTakePhoto() {
        cameraXManager.takePhoto(myFilesDirectory) { isSuccess, message, file ->
            CoroutineScope(Dispatchers.IO).launch {
                val fileName = "$picName.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveOnHigherDevices(fileName, file)
                } else {
                    saveOnLowerDevices(file)
                }
            }
        }
    }

    private fun saveOnLowerDevices(globalFile: File?) {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val file = File(path, "$myFilesDirectory/$picName.png")
        file.parentFile?.mkdirs()

        val inStream = FileInputStream(globalFile)
        val outStream = FileOutputStream(file)
        val inChannel: FileChannel = inStream.channel
        val outChannel: FileChannel = outStream.channel
        inChannel.transferTo(0, inChannel.size(), outChannel)
        inStream.close()
        outStream.close()

        MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null) { filePath, _: Uri? ->
            CoroutineScope(Dispatchers.Main).launch {
                showToast("Saved Successfully to $filePath")
            }
        }
    }

    private fun saveOnHigherDevices(fileName: String, globalFile: File?) {
        val resolver: ContentResolver = requireContext().contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + myFilesDirectory)
        }

        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        imageUri?.let { uri ->
            resolver.openOutputStream(uri)?.use {
                // If you want to notify the MediaStore about the new file

                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val file = File(path, "$myFilesDirectory/$fileName")

                val inStream = FileInputStream(globalFile)
                val outStream = FileOutputStream(file)
                val inChannel: FileChannel = inStream.channel
                val outChannel: FileChannel = outStream.channel
                inChannel.transferTo(0, inChannel.size(), outChannel)
                inStream.close()
                outStream.close()

                MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null) { filePath, _: Uri? ->
                    CoroutineScope(Dispatchers.Main).launch {
                        showToast("Saved Successfully to $filePath")
                    }
                }
            }
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