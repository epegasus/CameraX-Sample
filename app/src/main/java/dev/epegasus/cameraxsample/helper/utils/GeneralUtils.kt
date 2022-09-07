package dev.epegasus.cameraxsample.helper.utils

import android.content.Context
import android.os.Handler
import android.os.Looper

object GeneralUtils {

    const val TAG = "MyTag"

    fun withDelay(delay: Long = 300, block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed(block, delay)
    }

    fun getString(context: Context, stringId: Int): String {
        return context.resources.getString(stringId)
    }
}