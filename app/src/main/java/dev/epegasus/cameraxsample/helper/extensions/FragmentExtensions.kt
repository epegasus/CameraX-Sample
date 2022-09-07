package dev.epegasus.cameraxsample.helper.extensions

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar

object FragmentExtensions {

    fun Fragment.showToast(message: String) {
        this.view?.let {
            Toast.makeText(it.context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun Fragment.showSnackBar(message: String) {
        this.view?.let {
            Snackbar.make(it.rootView, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    fun Fragment.showToast(stringId: Int) {
        this.view?.let {
            val message = it.resources.getString(stringId)
            Toast.makeText(it.context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * TODO
     *     Used launchWhenCreated, bcz of screen rotation
     *     Used launchWhenResumed, bcz of screen rotation
     * @param fragment_id : Current Fragment's Id (from Nav Graph)
     * @param action : Action / Id of other fragment
     */

    fun Fragment.navigateTo(fragment_id: Int, action: Int) {
        lifecycleScope.launchWhenCreated {
            if (isAdded && isCurrentDestination(fragment_id)) {
                findNavController().navigate(action)
            }
        }
    }

    fun Fragment.navigateTo(fragment_id: Int, action: NavDirections) {
        lifecycleScope.launchWhenCreated {
            if (isAdded && isCurrentDestination(fragment_id)) {
                findNavController().navigate(action)
            }
        }
    }

    fun Fragment.popFrom(fragment_id: Int) {
        lifecycleScope.launchWhenCreated {
            if (isAdded && isCurrentDestination(fragment_id)) {
                findNavController().popBackStack()
            }
        }
    }

    private fun Fragment.isCurrentDestination(fragment_id: Int): Boolean {
        return findNavController().currentDestination?.id == fragment_id
    }
}