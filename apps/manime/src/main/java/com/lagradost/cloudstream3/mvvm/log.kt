package com.lagradost.cloudstream3.mvvm

fun logError(throwable: Throwable) {
    android.util.Log.e("Cloudstream", "Error", throwable)
}

fun logError(msg: String, throwable: Throwable? = null) {
    android.util.Log.e("Cloudstream", msg, throwable)
}
