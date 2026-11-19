package app.expressiveclock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import app.expressiveclock.MainActivity
import app.expressiveclock.R
import app.expressiveclock.data.ClockSettings
import app.expressiveclock.data.ClockStore
import app.expressiveclock.data.DigitalBackground
import app.expressiveclock.data.DigitalLayout
import app.expressiveclock.data.WidgetConfig
import app.expressiveclock.data.WidgetType
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

object DigitalClockWidgetRenderer {

    suspend fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        forceWeatherRefresh: Boolean = false,
        weatherOverride: String? = null,
    ) {
        val store = ClockStore(context)
        val config = store.widgetConfigNow(appWidgetId) ?: WidgetConfig(type = WidgetType.DIGITAL)
        if (config.type == WidgetType.ANALOG) {
            ClockWidgetRenderer.update(context, manager, appWidgetId)
            return
        }
        val settings = store.settingsNow()
        val options = manager.getAppWidgetOptions(appWidgetId)
        val weatherText = weatherOverride
            ?: WidgetWeatherHelper.getWeatherBadge(context, forceRefresh = forceWeatherRefresh)

        val views = try {
            build(context, config, settings, options, weatherText)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("DigitalClockWidget", "Rendering native clock fallback", error)
            RemoteViews(context.packageName, R.layout.widget_digital_initial).apply {
                setOnClickPendingIntent(
                    android.R.id.background,
                    openClockAppPendingIntent(context),
                )
            }
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun openClockAppPendingIntent(context: Context): PendingIntent {
        val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targetIntent = if (alarmIntent.resolveActivity(context.packageManager) != null) {
            alarmIntent
        } else {
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return PendingIntent.getActivity(
            context,
            100,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openWeatherAppPendingIntent(context: Context): PendingIntent {
        val pm = context.packageManager
        val knownWeatherPackages = listOf(
            "com.coloros.weather2",
            "com.oplus.weather",
            "com.oneplus.weather",
            "net.oneplus.weather",
            "com.coloros.weather",
            "com.google.android.apps.weather",
        )
        val launchIntent = knownWeatherPackages.firstNotNullOfOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_WEATHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val finalIntent = if (launchIntent.resolveActivity(pm) != null) {
            launchIntent
        } else {
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return PendingIntent.getActivity(
            context,
            101,
            finalIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openCalendarPendingIntent(context: Context): PendingIntent {
        val calendarIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALENDAR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            102,
            calendarIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun build(
        context: Context,
        config: WidgetConfig,
        settings: ClockSettings,
        options: Bundle,
        weatherText: String,
    ): RemoteViews {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt().coerceAtLeast(1)

        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)

        val portrait = one(context, config, settings, px(minW), px(maxH), weatherText)
        val landscape = one(context, config, settings, px(maxW), px(minH), weatherText)
        return RemoteViews(landscape, portrait)
    }

    private suspend fun one(
        context: Context,
        config: WidgetConfig,
        settings: ClockSettings,
        w: Int,
        h: Int,
        weatherText: String,
    ): RemoteViews {
        val colours = WidgetColours.resolve(context, config, settings)
        val profile = NativeWidgetFonts.resolve(context, config.digitalFont, config.digitalWeight)
        val layout = profile.layout(config.digitalLayout == DigitalLayout.STACKED)
        val views = RemoteViews(context.packageName, layout)

        val (timePaint, datePaint) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            profile.paints(context, layout)
        }
        val bmp = renderDigitalBitmap(context, config, colours, w, h, views, timePaint, datePaint, profile, weatherText)
        views.setImageViewIcon(R.id.background_shape, bmp.toIcon())

        val isStacked = config.digitalLayout == DigitalLayout.STACKED
        val clockIntent = openClockAppPendingIntent(context)
        val weatherIntent = openWeatherAppPendingIntent(context)
        val calendarIntent = openCalendarPendingIntent(context)

        if (isStacked) {
            views.setOnClickPendingIntent(android.R.id.background, clockIntent)
            views.setOnClickPendingIntent(R.id.live_hour_slot, clockIntent)
            views.setOnClickPendingIntent(R.id.live_minute_slot, clockIntent)
            views.setOnClickPendingIntent(R.id.live_date, calendarIntent)
        } else {
            // 1. Left half (0 .. rightZoneStartX, full height) -> Clock app
            views.setOnClickPendingIntent(android.R.id.background, clockIntent)
            views.setOnClickPendingIntent(R.id.live_row, clockIntent)
            views.setOnClickPendingIntent(R.id.live_hours, clockIntent)
            views.setOnClickPendingIntent(R.id.live_colon, clockIntent)
            views.setOnClickPendingIntent(R.id.live_minutes, clockIntent)

            // 2. Invisible Button #1 (top-right: rightZoneStartX .. w, 0 .. splitY) -> Weather app
            views.setOnClickPendingIntent(R.id.live_hour_slot, weatherIntent)

            // 3. Invisible Button #2 (bottom-right: rightZoneStartX .. w, splitY .. h) -> Calendar app
            views.setOnClickPendingIntent(R.id.live_minute_slot, calendarIntent)
            views.setOnClickPendingIntent(R.id.live_date, calendarIntent)
        }

        return views
    }

    private fun renderDigitalBitmap(
        context: Context,
        config: WidgetConfig,
        colours: FaceRenderer.Colours,
        w: Int,
        h: Int,
        views: RemoteViews,
        timePaint: Paint,
        datePaint: Paint,
        profile: NativeWidgetFonts.Profile,
        weatherText: String,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = context.resources.displayMetrics.density

        // 1. Full-bleed edge-to-edge background (transparent when Background = NONE)
        if (config.digitalBackground != DigitalBackground.NONE) {
            val rx = 24f * density
            if (config.digitalBackground == DigitalBackground.FILLED) {
                val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colours.face
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rect, rx, rx, fillPaint)
            } else if (config.digitalBackground == DigitalBackground.OUTLINED) {
                val strokeWidth = (2.5f * density).coerceAtLeast(3f)
                val half = strokeWidth / 2f
                val rect = RectF(half, half, w - half, h - half)
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colours.outline
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
                canvas.drawRoundRect(rect, rx, rx, strokePaint)
            }
        }

        val isStacked = config.digitalLayout == DigitalLayout.STACKED
        val isTimeOnly = !config.showDate && !config.showDayOfWeek
        val isCompact = config.digitalLayout == DigitalLayout.COMPACT

        val dateSkeleton = when {
            isTimeOnly -> ""
            config.showDate && config.showDayOfWeek -> "EEEdMMM"
            config.showDayOfWeek -> "EEE"
            else -> "dMMM"
        }
        val showDateView = dateSkeleton.isNotEmpty()

        val hourColorInt = config.digitalHourColor?.toInt() ?: colours.hourHand
        val colonColorInt = config.digitalColonColor?.toInt() ?: colours.onFace
        val minuteColorInt = config.digitalMinuteColor?.toInt() ?: colours.minuteHand
        val dateColorInt = config.digitalDateColor?.toInt() ?: colours.minor

        fun clock(id: Int, twelve: String, twentyFour: String) {
            views.setCharSequence(id, "setFormat12Hour", twelve)
            views.setCharSequence(id, "setFormat24Hour", twentyFour)
        }
        clock(R.id.live_hours, "h", "HH")
        val minutes = if (config.showSeconds && !isCompact) "mm:ss" else "mm"
        clock(R.id.live_minutes, minutes, minutes)

        val datePattern = if (showDateView)
            DateFormat.getBestDateTimePattern(Locale.getDefault(), dateSkeleton) else ""
        val styledDate = android.text.SpannableString(datePattern)
        if (config.showDayOfWeek) {
            val start = datePattern.indexOf('E')
            if (start >= 0) {
                val end = (start until datePattern.length).firstOrNull { datePattern[it] != 'E' } ?: datePattern.length
                styledDate.setSpan(
                    android.text.style.ForegroundColorSpan(
                        config.digitalDayColor?.toInt() ?: colours.minor
                    ),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        views.setCharSequence(R.id.live_date, "setFormat12Hour", styledDate)
        views.setCharSequence(R.id.live_date, "setFormat24Hour", styledDate)
        views.setBoolean(R.id.live_date, "setAllCaps", false)
        views.setViewVisibility(R.id.live_date, if (showDateView) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.live_row, if (isStacked) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.live_hours, View.VISIBLE)
        views.setViewVisibility(R.id.live_minutes, View.VISIBLE)

        fun inkWidth(text: String, paint: Paint): Float {
            val bounds = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            return maxOf(paint.measureText(text), bounds.right.toFloat()) -
                    minOf(0f, bounds.left.toFloat())
        }

        fun pairWidth(paint: Paint) = (0..59).maxOf {
            inkWidth(String.format(Locale.getDefault(), "%02d", it), paint)
        }

        val gutter = 2f * density

        if (isStacked) {
            views.setViewVisibility(R.id.live_hour_slot, View.VISIBLE)
            views.setViewVisibility(R.id.live_minute_slot, View.VISIBLE)
            views.setInt(R.id.live_hour_slot, "setBackgroundColor", 0x00000000)
            views.setInt(R.id.live_minute_slot, "setBackgroundColor", 0x00000000)

            var size = h * (if (showDateView) .28f else .35f)
            var dateSize = size * .28f
            timePaint.textSize = size
            val pair = pairWidth(timePaint)
            val colon = inkWidth(":", timePaint)
            val minute = if (config.showSeconds && !isCompact) pair * 2f + colon else pair
            val scale = DigitalTextFit.scale(w.toFloat(), pair, colon, minute, true, gutter)
            size *= scale
            dateSize *= scale
            timePaint.textSize = size
            datePaint.textSize = dateSize

            listOf(R.id.live_hours, R.id.live_minutes, R.id.live_colon, R.id.live_date).forEach {
                views.setViewPadding(it, gutter.roundToInt() / 2, 0, gutter.roundToInt() / 2, 0)
            }
            views.setInt(R.id.live_date, "setGravity", Gravity.CENTER)

            views.setTextViewTextSize(R.id.live_hours, TypedValue.COMPLEX_UNIT_PX, size)
            views.setTextColor(R.id.live_hours, hourColorInt)
            views.setTextViewTextSize(R.id.live_minutes, TypedValue.COMPLEX_UNIT_PX, size)
            views.setTextColor(R.id.live_minutes, minuteColorInt)
            views.setTextViewTextSize(R.id.live_colon, TypedValue.COMPLEX_UNIT_PX, size)
            views.setTextColor(R.id.live_colon, colonColorInt)
            views.setTextViewTextSize(R.id.live_date, TypedValue.COMPLEX_UNIT_PX, dateSize)
            views.setTextColor(R.id.live_date, dateColorInt)
            profile.variation?.let {
                views.setString(R.id.live_hours, "setFontVariationSettings", it)
                views.setString(R.id.live_minutes, "setFontVariationSettings", it)
                views.setString(R.id.live_colon, "setFontVariationSettings", it)
                views.setString(R.id.live_date, "setFontVariationSettings", it)
            }

            val cy = if (weatherText.isNotBlank()) h * 0.43f else h / 2f
            views.setViewPadding(R.id.live_hour_slot, 0, (cy - size * .05f + timePaint.fontMetrics.ascent).roundToInt().coerceAtLeast(0), 0, 0)
            views.setViewPadding(R.id.live_minute_slot, 0, (cy + size * .75f + timePaint.fontMetrics.ascent).roundToInt().coerceAtLeast(0), 0, 0)
            views.setViewPadding(R.id.live_date_slot, 0, (cy + size * 1.15f + datePaint.fontMetrics.ascent).roundToInt().coerceAtLeast(0), 0, 0)

            if (weatherText.isNotBlank()) {
                val weatherPaint = Paint(datePaint).apply {
                    color = dateColorInt
                    textSize = (dateSize * 0.95f).coerceAtLeast(12f * density)
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                canvas.drawText(weatherText, w / 2f, h - (10f * density), weatherPaint)
            }
        } else {
            var size = (h * 0.72f).coerceAtMost(96f * density)
            timePaint.textSize = size
            val pair = pairWidth(timePaint)
            val colon = inkWidth(":", timePaint)
            val minute = if (config.showSeconds && !isCompact) pair * 2f + colon else pair
            val scale = DigitalTextFit.scale((w * 0.68f), pair, colon, minute, false, gutter)
            size *= scale
            timePaint.textSize = size

            val rightTextSize = (h * 0.19f).coerceIn(17.5f * density, 23f * density)
            datePaint.textSize = rightTextSize

            // Zero out inner digit & date padding
            listOf(R.id.live_date, R.id.live_hours, R.id.live_minutes, R.id.live_colon).forEach {
                views.setViewPadding(it, 0, 0, 0, 0)
            }

            // 1. Time visual position (exact `56f` alignment with ZERO right padding on `live_row_slot` so minutes never clip)
            val timeBounds = android.graphics.Rect()
            timePaint.getTextBounds("10:35", 0, 5, timeBounds)
            val visualTimeCenterOffset = (timeBounds.top + timeBounds.bottom) / 2f
            val timeBaseline = (h / 2f) - visualTimeCenterOffset
            val timeTop = (timeBaseline + timePaint.fontMetrics.ascent).roundToInt().coerceAtLeast(0)
            val actualTimeWidth = timePaint.measureText("10:35")
            val rightPadInsideRow = (w - actualTimeWidth + (56f * density)).roundToInt().coerceAtLeast(0)
            views.setViewPadding(R.id.live_row, 0, 0, rightPadInsideRow, 0)
            views.setViewPadding(R.id.live_row_slot, 0, timeTop, 0, 0)

            // 2. Date & Day visual position (exact locked alignment)
            val centerLine = h / 2f
            val weatherGap = 8f * density
            val dateTop = (centerLine + (15f * density)).roundToInt().coerceAtLeast(0)
            val shiftedDateWidth = (datePaint.measureText("Thu, 19 Nov") * 0.60f).roundToInt()
            val leftPadForDate = (w - shiftedDateWidth).coerceAtLeast(0)
            views.setViewPadding(R.id.live_date_slot, leftPadForDate, dateTop, 0, 0)
            views.setInt(R.id.live_date, "setGravity", Gravity.CENTER)

            // 3. Two Invisible Buttons on the Right Side (50% wider toward left, split cleanly in the Weather/Date gap):
            //    - `rightZoneStartX`: ~48% of widget width (extends 50% further left than before)
            //    - `splitY`: exact midpoint between Weather and Day/Date
            val rightZoneStartX = (w * 0.48f).roundToInt()
            val splitY = ((centerLine + dateTop) / 2f).roundToInt().coerceAtLeast(1)

            // Invisible Button #1: Top-Right (`rightZoneStartX .. w`, `0 .. splitY`) -> opens Weather app
            views.setViewVisibility(R.id.live_hour_slot, View.VISIBLE)
            views.setViewPadding(R.id.live_hour_slot, 0, 0, 0, 0)
            views.setInt(R.id.live_hour_slot, "setBackgroundColor", 0x01000000)

            // Invisible Button #2: Bottom-Right (`rightZoneStartX .. w`, `splitY .. h`) -> opens Calendar app
            views.setViewVisibility(R.id.live_minute_slot, View.VISIBLE)
            views.setViewPadding(R.id.live_minute_slot, 0, 0, 0, 0)
            views.setInt(R.id.live_minute_slot, "setBackgroundColor", 0x01000000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutMargin(
                    R.id.live_hour_slot,
                    RemoteViews.MARGIN_START,
                    rightZoneStartX.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX,
                )
                views.setViewLayoutMargin(
                    R.id.live_hour_slot,
                    RemoteViews.MARGIN_BOTTOM,
                    (h - splitY).coerceAtLeast(1).toFloat(),
                    TypedValue.COMPLEX_UNIT_PX,
                )

                views.setViewLayoutMargin(
                    R.id.live_minute_slot,
                    RemoteViews.MARGIN_START,
                    rightZoneStartX.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX,
                )
                views.setViewLayoutMargin(
                    R.id.live_minute_slot,
                    RemoteViews.MARGIN_TOP,
                    splitY.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX,
                )
            }

            fun style(id: Int, sizePx: Float, color: Int) {
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sizePx)
                views.setTextColor(id, color)
                profile.variation?.let { views.setString(id, "setFontVariationSettings", it) }
            }
            style(R.id.live_hours, size, hourColorInt)
            style(R.id.live_minutes, size, minuteColorInt)
            style(R.id.live_colon, size, colonColorInt)
            style(R.id.live_date, rightTextSize, dateColorInt)

            // 4. Weather text drawn on bitmap (exact locked alignment)
            if (weatherText.isNotBlank()) {
                val weatherPaint = Paint(datePaint).apply {
                    color = dateColorInt
                    textSize = rightTextSize
                    textAlign = Paint.Align.RIGHT
                    isAntiAlias = true
                }
                val weatherBaseline = if (showDateView) {
                    centerLine - weatherGap
                } else {
                    centerLine + (rightTextSize * 0.35f)
                }
                canvas.drawText(weatherText, w.toFloat(), weatherBaseline, weatherPaint)
            }
        }

        return bmp
    }

    private fun Bitmap.toIcon(): Icon {
        val out = ByteArrayOutputStream(64 * 1024)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        compress(format, 100, out)
        val bytes = out.toByteArray()
        return Icon.createWithData(bytes, 0, bytes.size)
    }
}