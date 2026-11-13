package dev.wonderdesk.memories

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Pixel Camera opens its corner thumbnail only in Google Photos (it names that app directly), so with Google Photos
 * turned off the tap does nothing. This switch watches Pixel Camera for that one tap and opens the newest shot in
 * Memories instead. It reads nothing else and does nothing while Google Photos is still on.
 */
class CameraLink : AccessibilityService() {
    private var last = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || e.packageName !in CAMERAS) return
        if (photosEnabled()) return
        val node = e.source
        val id = node?.viewIdResourceName.orEmpty().lowercase()
        val desc = (e.contentDescription ?: node?.contentDescription ?: "").toString().lowercase()
        node?.recycle()
        val thumb = "thumbnail" in id || "filmstrip" in id || "preview" in id || "gallery" in id ||
            listOf("thumbnail", "latest", "view photo", "open photo", "view video", "photo preview", "gallery", "last photo", "most recent").any { it in desc }
        if (!thumb) return
        val now = System.currentTimeMillis(); if (now - last < 1200) return; last = now
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).setAction(ACTION_LATEST).putExtra("secure", locked)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun photosEnabled() = runCatching { packageManager.getApplicationInfo("com.google.android.apps.photos", 0).enabled }.getOrDefault(false)

    override fun onInterrupt() {}

    companion object {
        const val ACTION_LATEST = "dev.wonderdesk.memories.LATEST"
        val CAMERAS = setOf("com.google.android.GoogleCamera", "com.google.android.GoogleCameraEng")
    }
}
