/*
 * Copyright (C) 2026 MistOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.wallpapereffects.service

import android.app.Service
import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.android.axion.wallpapereffects.canvas.CanvasStyle
import com.android.axion.wallpapereffects.canvas.CanvasStyleRenderer
import com.android.axion.wallpapereffects.canvas.OutlineThickness
import com.android.axion.wallpapereffects.util.DepthMaskUtils
import com.android.axion.wallpapereffects.util.PortraitSegmenter
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

private const val TAG = "CanvasAodService"

const val SETTING_CANVAS_ENABLED           = "canvas_aod_enabled"
const val SETTING_CANVAS_STYLE             = "canvas_aod_style"
const val SETTING_CANVAS_ANIMATION_ENABLED = "canvas_aod_animation_enabled"
const val SETTING_CANVAS_ANIMATION_SPEED   = "canvas_aod_animation_speed"
const val SETTING_CANVAS_WEATHER_EFFECTS   = "canvas_aod_weather_effects"
const val SETTING_CANVAS_WEATHER_INTENSITY = "canvas_aod_weather_intensity"
const val SETTING_CANVAS_THICKNESS         = "canvas_aod_outline_thickness"
const val SETTING_CANVAS_COLOR_MODE        = "canvas_aod_color_mode"
const val SETTING_CANVAS_CUSTOM_COLOR      = "canvas_aod_custom_color"
const val SETTING_CANVAS_CHARGING_ANIM     = "canvas_aod_charging_animation"
const val SETTING_CANVAS_NOTIF_PULSE       = "canvas_aod_notification_pulse"

const val SETTING_CANVAS_CACHE_PATH        = "canvas_aod_cache_path"
const val SETTING_CANVAS_WALLPAPER_HASH    = "canvas_aod_wallpaper_hash"

private const val CACHE_DIR  = "canvas_aod"
private const val CACHE_FILE = "canvas_%d_%s.png"

class CanvasAodService : Service() {

    companion object {
        const val ACTION_CANVAS_AOD_REGENERATE =
            "com.android.axion.wallpapereffects.CANVAS_AOD_REGENERATE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var processingThread: Thread? = null
    private var lastScreenW = 0
    private var lastScreenH = 0

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) != 0) {
            Log.d(TAG, "Wallpaper colors changed (which=$which) — scheduling regeneration")
            scheduleProcess()
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            Log.d(TAG, "Canvas AOD setting changed: $uri")
            scheduleProcess()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        val dm = resources.displayMetrics
        lastScreenW = dm.widthPixels
        lastScreenH = dm.heightPixels

        WallpaperManager.getInstance(this).addOnColorsChangedListener(colorsListener, handler)

        val cr = contentResolver
        listOf(
            SETTING_CANVAS_ENABLED,
            SETTING_CANVAS_STYLE,
            SETTING_CANVAS_THICKNESS,
            SETTING_CANVAS_COLOR_MODE,
            SETTING_CANVAS_CUSTOM_COLOR,
        ).forEach { key ->
            cr.registerContentObserver(
                Settings.Secure.getUriFor(key), false, settingsObserver,
            )
        }

        scheduleProcess()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        if (dm.widthPixels != lastScreenW || dm.heightPixels != lastScreenH) {
            Log.d(TAG, "Screen dimensions changed — rescheduling")
            lastScreenW = dm.widthPixels
            lastScreenH = dm.heightPixels
            scheduleProcess()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_CANVAS_AOD_REGENERATE) {
            scheduleProcess(forceRegenerate = true)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
        contentResolver.unregisterContentObserver(settingsObserver)
        processingThread?.interrupt()
        super.onDestroy()
    }

    private fun scheduleProcess(forceRegenerate: Boolean = false) {
        processingThread?.interrupt()
        processingThread = Thread {
            try {
                processCanvas(forceRegenerate)
            } catch (e: InterruptedException) {
                Log.d(TAG, "Canvas processing interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Canvas processing failed", e)
                clearCanvasSettings()
            }
        }.also { it.start() }
    }

    private fun processCanvas(forceRegenerate: Boolean) {
        val cr = contentResolver
        val enabled = Settings.Secure.getIntForUser(cr, SETTING_CANVAS_ENABLED, 0, android.os.UserHandle.USER_CURRENT)
        if (enabled != 1) {
            Log.d(TAG, "Canvas AOD disabled — clearing")
            clearCanvasSettings()
            return
        }

        val styleId    = Settings.Secure.getIntForUser(cr, SETTING_CANVAS_STYLE, 0, android.os.UserHandle.USER_CURRENT)
        val thickness  = Settings.Secure.getIntForUser(cr, SETTING_CANVAS_THICKNESS, OutlineThickness.MEDIUM, android.os.UserHandle.USER_CURRENT)
        val colorMode  = Settings.Secure.getIntForUser(cr, SETTING_CANVAS_COLOR_MODE, 1, android.os.UserHandle.USER_CURRENT)
        val customColor = Settings.Secure.getIntForUser(cr, SETTING_CANVAS_CUSTOM_COLOR, 0xFFFFFFFF.toInt(), android.os.UserHandle.USER_CURRENT)
        val style = CanvasStyle.fromId(styleId)

        val wm = WallpaperManager.getInstance(this)
        val wallpaper = loadWallpaperBitmap(wm) ?: run {
            Log.w(TAG, "No wallpaper bitmap available")
            clearCanvasSettings()
            return
        }

        val wallpaperHash = hashBitmap(wallpaper)
        val cacheFile = cacheFileFor(styleId, wallpaperHash, thickness, colorMode, customColor)

        val existingPath = Settings.Secure.getStringForUser(cr, SETTING_CANVAS_CACHE_PATH, android.os.UserHandle.USER_CURRENT)
        val existingHash = Settings.Secure.getStringForUser(cr, SETTING_CANVAS_WALLPAPER_HASH, android.os.UserHandle.USER_CURRENT)
        if (!forceRegenerate && cacheFile.exists() && cacheFile.length() > 0
            && existingPath == cacheFile.absolutePath
            && existingHash == wallpaperHash
        ) {
            Log.d(TAG, "Canvas cache is up-to-date: ${cacheFile.name}")
            if (!wallpaper.isRecycled) wallpaper.recycle()
            return
        }

        Log.d(TAG, "Generating canvas: style=$style thickness=$thickness colorMode=$colorMode")

        val wms = getSystemService(WindowManager::class.java)
        val maxBounds = wms?.maximumWindowMetrics?.bounds
        val dm = resources.displayMetrics
        val dstW = maxBounds?.width() ?: dm.widthPixels
        val dstH = maxBounds?.height() ?: dm.heightPixels
        val (cropped, _) = centerCropToDisplay(wallpaper, dstW, dstH)
        if (cropped !== wallpaper && !wallpaper.isRecycled) wallpaper.recycle()

        val segmenter = PortraitSegmenter(this)
        segmenter.init()
        val foreground: Bitmap?
        try {
            foreground = segmenter.segment(cropped)
        } finally {
            segmenter.release()
            if (!cropped.isRecycled) cropped.recycle()
        }

        if (foreground == null) {
            Log.w(TAG, "Segmentation returned null — no subject detected")
            clearCanvasSettings()
            return
        }

        val density = resources.displayMetrics.density
        val canvas = CanvasStyleRenderer.render(
            foreground = foreground,
            style      = style,
            thickness  = thickness,
            colorMode  = colorMode,
            customColor = customColor,
            density    = density,
            context    = this,
        )
        if (!foreground.isRecycled) foreground.recycle()

        if (canvas == null) {
            Log.w(TAG, "Canvas render returned null for style=$style")
            clearCanvasSettings()
            return
        }

        saveBitmapToCache(canvas, cacheFile)
        if (!canvas.isRecycled) canvas.recycle()

        val uriStr = "content://${CanvasFileProvider.AUTHORITY}/${cacheFile.name}"
        Settings.Secure.putStringForUser(cr, SETTING_CANVAS_CACHE_PATH, uriStr, android.os.UserHandle.USER_CURRENT)
        Settings.Secure.putStringForUser(cr, SETTING_CANVAS_WALLPAPER_HASH, wallpaperHash, android.os.UserHandle.USER_CURRENT)
        Log.d(TAG, "Canvas published: ${cacheFile.name}")
    }

    private fun loadWallpaperBitmap(wm: WallpaperManager): Bitmap? {
        try {
            wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { pfd ->
                val bmp = android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                if (bmp != null) return bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lock wallpaper load failed", e)
        }
        try {
            val drawable = wm.drawable
            if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "System wallpaper load failed", e)
        }
        try {
            val deCtx = createDeviceProtectedStorageContext()
            val file = File(deCtx.filesDir, "wallpaper.jpg")
            if (file.exists()) {
                return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DE wallpaper load failed", e)
        }
        return null
    }

    private fun centerCropToDisplay(bitmap: Bitmap, dstW: Int, dstH: Int): Pair<Bitmap, android.graphics.Rect> {
        val srcW = bitmap.width; val srcH = bitmap.height
        val fullRect = android.graphics.Rect(0, 0, srcW, srcH)
        if (dstW <= 0 || dstH <= 0) return Pair(bitmap, fullRect)

        val srcAR = srcW.toFloat() / srcH
        val dstAR = dstW.toFloat() / dstH

        val cropRect = if (srcAR > dstAR) {
            val cropW = (srcH * dstAR).roundToInt().coerceAtMost(srcW)
            val left = (srcW - cropW) / 2
            android.graphics.Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstAR).roundToInt().coerceAtMost(srcH)
            val top = (srcH - cropH) / 2
            android.graphics.Rect(0, top, srcW, top + cropH)
        }

        if (cropRect.width() >= srcW - 2 && cropRect.height() >= srcH - 2) return Pair(bitmap, fullRect)

        return try {
            val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            Pair(cropped, cropRect)
        } catch (e: Exception) {
            Log.w(TAG, "Center-crop failed", e)
            Pair(bitmap, fullRect)
        }
    }

    private fun hashBitmap(bitmap: Bitmap): String {
        return try {
            val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            val md = MessageDigest.getInstance("MD5")
            md.update(buffer.array())
            md.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    private fun cacheFileFor(
        styleId: Int, wallpaperHash: String,
        thickness: Int, colorMode: Int, customColor: Int,
    ): File {
        val paramHash = "%d_%d_%d_%08x".format(styleId, thickness, colorMode, customColor).hashCode()
        val name = "canvas_%d_%s_%d.png".format(styleId, wallpaperHash, paramHash)
        val dir = File(cacheDir, CACHE_DIR).also {
            it.mkdirs()
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
        cacheDir?.setExecutable(true, false)
        cacheDir?.parentFile?.setExecutable(true, false)
        
        dir.listFiles()?.filter { it.name.startsWith("canvas_${styleId}_") && it.name != name }
            ?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun saveBitmapToCache(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.setReadable(true, false)
            Log.d(TAG, "Canvas saved to ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save canvas to cache", e)
        }
    }

    private fun clearCanvasSettings() {
        try {
            val cr = contentResolver
            Settings.Secure.putStringForUser(cr, SETTING_CANVAS_CACHE_PATH, null, android.os.UserHandle.USER_CURRENT)
            Settings.Secure.putStringForUser(cr, SETTING_CANVAS_WALLPAPER_HASH, null, android.os.UserHandle.USER_CURRENT)
        } catch (_: Exception) {}
    }
}
