package com.aitech.weartak_civ.debug

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

// Physical debug builds remain distinguishable; emulator previews show production branding.
class DevBadgeInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = addBadge(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }

    private fun addBadge(activity: Activity) {
        if (!showDevBadge(Build.HARDWARE)) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<TextView>(TAG) != null) return
        val density = activity.resources.displayMetrics.density
        val badge = TextView(activity).apply {
            tag = TAG
            text = "DEV"
            setTextColor(Color.RED)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            isClickable = false
            isFocusable = false
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (10 * density).toInt() }
        (decor as? FrameLayout)?.addView(badge, params)
        badge.elevation = 100f
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object { const val TAG = "dev_badge" }
}

internal fun showDevBadge(hardware: String): Boolean = hardware !in setOf("ranchu", "goldfish")