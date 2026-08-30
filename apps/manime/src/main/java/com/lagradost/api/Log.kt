package com.lagradost.api

object Log {
    fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    fun w(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?) { android.util.Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { android.util.Log.e(tag, msg, tr) }
    fun e(tag: String, tr: Throwable?) { android.util.Log.e(tag, "Exception", tr) }
    fun d(msg: String) { android.util.Log.d("Cloudstream", msg) }
    fun i(msg: String) { android.util.Log.i("Cloudstream", msg) }
    fun w(msg: String) { android.util.Log.w("Cloudstream", msg) }
    fun e(msg: String) { android.util.Log.e("Cloudstream", msg) }
}
