package com.example.checkboxticker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

/** How the ticker looks at the screen: screen sharing, or the accessibility screenshot. */
interface Eyes {
    fun findBoxes(minScreenPx: Int, maxScreenPx: Int, done: (List<Rect>) -> Unit)

    fun findBoxesWithSketch(
        minScreenPx: Int,
        maxScreenPx: Int,
        skip: List<Rect>,
        done: (List<Rect>, BoxLook.Sketch?) -> Unit
    )

    fun findColour(target: Int, tolerance: Int, skipTopPct: Int, done: (Rect?) -> Unit)
}

/**
 * Looks at the screen with the accessibility screenshot (Android 11 and newer), so no screen
 * sharing is needed and nothing runs between pictures. Android allows about three of these a
 * second, so pictures are spaced out a little. Only used on Android 11 and newer.
 */
class ShotEyes(private val service: AccessibilityService) : Eyes {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var lastShot = 0L

    override fun findBoxes(minScreenPx: Int, maxScreenPx: Int, done: (List<Rect>) -> Unit) =
        shoot { frame ->
            val boxes = frame?.let {
                Frames.boxes(it.frame, minScreenPx / ScreenService.SCALE, maxScreenPx / ScreenService.SCALE)
            } ?: emptyList()
            main.post { done(boxes) }
        }

    override fun findBoxesWithSketch(
        minScreenPx: Int,
        maxScreenPx: Int,
        skip: List<Rect>,
        done: (List<Rect>, BoxLook.Sketch?) -> Unit
    ) = shoot { shot ->
        var boxes: List<Rect> = emptyList()
        var sketch: BoxLook.Sketch? = null
        if (shot != null) {
            try {
                boxes = Frames.boxes(shot.frame, minScreenPx / ScreenService.SCALE, maxScreenPx / ScreenService.SCALE)
                sketch = Frames.sketch(shot.frame, skip, shot.screenW, shot.screenH)
            } catch (t: Throwable) {
                Log.e(ScreenService.TAG, "screen scan failed", t)
            }
        }
        main.post { done(boxes, sketch) }
    }

    override fun findColour(target: Int, tolerance: Int, skipTopPct: Int, done: (Rect?) -> Unit) =
        shoot { shot ->
            val box = shot?.let {
                try {
                    Frames.colour(it.frame, target, tolerance, skipTopPct)
                } catch (t: Throwable) {
                    null
                }
            }
            main.post { done(box) }
        }

    private class Shot(val frame: Frame, val screenW: Int, val screenH: Int)

    /** Takes a screenshot (waiting out Android's limit) and hands it over on the worker thread. */
    private fun shoot(then: (Shot?) -> Unit) {
        val wait = (lastShot + MIN_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        main.postDelayed({ take(then, retry = true) }, wait)
    }

    private fun take(then: (Shot?) -> Unit, retry: Boolean) {
        lastShot = SystemClock.uptimeMillis()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = try {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        bmp?.let { toShot(it) }
                    } catch (t: Throwable) {
                        Log.e(ScreenService.TAG, "screenshot unreadable", t)
                        null
                    } finally {
                        buffer.close()
                    }
                    then(shot)
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retry) {
                        main.postDelayed({ take(then, retry = false) }, MIN_GAP_MS)
                    } else {
                        Log.w(ScreenService.TAG, "screenshot failed: $errorCode")
                        worker.execute { then(null) }
                    }
                }
            })
    }

    /** Shrinks the screenshot to the size the box finder is tuned for. */
    private fun toShot(full: Bitmap): Shot {
        val w = full.width / ScreenService.SCALE
        val h = full.height / ScreenService.SCALE
        val small = Bitmap.createScaledBitmap(full, w, h, true)
        val rgb = IntArray(w * h)
        small.getPixels(rgb, 0, w, 0, 0, w, h)
        for (i in rgb.indices) rgb[i] = rgb[i] and 0xffffff
        val shot = Shot(Frame(rgb, w, h), full.width, full.height)
        if (small != full) small.recycle()
        full.recycle()
        return shot
    }

    companion object {
        private const val MIN_GAP_MS = 350L
    }
}
