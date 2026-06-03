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

package com.android.axion.wallpapereffects.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAG = "CanvasStyleRenderer"

enum class CanvasStyle(val id: Int, val displayName: String) {
    PENCIL_SKETCH(0, "Pencil Sketch"),
    COMIC_OUTLINE(1, "Comic Outline"),
    ANIME_OUTLINE(2, "Anime Outline"),
    CYBERPUNK_NEON(3, "Cyberpunk Neon"),
    LOW_POLY(4, "Low Poly"),
    BLUEPRINT(5, "Blueprint Style");

    companion object {
        fun fromId(id: Int): CanvasStyle = values().firstOrNull { it.id == id } ?: PENCIL_SKETCH
    }
}

object OutlineThickness {
    const val THIN   = 0
    const val MEDIUM = 1
    const val THICK  = 2

    fun strokeWidth(thickness: Int, density: Float): Float = when (thickness) {
        THIN  -> 1.5f * density
        THICK -> 4.5f * density
        else  -> 2.5f * density
    }
}

object CanvasStyleRenderer {

    fun render(
        foreground: Bitmap,
        style: CanvasStyle,
        thickness: Int,
        colorMode: Int,
        customColor: Int,
        density: Float = 1f,
        context: Context? = null,
    ): Bitmap? {
        if (foreground.width <= 0 || foreground.height <= 0) return null

        val strokePx = OutlineThickness.strokeWidth(thickness, density)
        val outlineColor = resolveOutlineColor(style, colorMode, customColor, context)

        return try {
            val processor: StyleProcessor = when (style) {
                CanvasStyle.PENCIL_SKETCH  -> PencilSketchProcessor
                CanvasStyle.COMIC_OUTLINE  -> ComicOutlineProcessor
                CanvasStyle.ANIME_OUTLINE  -> AnimeOutlineProcessor
                CanvasStyle.CYBERPUNK_NEON -> CyberpunkNeonProcessor
                CanvasStyle.LOW_POLY       -> LowPolyProcessor
                CanvasStyle.BLUEPRINT      -> BlueprintProcessor
            }
            processor.process(foreground, strokePx, outlineColor)
        } catch (e: Exception) {
            Log.e(TAG, "Style render failed for $style", e)
            null
        }
    }

    private fun resolveOutlineColor(style: CanvasStyle, colorMode: Int, customColor: Int, context: Context?): Int {
        return when (colorMode) {
            0    -> resolveMonetAccent(context)
            2    -> customColor
            else -> Color.WHITE
        }.let { color ->
            when {
                style == CanvasStyle.CYBERPUNK_NEON && colorMode != 2 -> Color.CYAN
                style == CanvasStyle.BLUEPRINT      && colorMode != 2 -> Color.WHITE
                else -> color
            }
        }
    }

    private fun resolveMonetAccent(context: Context?): Int {
        if (context == null) return Color.WHITE
        return try {
            context.resources.getColor(android.R.color.system_accent1_200, context.theme)
        } catch (e: Exception) {
            Log.w(TAG, "Monet accent unavailable, falling back to white", e)
            Color.WHITE
        }
    }
}

internal interface StyleProcessor {
    fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap
}

internal object EdgeUtils {

    fun extractLuminance(bmp: Bitmap, alphaThreshold: Int = 30): IntArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            if (a > alphaThreshold) {
                val r = Color.red(pixels[i])
                val g = Color.green(pixels[i])
                val b = Color.blue(pixels[i])
                lum[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            } else {
                lum[i] = 0
            }
        }
        return lum
    }

    fun sobelMagnitude(lum: IntArray, w: Int, h: Int): FloatArray {
        val mag = FloatArray(w * h)
        var maxMag = 1f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = lum[(y - 1) * w + (x - 1)]; val tc = lum[(y - 1) * w + x]; val tr = lum[(y - 1) * w + (x + 1)]
                val ml = lum[y * w + (x - 1)];                                         val mr = lum[y * w + (x + 1)]
                val bl = lum[(y + 1) * w + (x - 1)]; val bc = lum[(y + 1) * w + x]; val br = lum[(y + 1) * w + (x + 1)]
                val gx = (-tl - 2 * ml - bl + tr + 2 * mr + br).toFloat()
                val gy = (-tl - 2 * tc - tr + bl + 2 * bc + br).toFloat()
                val m = sqrt(gx * gx + gy * gy)
                mag[y * w + x] = m
                if (m > maxMag) maxMag = m
            }
        }
        for (i in mag.indices) mag[i] /= maxMag
        return mag
    }

    fun foregroundMask(bmp: Bitmap, alphaThreshold: Int = 30): BooleanArray {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return BooleanArray(pixels.size) { Color.alpha(pixels[it]) > alphaThreshold }
    }

    fun outputBitmap(src: Bitmap): Bitmap =
        Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
}

internal object PencilSketchProcessor : StyleProcessor {

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val result = EdgeUtils.outputBitmap(foreground)
        val r = Color.red(outlineColor)
        val g = Color.green(outlineColor)
        val b = Color.blue(outlineColor)

        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (!fgMask[i]) { pixels[i] = 0; continue }
            val edge = mag[i]
            val noise = (Random.nextFloat() * 0.08f) - 0.04f
            val alpha = ((edge + noise).coerceIn(0f, 1f) * 220f).roundToInt()
            val finalAlpha = if (edge < 0.15f) (alpha * 0.4f).toInt() else alpha
            pixels[i] = Color.argb(finalAlpha, r, g, b)
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        if (strokePx >= 3f) {
            return dilate(result, 1)
        }
        return result
    }

    private fun dilate(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var maxA = Color.alpha(pixels[y * w + x])
                var bestColor = pixels[y * w + x]
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val a = Color.alpha(pixels[ny * w + nx])
                            if (a > maxA) { maxA = a; bestColor = pixels[ny * w + nx] }
                        }
                    }
                }
                out[y * w + x] = bestColor
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (!src.isRecycled) src.recycle()
        return result
    }
}

internal object ComicOutlineProcessor : StyleProcessor {

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val threshold = 0.28f
        val r = Color.red(outlineColor)
        val g = Color.green(outlineColor)
        val b = Color.blue(outlineColor)

        val result = EdgeUtils.outputBitmap(foreground)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (!fgMask[i]) { pixels[i] = 0; continue }
            val alpha = if (mag[i] >= threshold) 255 else 0
            pixels[i] = Color.argb(alpha, r, g, b)
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        val dilRadius = (strokePx / 2f).roundToInt().coerceIn(1, 4)
        return dilateComic(result, dilRadius, outlineColor)
    }

    private fun dilateComic(src: Bitmap, radius: Int, color: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var hasEdge = Color.alpha(pixels[y * w + x]) > 128
                if (!hasEdge) {
                    outer@ for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h && Color.alpha(pixels[ny * w + nx]) > 128) {
                                hasEdge = true; break@outer
                            }
                        }
                    }
                }
                out[y * w + x] = if (hasEdge) Color.argb(255, r, g, b) else 0
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (!src.isRecycled) src.recycle()
        return result
    }
}

internal object AnimeOutlineProcessor : StyleProcessor {

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val threshold = 0.20f
        val r = Color.red(outlineColor)
        val g = Color.green(outlineColor)
        val b = Color.blue(outlineColor)

        val result = EdgeUtils.outputBitmap(foreground)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (!fgMask[i]) { pixels[i] = 0; continue }
            val m = mag[i]
            val alpha = when {
                m >= threshold + 0.15f -> 245
                m >= threshold -> ((m - threshold) / 0.15f * 245f).roundToInt()
                else -> 0
            }
            pixels[i] = Color.argb(alpha, r, g, b)
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        return dilateAnime(result, (strokePx / 2.5f).roundToInt().coerceIn(1, 3), outlineColor)
    }

    private fun dilateAnime(src: Bitmap, radius: Int, color: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var maxA = Color.alpha(pixels[y * w + x])
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            maxA = max(maxA, Color.alpha(pixels[ny * w + nx]))
                        }
                    }
                }
                val smoothAlpha = min(maxA, 245)
                out[y * w + x] = if (smoothAlpha > 0) Color.argb(smoothAlpha, r, g, b) else 0
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (!src.isRecycled) src.recycle()
        return result
    }
}

internal object CyberpunkNeonProcessor : StyleProcessor {

    private val NEON_COLORS = intArrayOf(
        Color.CYAN,
        Color.argb(255, 255, 0, 255),
        Color.argb(255, 0, 255, 180),
    )

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val result = EdgeUtils.outputBitmap(foreground)
        val pixels = IntArray(w * h)
        val threshold = 0.18f

        val primaryColor = if (outlineColor == Color.WHITE) Color.CYAN else outlineColor

        for (i in pixels.indices) {
            if (!fgMask[i]) { pixels[i] = 0; continue }
            val m = mag[i]
            if (m < threshold) { pixels[i] = 0; continue }

            val alpha = ((m - threshold) / (1f - threshold) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        return addNeonGlow(result, primaryColor, strokePx)
    }

    private fun addNeonGlow(src: Bitmap, neonColor: Int, strokePx: Float): Bitmap {
        val w = src.width; val h = src.height
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val glowRadius = (strokePx * 2f).roundToInt().coerceIn(2, 8)
        val glowPixels = IntArray(w * h)

        val nr = Color.red(neonColor)
        val ng = Color.green(neonColor)
        val nb = Color.blue(neonColor)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val srcA = Color.alpha(srcPixels[y * w + x])
                if (srcA > 0) { glowPixels[y * w + x] = srcPixels[y * w + x]; continue }

                var maxContrib = 0f
                for (dy in -glowRadius..glowRadius) {
                    for (dx in -glowRadius..glowRadius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val a = Color.alpha(srcPixels[ny * w + nx])
                            if (a > 0) {
                                val dist = sqrt((dx * dx + dy * dy).toFloat())
                                val contrib = (a / 255f) * (1f - dist / glowRadius).coerceAtLeast(0f)
                                if (contrib > maxContrib) maxContrib = contrib
                            }
                        }
                    }
                }
                val glowAlpha = (maxContrib * 80f).roundToInt().coerceIn(0, 80)
                glowPixels[y * w + x] = Color.argb(glowAlpha, nr, ng, nb)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(glowPixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(result)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        if (!src.isRecycled) src.recycle()
        return result
    }
}

internal object LowPolyProcessor : StyleProcessor {

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val points = mutableListOf<FloatArray>()
        val step = max(w, h) / 30
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (fgMask[y * w + x]) {
                    val jitter = step / 3
                    val px = (x + Random.nextInt(-jitter, jitter + 1)).coerceIn(0, w - 1)
                    val py = (y + Random.nextInt(-jitter, jitter + 1)).coerceIn(0, h - 1)
                    val edgeBias = mag[py * w + px]
                    if (edgeBias > 0.1f || Random.nextFloat() < 0.3f) {
                        points.add(floatArrayOf(px.toFloat(), py.toFloat()))
                    }
                }
            }
        }

        val origPixels = IntArray(w * h)
        foreground.getPixels(origPixels, 0, w, 0, 0, w, h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outlineColor
            style = Paint.Style.STROKE
            strokeWidth = strokePx * 0.4f
            alpha = 180
        }

        if (points.size >= 3) {
            for (i in points.indices) {
                val a = points[i]
                val nearest = points
                    .filterIndexed { idx, _ -> idx != i }
                    .sortedBy { pt -> dist(a, pt) }
                    .take(2)

                if (nearest.size < 2) continue
                val b = nearest[0]; val c = nearest[1]

                val ai = a[1].toInt().coerceIn(0, h - 1) * w + a[0].toInt().coerceIn(0, w - 1)
                val bi = b[1].toInt().coerceIn(0, h - 1) * w + b[0].toInt().coerceIn(0, w - 1)
                val ci = c[1].toInt().coerceIn(0, h - 1) * w + c[0].toInt().coerceIn(0, w - 1)
                if (!fgMask[ai] && !fgMask[bi] && !fgMask[ci]) continue

                val cx = ((a[0] + b[0] + c[0]) / 3f).toInt().coerceIn(0, w - 1)
                val cy = ((a[1] + b[1] + c[1]) / 3f).toInt().coerceIn(0, h - 1)
                val sampleColor = if (fgMask[cy * w + cx]) origPixels[cy * w + cx] else continue

                val sr = Color.red(sampleColor)
                val sg = Color.green(sampleColor)
                val sb = Color.blue(sampleColor)
                val gray = (0.299f * sr + 0.587f * sg + 0.114f * sb).toInt()
                val blended = Color.argb(
                    180,
                    (gray * 0.3f + Color.red(outlineColor) * 0.7f).toInt().coerceIn(0, 255),
                    (gray * 0.3f + Color.green(outlineColor) * 0.7f).toInt().coerceIn(0, 255),
                    (gray * 0.3f + Color.blue(outlineColor) * 0.7f).toInt().coerceIn(0, 255)
                )

                paint.color = blended
                paint.style = Paint.Style.FILL

                val path = android.graphics.Path()
                path.moveTo(a[0], a[1])
                path.lineTo(b[0], b[1])
                path.lineTo(c[0], c[1])
                path.close()

                canvas.drawPath(path, paint)
                canvas.drawPath(path, edgePaint)
            }
        }

        return result
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }
}

internal object BlueprintProcessor : StyleProcessor {

    private val BLUEPRINT_BG  = Color.argb(220, 15,  45, 100)
    private val BLUEPRINT_LINE = Color.WHITE
    private val BLUEPRINT_GRID = Color.argb(40, 150, 190, 255)

    override fun process(foreground: Bitmap, strokePx: Float, outlineColor: Int): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val lum = EdgeUtils.extractLuminance(foreground)
        val mag = EdgeUtils.sobelMagnitude(lum, w, h)
        val fgMask = EdgeUtils.foregroundMask(foreground)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawColor(BLUEPRINT_BG)

        val gridPaint = Paint().apply {
            color = BLUEPRINT_GRID
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridSpacing = (20f * (w / 400f)).coerceIn(12f, 32f)
        var gx = 0f; while (gx < w) { canvas.drawLine(gx, 0f, gx, h.toFloat(), gridPaint); gx += gridSpacing }
        var gy = 0f; while (gy < h) { canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint); gy += gridSpacing }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLUEPRINT_LINE
            strokeWidth = strokePx * 0.8f
            style = Paint.Style.STROKE
        }

        val threshold = 0.20f
        val outlinePixels = IntArray(w * h)
        for (i in outlinePixels.indices) {
            if (!fgMask[i]) { outlinePixels[i] = 0; continue }
            val m = mag[i]
            val alpha = if (m >= threshold) ((m - threshold) / (1f - threshold) * 255f).roundToInt().coerceIn(0, 255) else 0
            outlinePixels[i] = if (alpha > 0) Color.argb(alpha, 255, 255, 255) else 0
        }
        val outlineBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outlineBmp.setPixels(outlinePixels, 0, w, 0, 0, w, h)

        val dilated = dilateBlueprint(outlineBmp, (strokePx / 2f).roundToInt().coerceIn(1, 3))
        canvas.drawBitmap(dilated, 0f, 0f, null)
        if (!dilated.isRecycled) dilated.recycle()

        return result
    }

    private fun dilateBlueprint(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var maxA = Color.alpha(pixels[y * w + x])
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            maxA = max(maxA, Color.alpha(pixels[ny * w + nx]))
                        }
                    }
                }
                out[y * w + x] = if (maxA > 0) Color.argb(maxA, 255, 255, 255) else 0
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (!src.isRecycled) src.recycle()
        return result
    }
}

