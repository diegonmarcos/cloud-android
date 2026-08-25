package com.diegonmarcos.superapp.ui
import com.diegonmarcos.superapp.R

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/**
 * Small Snackbar facade. Replaces stray `Toast.makeText(...).show()` calls
 * with a Material-themed bottom banner anchored on the CoordinatorLayout
 * — so the message rides above the BottomNav rather than sitting on top
 * of nav gestures.
 *
 *   anchor.snack("Hello")                  // short toast-like message
 *   anchor.snack(R.string.foo, "OK") { … } // with an action button
 */
fun View.snack(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
    Snackbar.make(this, message, duration).show()
}

fun View.snack(@StringRes messageRes: Int, duration: Int = Snackbar.LENGTH_SHORT) {
    Snackbar.make(this, messageRes, duration).show()
}

fun View.snack(
    message: String,
    actionLabel: String,
    duration: Int = Snackbar.LENGTH_LONG,
    onClick: (View) -> Unit,
) {
    Snackbar.make(this, message, duration)
        .setAction(actionLabel, onClick)
        .show()
}
