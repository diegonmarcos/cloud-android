@file:Suppress("KotlinConstantConditions")

package com.diegonmarcos.mediacenter.core.util

import android.util.Log
import com.diegonmarcos.mediacenter.BuildConfig

fun printInfo(message: Any) {
    Log.i("GalleryInfo", message.toString())
}

fun printDebug(message: Any) {
    printDebug(message.toString())
}

fun printDebug(message: String) {
    if (BuildConfig.BUILD_TYPE != "release") {
        Log.d("GalleryInfo", message)
    }
}

fun printError(message: String) {
    Log.e("GalleryInfo", message)
}

fun printWarning(message: String) {
    Log.w("GalleryInfo", message)
}