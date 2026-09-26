package com.example.checkboxticker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
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
    /**
     * The last picture looked at (0xRRGGBB pixels, width, height), at 1/[ScreenService.SCALE]
     * of the screen - to cut samples and ask the box model without taking another screenshot.
     */
    fun lastPixels(): Triple<IntArray, Int, Int>? = null

    fun findBoxes(minScreenPx: Int, maxScreenPx: Int, done: (List<Rect>) -> Unit)

    fun findBoxesWithSketch(
        minScreenPx: Int,
        maxScreenPx: Int,
        skip: List<Rect>,
        done: (List<Rect>, BoxLook.Sketch?) -> Unit
    )

    fun findColour(target: Int, tolerance: Int, skipTopPct: Int, done: (Rect?) -> Unit)

    /** Every patch of one colour, with its size in pixels (at the finder's scale). */
    fun findColourPatches(
        target: Int, tolerance: Int, skipTopPct: Int, done: (List<Pair<Rect, Int>>) -> Unit
    )
}

/**
 * Looks at the screen with the accessibility screenshot (Android 11 and newer), so no screen
 * sharing is needed and nothing runs between pictures. Android allows about three of these a
 * second, so pictures are spaced out a little. Only used on Android 11 and newer.
 */
class ShotEyes(
    private val service: AccessibilityService,
    /** Called when the phone refuses a screenshot (not just "too soon"). */
    private val onRefused: () -> Unit = {}
) : Eyes {

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

    override fun findColourPatches(
        target: Int, tolerance: Int, skipTopPct: Int, done: (List<Pair<Rect, Int>>) -> Unit
    ) = shoot { shot ->
        val patches = shot?.let {
            try {
                Frames.colourPatches(it.frame, target, tolerance, skipTopPct)
            } catch (t: Throwable) {
                null
            }
        } ?: emptyList()
        main.post { done(patches) }
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
                        hw?.let { ScreenPictures.normalise(service, it, ScreenService.SCALE) }
                            ?.let { toShot(it) }
                    } catch (t: Throwable) {
                        Log.e(ScreenService.TAG, "screenshot unreadable", t)
                        null
                    } finally {
                        buffer.close()
                    }
                    if (shot == null) main.post(onRefused)
                    then(shot)
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retry) {
                        main.postDelayed({ take(then, retry = false) }, MIN_GAP_MS)
                    } else {
                        Log.w(ScreenService.TAG, "screenshot failed: $errorCode")
                        // A secure page blocks sharing too, so only ask for it otherwise.
                        if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            main.post(onRefused)
                        }
                        worker.execute { then(null) }
                    }
                }
            })
    }

    /** Turns the (already shrunk, standard-colour) screenshot into a frame for the finder. */
    @Volatile
    private var last: Frame? = null

    override fun lastPixels(): Triple<IntArray, Int, Int>? = last?.let { Triple(it.rgb, it.w, it.h) }

    private fun toShot(small: Bitmap): Shot {
        val w = small.width
        val h = small.height
        val rgb = IntArray(w * h)
        small.getPixels(rgb, 0, w, 0, 0, w, h)
        for (i in rgb.indices) rgb[i] = rgb[i] and 0xffffff
        small.recycle()
        val frame = Frame(rgb, w, h)
        last = frame
        return Shot(frame, w * ScreenService.SCALE, h * ScreenService.SCALE)
    }

    companion object {
        private const val MIN_GAP_MS = 340L    // Android allows one every 333 ms
    }
}

/**
 * Makes an accessibility screenshot match what taps and colours expect. Some phones hand it
 * over in a wide colour space (Display P3), which shifts colours such as the pop-up's purple,
 * or at a different resolution than the screen's tap coordinates, which shifts every tap.
 */
object ScreenPictures {
    /**
     * The screenshot in standard sRGB colours, at the screen's own size divided by [divide]
     * (so a pixel at x,y is the tap point x*divide, y*divide).
     */
    @JvmStatic
    fun normalise(context: Context, screenshot: Bitmap, divide: Int): Bitmap? {
        val soft = screenshot.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (w <= 0 || h <= 0) {
            w = soft.width
            h = soft.height
        }
        // Rotated since the metrics were read: follow the picture.
        if ((soft.width > soft.height) != (w > h)) {
            val t = w
            w = h
            h = t
        }
        val out = Bitmap.createBitmap(
            (w / divide).coerceAtLeast(1), (h / divide).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB)
        )
        // Drawing converts the colours into the sRGB target.
        Canvas(out).drawBitmap(soft, null, Rect(0, 0, out.width, out.height),
            Paint(Paint.FILTER_BITMAP_FLAG))
        soft.recycle()
        return out
    }
}
