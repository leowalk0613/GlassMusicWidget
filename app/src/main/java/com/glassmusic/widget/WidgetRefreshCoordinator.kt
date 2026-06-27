package com.glassmusic.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** 合并、节流小部件刷新，避免通知栏/播放进度触发更新风暴 */
object WidgetRefreshCoordinator {

    private const val TAG = "WidgetRefresh"
    private const val DEBOUNCE_MS = 400L
    private const val MIN_INTERVAL_MS = 800L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPush: Runnable? = null
    private var lastPushAt = 0L

    fun requestPush(context: Context, immediate: Boolean = false) {
        val appContext = context.applicationContext
        pendingPush?.let { handler.removeCallbacks(it) }
        pendingPush = Runnable { executePush(appContext) }
        val delay = if (immediate) 0L else DEBOUNCE_MS
        handler.postDelayed(pendingPush!!, delay)
    }

    private fun executePush(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPushAt
        if (elapsed < MIN_INTERVAL_MS) {
            handler.postDelayed({ executePush(context) }, MIN_INTERVAL_MS - elapsed)
            return
        }
        pendingPush = null
        lastPushAt = now
        Log.e(AppBuildInfo.LOG_TAG, "push widgets ${AppBuildInfo.MARKER}")
        MusicWidgetProvider.pushWidgetsDirect(context)
    }

    fun cancel() {
        pendingPush?.let { handler.removeCallbacks(it) }
        pendingPush = null
    }
}
