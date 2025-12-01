package com.example.persona.core.util

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.core.base.BaseViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// Fragment
fun Fragment.observeErrorEvents(viewModel: BaseViewModel, view: View) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.errorEvents.collect { message ->
                Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                    .setAction("Dismiss") { }
                    .show()
            }
        }
    }
}

// Activity
fun AppCompatActivity.observeErrorEvents(viewModel: BaseViewModel, rootView: View) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.errorEvents.collect { message ->
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                    .setAction("OK") { }
                    .show()
            }
        }
    }
}