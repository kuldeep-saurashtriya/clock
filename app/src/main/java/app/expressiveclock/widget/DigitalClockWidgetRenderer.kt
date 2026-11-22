package app.expressiveclock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import app.expressiveclock.widget.WidgetWeatherHelper.WeatherIconKind
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object DigitalClockWidgetRenderer {

    suspend fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        forceWeatherRefresh: Boolean = false,
        weatherOverride: String? = null,
        dateOverride: String? = null,
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
            build(context, config, settings, options, weatherText, dateOverride)
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

    private fun weatherTapPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DigitalClockWidgetProvider::class.java).apply {
            action = DigitalClockWidgetProvider.ACTION_WEATHER_TAP
        }
        return PendingIntent.getBroadcast(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dateTapPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DigitalClockWidgetProvider::class.java).apply {
            action = DigitalClockWidgetProvider.ACTION_DATE_TAP
        }
        return PendingIntent.getBroadcast(
            context,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun build(
        context: Context,
        config: WidgetConfig,
        settings: ClockSettings,
        options: Bundle,
        weatherText: String,
        dateOverride: String?,
    ): RemoteViews {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt().coerceAtLeast(1)

        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)

        val portrait = one(context, config, settings, px(minW), px(maxH), weatherText, dateOverride)
        val landscape = one(context, config, settings, px(maxW), px(minH), weatherText, dateOverride)
        return RemoteViews(landscape, portrait)
    }

    private suspend fun one(
        context: Context,
        config: WidgetConfig,
        settings: ClockSettings,
        w: Int,
        h: Int,
        weatherText: String,
        dateOverride: String?,
    ): RemoteViews {
        val colours = WidgetColours.resolve(context, config, settings)
        val profile = NativeWidgetFonts.resolve(context, config.digitalFont, config.digitalWeight)
        val layout = profile.layout(config.digitalLayout == DigitalLayout.STACKED)
        val views = RemoteViews(context.packageName, layout)

        val (timePaint, datePaint) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            profile.paints(context, layout)
        }
        val bmp = renderDigitalBitmap(
            context, config, colours, w, h, views, timePaint, datePaint, profile, weatherText, dateOverride
        )
        views.setImageViewIcon(R.id.background_shape, bmp.toIcon())

        val isStacked = config.digitalLayout == DigitalLayout.STACKED
        val clockIntent = openClockAppPendingIntent(context)
        val weatherIntent = weatherTapPendingIntent(context)
        val dateIntent = dateTapPendingIntent(context)

        if (isStacked) {
            views.setOnClickPendingIntent(android.R.id.background, clockIntent)
            views.setOnClickPendingIntent(R.id.live_hour_slot, clockIntent)
            views.setOnClickPendingIntent(R.id.live_minute_slot, clockIntent)
            views.setOnClickPendingIntent(R.id.live_date, dateIntent)
        } else {
            views.setOnClickPendingIntent(android.R.id.background, clockIntent)
            views.setOnClickPendingIntent(R.id.live_row, clockIntent)
            views.setOnClickPendingIntent(R.id.live_hours, clockIntent)
            views.setOnClickPendingIntent(R.id.live_colon, clockIntent)
            views.setOnClickPendingIntent(R.id.live_minutes, clockIntent)

            // Top-right zone: single-tap opens Weather app, double-tap force-refreshes weather
            views.setOnClickPendingIntent(R.id.live_hour_slot, weatherIntent)

            // Bottom-right zone: single-tap opens Calendar app, double-tap toggles next event / "Nothing Planned"
            views.setOnClickPendingIntent(R.id.live_minute_slot, dateIntent)
            views.setOnClickPendingIntent(R.id.live_date, dateIntent)
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
        dateOverride: String?,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = context.resources.displayMetrics.density

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
        val showDateView = dateSkeleton.isNotEmpty() || !dateOverride.isNullOrBlank()

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

        if (!dateOverride.isNullOrBlank()) {
            val escaped = "'${dateOverride.replace("'", "''")}'"
            views.setCharSequence(R.id.live_date, "setFormat12Hour", escaped)
            views.setCharSequence(R.id.live_date, "setFormat24Hour", escaped)
        } else {
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
        }
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
                val weatherSize = (dateSize * 0.95f).coerceAtLeast(12f * density)
                val weatherPaint = Paint(datePaint).apply {
                    color = dateColorInt
                    textSize = weatherSize
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val parsed = WidgetWeatherHelper.parseBadge(weatherText)
                drawWeatherWithSolidIcon(
                    canvas = canvas,
                    iconKind = parsed.iconKind,
                    isDay = parsed.isDay,
                    label = parsed.label,
                    rightX = (w / 2f) + (weatherPaint.measureText(parsed.label) / 2f) + (if (parsed.iconKind != null) weatherSize * 0.6f else 0f),
                    baselineY = h - (10f * density),
                    textPaint = weatherPaint,
                    normalColor = dateColorInt,
                    density = density,
                )
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

            listOf(R.id.live_date, R.id.live_hours, R.id.live_minutes, R.id.live_colon).forEach {
                views.setViewPadding(it, 0, 0, 0, 0)
            }
            listOf(R.id.live_hours, R.id.live_minutes, R.id.live_colon).forEach {
                views.setBoolean(it, "setSingleLine", true)
            }

            val colonLiftPx = (size * 0.14f).roundToInt().coerceAtLeast(1)
            views.setInt(R.id.live_row, "setGravity", Gravity.BOTTOM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutMargin(
                    R.id.live_colon,
                    RemoteViews.MARGIN_BOTTOM,
                    colonLiftPx.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX,
                )
            } else {
                views.setViewPadding(R.id.live_colon, 0, 0, 0, colonLiftPx)
            }

            val centerLine = h * 0.525f
            val weatherGap = 8f * density
            val dateTop = (centerLine + (15f * density)).roundToInt().coerceAtLeast(0)
            views.setViewPadding(R.id.live_date_slot, 0, dateTop, 0, 0)
            views.setInt(R.id.live_date, "setGravity", Gravity.END)
            views.setBoolean(R.id.live_date, "setSingleLine", true)

            val dateBaseline = dateTop - datePaint.fontMetrics.ascent
            val timeTop = (dateBaseline + timePaint.fontMetrics.ascent).roundToInt().coerceAtLeast(0)
            views.setViewPadding(R.id.live_row, 0, 0, 0, 0)
            views.setViewPadding(R.id.live_row_slot, 0, timeTop, 0, 0)

            val rightZoneStartX = (w * 0.48f).roundToInt()
            val splitY = ((centerLine + dateTop) / 2f).roundToInt().coerceAtLeast(1)

            views.setViewVisibility(R.id.live_hour_slot, View.VISIBLE)
            views.setViewPadding(R.id.live_hour_slot, 0, 0, 0, 0)
            views.setInt(R.id.live_hour_slot, "setBackgroundColor", 0x01000000)

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
            val dateDisplaySize = if (dateOverride != null) (rightTextSize * 0.85f) else rightTextSize
            style(R.id.live_date, dateDisplaySize, dateColorInt)

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
                val parsed = WidgetWeatherHelper.parseBadge(weatherText)
                drawWeatherWithSolidIcon(
                    canvas = canvas,
                    iconKind = parsed.iconKind,
                    isDay = parsed.isDay,
                    label = parsed.label,
                    rightX = w.toFloat(),
                    baselineY = weatherBaseline,
                    textPaint = weatherPaint,
                    normalColor = dateColorInt,
                    density = density,
                )
            }
        }

        return bmp
    }

    private fun resolveWeatherIconColor(
        iconKind: WeatherIconKind,
        isDay: Boolean,
        normalColor: Int,
    ): Int {
        if (iconKind == WeatherIconKind.LOADING) return normalColor

        val sunnyDayColor = 0xFFA2D2CF.toInt()
        val midToneColor = 0xFF6F9F9C.toInt()
        val severeNightColor = 0xFF618B88.toInt()

        val isSevereWeather = iconKind == WeatherIconKind.RAIN ||
                iconKind == WeatherIconKind.SNOW ||
                iconKind == WeatherIconKind.STORM

        return if (isDay) {
            when {
                iconKind == WeatherIconKind.CLEAR_DAY -> sunnyDayColor
                isSevereWeather -> midToneColor
                else -> normalColor
            }
        } else {
            if (isSevereWeather) severeNightColor else midToneColor
        }
    }

    private fun drawWeatherWithSolidIcon(
        canvas: Canvas,
        iconKind: WeatherIconKind?,
        isDay: Boolean,
        label: String,
        rightX: Float,
        baselineY: Float,
        textPaint: Paint,
        normalColor: Int,
        density: Float,
    ) {
        canvas.drawText(label, rightX, baselineY, textPaint)
        if (iconKind == null) return

        val iconColor = resolveWeatherIconColor(iconKind, isDay, normalColor)
        val textWidth = textPaint.measureText(label)
        val iconSize = textPaint.textSize * 0.92f
        val gap = 4.5f * density
        val iconRight = rightX - textWidth - gap
        val iconLeft = iconRight - iconSize
        val capBounds = android.graphics.Rect()
        textPaint.getTextBounds("28", 0, 2, capBounds)
        val visualCenterY = baselineY + ((capBounds.top + capBounds.bottom) / 2f)
        val cx = (iconLeft + iconRight) / 2f
        val cy = visualCenterY

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = iconColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = iconColor
            style = Paint.Style.STROKE
            strokeWidth = (1.8f * density).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun drawSun(sunX: Float, sunY: Float, r: Float) {
            canvas.drawCircle(sunX, sunY, r * 0.52f, fillPaint)
            val rayInner = r * 0.72f
            val rayOuter = r * 0.98f
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()
                canvas.drawLine(
                    sunX + cosA * rayInner,
                    sunY + sinA * rayInner,
                    sunX + cosA * rayOuter,
                    sunY + sinA * rayOuter,
                    strokePaint,
                )
            }
        }

        fun drawMoon(moonX: Float, moonY: Float, r: Float) {
            val moonPath = Path().apply {
                addCircle(moonX, moonY, r * 0.82f, Path.Direction.CW)
            }
            val cutout = Path().apply {
                addCircle(moonX + r * 0.38f, moonY - r * 0.30f, r * 0.68f, Path.Direction.CW)
            }
            moonPath.op(cutout, Path.Op.DIFFERENCE)
            canvas.drawPath(moonPath, fillPaint)
        }

        fun drawCloud(cloudX: Float, cloudY: Float, width: Float) {
            val h = width * 0.56f
            val left = cloudX - width / 2f
            val bottom = cloudY + h * 0.42f
            val path = Path().apply {
                addRoundRect(
                    RectF(left, bottom - h * 0.52f, left + width, bottom),
                    h * 0.26f,
                    h * 0.26f,
                    Path.Direction.CW,
                )
                addCircle(left + width * 0.38f, bottom - h * 0.52f, width * 0.23f, Path.Direction.CW)
                addCircle(left + width * 0.66f, bottom - h * 0.44f, width * 0.17f, Path.Direction.CW)
            }
            canvas.drawPath(path, fillPaint)
        }

        val r = iconSize * 0.48f
        when (iconKind) {
            WeatherIconKind.LOADING -> {
                val halfW = iconSize * 0.32f
                val halfH = iconSize * 0.40f
                // Top and bottom caps
                canvas.drawLine(cx - halfW * 1.15f, cy - halfH, cx + halfW * 1.15f, cy - halfH, strokePaint)
                canvas.drawLine(cx - halfW * 1.15f, cy + halfH, cx + halfW * 1.15f, cy + halfH, strokePaint)
                // Hourglass frame
                val frame = Path().apply {
                    moveTo(cx - halfW, cy - halfH)
                    lineTo(cx + halfW, cy - halfH)
                    lineTo(cx, cy)
                    lineTo(cx + halfW, cy + halfH)
                    lineTo(cx - halfW, cy + halfH)
                    lineTo(cx, cy)
                    close()
                }
                canvas.drawPath(frame, strokePaint)
                // Filled bottom sand
                val sand = Path().apply {
                    moveTo(cx - halfW * 0.68f, cy + halfH)
                    lineTo(cx + halfW * 0.68f, cy + halfH)
                    lineTo(cx, cy + halfH * 0.32f)
                    close()
                }
                canvas.drawPath(sand, fillPaint)
            }
            WeatherIconKind.CLEAR_DAY -> drawSun(cx, cy, r)
            WeatherIconKind.CLEAR_NIGHT -> drawMoon(cx, cy, r)
            WeatherIconKind.PARTLY_CLOUDY_DAY -> {
                drawSun(cx - r * 0.24f, cy - r * 0.22f, r * 0.68f)
                drawCloud(cx + r * 0.12f, cy + r * 0.20f, iconSize * 0.78f)
            }
            WeatherIconKind.PARTLY_CLOUDY_NIGHT -> {
                drawMoon(cx - r * 0.24f, cy - r * 0.20f, r * 0.68f)
                drawCloud(cx + r * 0.12f, cy + r * 0.20f, iconSize * 0.78f)
            }
            WeatherIconKind.CLOUDY -> drawCloud(cx, cy + r * 0.08f, iconSize * 0.92f)
            WeatherIconKind.FOG -> {
                val halfW = iconSize * 0.42f
                for (row in -1..1) {
                    val y = cy + row * (iconSize * 0.24f)
                    canvas.drawLine(cx - halfW, y, cx + halfW, y, strokePaint)
                }
            }
            WeatherIconKind.RAIN -> {
                drawCloud(cx, cy - r * 0.12f, iconSize * 0.86f)
                for (dx in listOf(-0.22f, 0f, 0.22f)) {
                    val x = cx + iconSize * dx
                    canvas.drawLine(x, cy + r * 0.46f, x - iconSize * 0.07f, cy + r * 0.82f, strokePaint)
                }
            }
            WeatherIconKind.SNOW -> {
                drawCloud(cx, cy - r * 0.12f, iconSize * 0.86f)
                for (dx in listOf(-0.22f, 0f, 0.22f)) {
                    canvas.drawCircle(cx + iconSize * dx, cy + r * 0.65f, 1.5f * density, fillPaint)
                }
            }
            WeatherIconKind.STORM -> {
                drawCloud(cx, cy - r * 0.14f, iconSize * 0.86f)
                val bolt = Path().apply {
                    moveTo(cx + iconSize * 0.04f, cy + r * 0.34f)
                    lineTo(cx - iconSize * 0.10f, cy + r * 0.66f)
                    lineTo(cx + iconSize * 0.03f, cy + r * 0.66f)
                    lineTo(cx - iconSize * 0.06f, cy + r * 0.96f)
                }
                canvas.drawPath(bolt, strokePaint)
            }
        }
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