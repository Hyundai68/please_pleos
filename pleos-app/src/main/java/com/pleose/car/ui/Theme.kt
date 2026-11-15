package com.pleose.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pleos 내비 화면과 같은 색 (에뮬레이터 v2.1.0 내비 화면에서 확인한 값).
 * 낮에는 흰 표면·검정 글자·검정 주 버튼, 밤에는 그대로 반전된다. 모서리는 둥글리지 않는다.
 */
@Immutable
data class PleosPalette(
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val sub: Color,
    val dim: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
)

private val DayPalette = PleosPalette(
    surface = Color.White,
    surface2 = Color(0xFFF2F3F5),
    text = Color.Black,
    sub = Color(0xFF5F6368),
    dim = Color(0xFF8E9196),
    line = Color(0xFFBDBDBD),
    primary = Color.Black,
    onPrimary = Color.White,
)

private val NightPalette = PleosPalette(
    surface = Color.Black,
    surface2 = Color(0xFF1C1C1E),
    text = Color.White,
    sub = Color(0xFFB4B7BC),
    dim = Color(0xFF7C7F85),
    line = Color(0xFF606060),
    primary = Color.White,
    onPrimary = Color.Black,
)

/** 낮·밤 공통: 내비의 현재 위치 파랑, 하단 바 선택 주황 */
object PleosStatic {
    val blue = Color(0xFF0081F8)
    val orange = Color(0xFFFF9900)
    val red = Color(0xFFF23C3C)
    val green = Color(0xFF1E8E4E)
    val gray = Color(0xFF6B7178)
    val header = Color.Black
    val onHeader = Color.White
    val headerSub = Color(0xFFB4B7BC)
    val headerLine = Color(0xFF3A3A3A)
}

/** 내비 화면의 규격: 정사각 버튼 한 칸 */
object PleosSize {
    val cell = 99.dp
    val headerCell = 78.dp
}

private val LocalPalette = staticCompositionLocalOf { DayPalette }

val pleos: PleosPalette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

@Composable
fun PleosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalPalette provides if (dark) NightPalette else DayPalette) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
    }
}

enum class Glyph { CHECK, LOCK, CLOSE, BACK, PIN, GEAR, NAV, MORE, STAR, CLOCK, MIC, HOME, PLAY, STOP }

/** 단순한 아이콘 (아이콘 라이브러리 없이 그린다) */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val hole = pleos.surface
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        fun path(vararg pts: Pair<Float, Float>) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(w * x, h * y) else lineTo(w * x, h * y) }
        }
        when (glyph) {
            Glyph.CHECK -> drawPath(path(.18f to .52f, .42f to .75f, .84f to .28f), color, style = stroke)
            Glyph.LOCK -> {
                drawRoundRect(color, p(.2f, .45f), Size(w * .6f, h * .45f), CornerRadius(w * .08f))
                drawArc(color, 180f, 180f, false, p(.32f, .14f), Size(w * .36f, h * .6f), style = Stroke(w * .1f))
            }
            Glyph.CLOSE -> {
                drawLine(color, p(.2f, .2f), p(.8f, .8f), stroke.width, StrokeCap.Round)
                drawLine(color, p(.8f, .2f), p(.2f, .8f), stroke.width, StrokeCap.Round)
            }
            Glyph.BACK -> drawPath(path(.65f to .15f, .3f to .5f, .65f to .85f), color, style = stroke)
            Glyph.PIN -> {
                drawCircle(color, w * .3f, p(.5f, .38f))
                drawPath(path(.24f to .5f, .5f to .95f, .76f to .5f).apply { close() }, color)
                drawCircle(hole, w * .11f, p(.5f, .38f))
            }
            Glyph.GEAR -> {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val inner = Offset(w * .5f + (w * .28f * kotlin.math.cos(a)).toFloat(), h * .5f + (h * .28f * kotlin.math.sin(a)).toFloat())
                    val outer = Offset(w * .5f + (w * .44f * kotlin.math.cos(a)).toFloat(), h * .5f + (h * .44f * kotlin.math.sin(a)).toFloat())
                    drawLine(color, inner, outer, w * .13f)
                }
                drawCircle(color, w * .24f, style = Stroke(w * .13f))
            }
            Glyph.NAV -> drawPath(path(.5f to .08f, .86f to .9f, .5f to .7f, .14f to .9f).apply { close() }, color)
            Glyph.MORE -> for (i in 0..2) drawCircle(color, w * .08f, p(.2f + i * .3f, .5f))
            Glyph.STAR -> drawPath(
                Path().apply {
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) .46f else .2f
                        val a = Math.toRadians(-90.0 + i * 36.0)
                        val x = w * (.5f + r * kotlin.math.cos(a).toFloat())
                        val y = h * (.52f + r * kotlin.math.sin(a).toFloat())
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                },
                color,
            )
            Glyph.CLOCK -> {
                drawCircle(color, w * .4f, style = stroke)
                drawPath(path(.5f to .26f, .5f to .52f, .68f to .62f), color, style = stroke)
            }
            Glyph.MIC -> {
                drawRoundRect(color, p(.36f, .1f), Size(w * .28f, h * .48f), CornerRadius(w * .14f))
                drawArc(color, 0f, 180f, false, p(.24f, .3f), Size(w * .52f, h * .42f), style = stroke)
                drawLine(color, p(.5f, .72f), p(.5f, .9f), stroke.width, StrokeCap.Round)
            }
            Glyph.HOME -> drawPath(path(.14f to .48f, .5f to .14f, .86f to .48f, .76f to .48f, .76f to .86f, .24f to .86f, .24f to .48f).apply { close() }, color)
            Glyph.PLAY -> drawPath(path(.28f to .16f, .82f to .5f, .28f to .84f).apply { close() }, color)
            Glyph.STOP -> drawRect(color, p(.24f, .24f), Size(w * .52f, h * .52f))
        }
    }
}

/** Please 표시: 말풍선(부탁) 안을 지나는 경로와 경유지 */
@Composable
fun PleaseMark(color: Color, routeColor: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(color, Offset(w * .06f, h * .1f), Size(w * .88f, h * .62f), CornerRadius(w * .12f))
        drawPath(
            Path().apply { moveTo(w * .3f, h * .68f); lineTo(w * .3f, h * .94f); lineTo(w * .54f, h * .68f); close() },
            color,
        )
        drawPath(
            Path().apply { moveTo(w * .22f, h * .55f); lineTo(w * .42f, h * .33f); lineTo(w * .58f, h * .47f); lineTo(w * .78f, h * .26f) },
            routeColor,
            style = Stroke(width = w * .08f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(PleosStatic.blue, w * .085f, Offset(w * .58f, h * .47f))
    }
}
