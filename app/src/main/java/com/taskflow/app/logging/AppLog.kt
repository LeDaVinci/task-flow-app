package com.taskflow.app.logging

import android.util.Log

object AppLog {
    const val TAG = "ChaosQuest"

    fun i(scope: String, message: String) {
        Log.i(TAG, "[$scope] $message")
    }

    fun w(scope: String, message: String) {
        Log.w(TAG, "[$scope] $message")
    }

    fun w(scope: String, message: String, error: Throwable) {
        Log.w(TAG, "[$scope] $message", error)
    }
}
