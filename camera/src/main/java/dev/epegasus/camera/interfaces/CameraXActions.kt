package dev.epegasus.camera.interfaces

interface CameraXActions {
    fun cameraNotFound()
    fun rotateCallback(canRotate: Boolean)
    fun flashCallback(hasFlash: Boolean)
    fun onRotateClick()

}