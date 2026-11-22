package app.expressiveclock.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextClock
import androidx.core.content.res.ResourcesCompat
import app.expressiveclock.R
import app.expressiveclock.data.DigitalTypefaceWeight
import app.expressiveclock.data.WidgetFontFamily
import java.text.DateFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Shared native font resolution for RemoteViews and the current-device Compose preview. */
internal object NativeWidgetFonts {
    private val verified = ConcurrentHashMap<String, Boolean>()

    data class Profile(
        val kind: NativeFontKind,
        val requested: WidgetFontFamily,
        val weight: DigitalTypefaceWeight,
    ) {
        val variation: String? get() = if (kind == NativeFontKind.GOOGLE_FLEX)
            "'opsz' 144,'wdth' ${if (requested == WidgetFontFamily.GOOGLE_SANS) 25 else 100},'wght' ${weight.weightInt}" else null
        val isFallback: Boolean get() = requested in listOf(WidgetFontFamily.GOOGLE_SANS, WidgetFontFamily.GOOGLE_SANS_NORMAL) && kind != NativeFontKind.GOOGLE_FLEX
        val description: String get() = when (kind) {
            NativeFontKind.CONDENSED -> "Uses this device’s condensed sans font. Your Condensed style preference is preserved."
            NativeFontKind.SANS -> "Uses this device’s system sans font (OnePlus / OPlus Sans)."
            else -> "Uses this device’s sans font. Your ${requested.label} style preference is preserved."
        }

        fun layout(stacked: Boolean): Int = when (kind) {
            NativeFontKind.GOOGLE_FLEX -> if (stacked) R.layout.widget_live_digital_stacked_flex else R.layout.widget_live_digital_flex
            NativeFontKind.CONDENSED -> (if (stacked) intArrayOf(R.layout.widget_live_digital_stacked_condensed_100, R.layout.widget_live_digital_stacked_condensed_400, R.layout.widget_live_digital_stacked_condensed_600, R.layout.widget_live_digital_stacked_condensed_900) else intArrayOf(R.layout.widget_live_digital_condensed_100, R.layout.widget_live_digital_condensed_400, R.layout.widget_live_digital_condensed_600, R.layout.widget_live_digital_condensed_900))[weight.ordinal]
            NativeFontKind.SANS -> (if (stacked) intArrayOf(R.layout.widget_live_digital_stacked_sans_100, R.layout.widget_live_digital_stacked_sans_400, R.layout.widget_live_digital_stacked_sans_600, R.layout.widget_live_digital_stacked_sans_900) else intArrayOf(R.layout.widget_live_digital_sans_100, R.layout.widget_live_digital_sans_400, R.layout.widget_live_digital_sans_600, R.layout.widget_live_digital_sans_900))[weight.ordinal]
            NativeFontKind.SERIF -> (if (stacked) intArrayOf(R.layout.widget_live_digital_stacked_serif_100, R.layout.widget_live_digital_stacked_serif_400, R.layout.widget_live_digital_stacked_serif_600, R.layout.widget_live_digital_stacked_serif_900) else intArrayOf(R.layout.widget_live_digital_serif_100, R.layout.widget_live_digital_serif_400, R.layout.widget_live_digital_serif_600, R.layout.widget_live_digital_serif_900))[weight.ordinal]
            NativeFontKind.MONOSPACE -> (if (stacked) intArrayOf(R.layout.widget_live_digital_stacked_mono_100, R.layout.widget_live_digital_stacked_mono_400, R.layout.widget_live_digital_stacked_mono_600, R.layout.widget_live_digital_stacked_mono_900) else intArrayOf(R.layout.widget_live_digital_mono_100, R.layout.widget_live_digital_mono_400, R.layout.widget_live_digital_mono_600, R.layout.widget_live_digital_mono_900))[weight.ordinal]
            NativeFontKind.CURSIVE -> if (stacked) R.layout.widget_live_digital_stacked_cursive else R.layout.widget_live_digital_cursive
        }

        /** Call on the UI thread: use the same restricted inflation and native setters as hosts. */
        fun paints(context: Context, layout: Int): Pair<Paint, Paint> {
            val restricted = context.createPackageContext(context.packageName, Context.CONTEXT_RESTRICTED)
            val root = LayoutInflater.from(restricted).inflate(layout, null)
            fun paint(id: Int): Paint {
                val view = root.findViewById<TextClock>(id)
                if (kind == NativeFontKind.SANS) {
                    val sysSans = Typeface.create("sys-sans-en", Typeface.NORMAL)
                    if (sysSans != Typeface.DEFAULT) {
                        view.typeface = sysSans
                    }
                }
                variation?.let { view.setFontVariationSettings(it) }
                return Paint(view.paint)
            }
            return paint(R.id.live_hours) to paint(R.id.live_date)
        }
    }

    fun resolve(context: Context, requested: WidgetFontFamily, weight: DigitalTypefaceWeight): Profile {
        // Use OxygenOS system font (`sys-sans-en` / OPlusSansEn) when GOOGLE_SANS_NORMAL is selected
        if (requested == WidgetFontFamily.GOOGLE_SANS_NORMAL) {
            return Profile(NativeFontKind.SANS, requested, weight)
        }
        val googleRequested = requested == WidgetFontFamily.GOOGLE_SANS
        val width = 25
        val key = "$width:${weight.weightInt}:${context.resources.configuration}"
        val exact = googleRequested && verified.getOrPut(key) {
            try { verify(context, width, weight.weightInt) } catch (error: RuntimeException) {
                Log.w("NativeWidgetFonts", "Using native fallback after font verification failed", error)
                false
            }
        }
        val condensedAvailable = Typeface.create("sans-serif-condensed", Typeface.NORMAL) != Typeface.DEFAULT
        val kind = WidgetFontPolicy.choose(requested, exact, condensedAvailable)
        return Profile(kind, requested, weight)
    }

    private fun verify(context: Context, width: Int, weight: Int): Boolean {
        val native = Typeface.create("google-sans-flex", Typeface.NORMAL)
        if (native == Typeface.DEFAULT) return false
        val bundled = ResourcesCompat.getFont(context, R.font.google_sans_flex) ?: return false
        fun paint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 100f
            fontFeatureSettings = null
            setFontVariationSettings("'opsz' 144,'wdth' $width,'wght' $weight")
        }
        val actual = paint(native)
        val expected = paint(bundled)
        val symbols = DateFormatSymbols.getInstance()
        val sample = ("0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ, /.-" +
                symbols.shortMonths.joinToString("") + symbols.shortWeekdays.joinToString("") +
                (0..59).joinToString("") { String.format(Locale.getDefault(), "%02d", it) })
            .uppercase(Locale.getDefault())
        if (abs(actual.measureText(sample) - expected.measureText(sample)) > .01f) return false
        fun outline(paint: Paint): FloatArray = Path().let {
            paint.getTextPath(sample, 0, sample.length, 0f, 0f, it)
            it.approximate(.1f)
        }
        val a = outline(actual)
        val b = outline(expected)
        val compatible = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < .01f }
        Log.i("NativeWidgetFonts", "google-sans-flex width=$width weight=$weight compatible=$compatible")
        return compatible
    }
}