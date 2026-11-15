package com.pleose.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 요청자 앱 시안 색 (흰 바탕, 네이비 강조) */
object MColors {
    val bg = Color(0xFFF2F3F5)
    val surface = Color.White
    val navy = Color(0xFF0D2C5E)
    val blue = Color(0xFF2F7FD8)
    val text = Color(0xFF14171C)
    val sub = Color(0xFF6B7280)
    val dim = Color(0xFF9CA3AF)
    val line = Color(0xFFE3E6EA)
    val box = Color(0xFFF4F5F7)
    val red = Color(0xFFD64545)
    val amber = Color(0xFFB7791F)
}

enum class MGlyph { BACK, PLUS, MIC, LOCK, CHECK, SHARE, CLOSE }

@Composable
fun MIcon(glyph: MGlyph, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.1f
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (glyph) {
            MGlyph.BACK -> drawPath(Path().apply { moveTo(w * .62f, h * .18f); lineTo(w * .3f, h * .5f); lineTo(w * .62f, h * .82f) }, color, style = stroke)
            MGlyph.PLUS -> {
                drawLine(color, p(.5f, .2f), p(.5f, .8f), sw, StrokeCap.Round)
                drawLine(color, p(.2f, .5f), p(.8f, .5f), sw, StrokeCap.Round)
            }
            MGlyph.MIC -> {
                drawRoundRect(color, p(.37f, .1f), Size(w * .26f, h * .48f), CornerRadius(w * .13f))
                drawArc(color, 0f, 180f, false, p(.24f, .3f), Size(w * .52f, h * .42f), style = stroke)
                drawLine(color, p(.5f, .72f), p(.5f, .88f), sw, StrokeCap.Round)
            }
            MGlyph.LOCK -> {
                drawRoundRect(color, p(.22f, .46f), Size(w * .56f, h * .42f), CornerRadius(w * .08f))
                drawArc(color, 180f, 180f, false, p(.33f, .16f), Size(w * .34f, h * .56f), style = Stroke(w * .09f))
            }
            MGlyph.CHECK -> drawPath(Path().apply { moveTo(w * .2f, h * .52f); lineTo(w * .42f, h * .74f); lineTo(w * .82f, h * .3f) }, color, style = stroke)
            MGlyph.SHARE -> {
                drawCircle(color, w * .1f, p(.72f, .22f))
                drawCircle(color, w * .1f, p(.28f, .5f))
                drawCircle(color, w * .1f, p(.72f, .78f))
                drawLine(color, p(.28f, .5f), p(.72f, .22f), sw * .8f)
                drawLine(color, p(.28f, .5f), p(.72f, .78f), sw * .8f)
            }
            MGlyph.CLOSE -> {
                drawLine(color, p(.24f, .24f), p(.76f, .76f), sw, StrokeCap.Round)
                drawLine(color, p(.76f, .24f), p(.24f, .76f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun Avatar(initial: String, size: Dp = 38.dp) {
    Box(Modifier.size(size).background(MColors.navy, CircleShape), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NavyButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(54.dp).background(if (enabled) MColors.navy else Color(0xFFC9CED6), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun OutlinedNavyButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).border(1.dp, if (enabled) MColors.navy else MColors.line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) MColors.navy else MColors.dim, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun LinkText(text: String, color: Color = MColors.text, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MColors.sub, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
fun InputField(value: String, onChange: (String) -> Unit, placeholder: String, caps: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().background(MColors.surface, RoundedCornerShape(4.dp)).border(1.dp, MColors.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = MColors.dim, fontSize = 15.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = MColors.text, fontSize = 15.sp),
            cursorBrush = SolidColor(MColors.navy),
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun CheckBoxSquare(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(24.dp)
            .background(if (checked) MColors.navy else MColors.surface, RoundedCornerShape(3.dp))
            .border(1.5.dp, if (checked) MColors.navy else MColors.dim, RoundedCornerShape(3.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) { if (checked) MIcon(MGlyph.CHECK, Color.White, 18.dp) }
}
