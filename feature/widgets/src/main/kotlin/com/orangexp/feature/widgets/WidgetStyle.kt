package com.orangexp.feature.widgets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.orangexp.core.engine.ffi.DayState

/** Widget palette: the app's orange identity, following the launcher's light/dark mode. */
internal object WidgetColors {
    val background = ColorProvider(day = Color(0xFFFBF8F5), night = Color(0xFF161412))
    val surface = ColorProvider(day = Color(0xFFF2EDE8), night = Color(0xFF24211E))
    val onBackground = ColorProvider(day = Color(0xFF0E0D0C), night = Color(0xFFEDE7E1))
    val subtle = ColorProvider(day = Color(0xFF6F675E), night = Color(0xFFB3AAA0))
    val primary = ColorProvider(day = Color(0xFFD65212), night = Color(0xFFF2661B))

    fun state(state: DayState): ColorProvider = when (state) {
        DayState.GREEN -> ColorProvider(day = Color(0xFF218A4F), night = Color(0xFF34B36A))
        DayState.ORANGE -> ColorProvider(day = Color(0xFFD9800F), night = Color(0xFFF29A2E))
        DayState.RED -> ColorProvider(day = Color(0xFFCC2F35), night = Color(0xFFE5484D))
        DayState.BLACK -> ColorProvider(day = Color(0xFF000000), night = Color(0xFF8A8078))
    }

    /** Heatmap levels 0..5 as ARGB, for the rendered calendar bitmap. */
    fun heat(context: Context): IntArray =
        if (context.isNight()) {
            intArrayOf(0xFF2C2825.toInt(), 0xFF4A2814.toInt(), 0xFF7D3813.toInt(), 0xFFB44C12.toInt(), 0xFFE8631A.toInt(), 0xFFFF8F45.toInt())
        } else {
            intArrayOf(0xFFE6DED6.toInt(), 0xFFFDD8BC.toInt(), 0xFFFBB07C.toInt(), 0xFFF5873F.toInt(), 0xFFE2621A.toInt(), 0xFFAF440B.toInt())
        }

    fun todayOutline(context: Context): Int = if (context.isNight()) 0xFFF2661B.toInt() else 0xFFD65212.toInt()

    private fun Context.isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Rounded widget surface that opens the app when tapped. */
@Composable
internal fun WidgetSurface(content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    var modifier = GlanceModifier.fillMaxSize().background(WidgetColors.background).cornerRadius(24.dp)
    if (launch != null) modifier = modifier.clickable(actionStartActivity(launch))
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
}

@Composable
internal fun Label(text: String, color: ColorProvider = WidgetColors.subtle) {
    Text(text.uppercase(), style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
}

@Composable
internal fun Numeral(text: String, size: TextUnit, color: ColorProvider = WidgetColors.onBackground) {
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace), maxLines = 1)
}

@Composable
internal fun Body(text: String, color: ColorProvider = WidgetColors.onBackground, size: TextUnit = 13.sp, bold: Boolean = false) {
    Text(
        text,
        style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
        maxLines = 1,
    )
}
