package com.example.smarty.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

class JLatexMathPainter(
    private val latex: String,
    private val textSizePx: Float,
    private val textColorInt: Int,
    private val backgroundColorInt: Int,
) : Painter() {
    private val drawable by lazy {
        try {
            JLatexMathDrawable
                .builder(latex)
                .textSize(textSizePx)
                .color(textColorInt)
                .background(backgroundColorInt)
                .align(JLatexMathDrawable.ALIGN_CENTER)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    override val intrinsicSize: Size
        get() =
            drawable?.let {
                Size(it.intrinsicWidth.toFloat(), it.intrinsicHeight.toFloat())
            } ?: Size.Zero

    override fun DrawScope.onDraw() {
        drawable?.let { d ->
            d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            d.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

@Composable
fun LaTeXView(
    latex: String,
    isBlock: Boolean = true,
    modifier: Modifier = Modifier,
    textColor: Color = if (isSystemInDarkTheme()) Color.White else Color.Black,
    backgroundColor: Color = Color.Transparent,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { 16.sp.toPx() }
    val textColorInt = textColor.toArgb()
    val bgColorInt = backgroundColor.toArgb()

    val painter =
        remember(latex, textSizePx, textColorInt, bgColorInt) {
            JLatexMathPainter(latex, textSizePx, textColorInt, bgColorInt)
        }

    Box(
        modifier = modifier.then(if (isBlock) Modifier.fillMaxWidth().padding(vertical = 8.dp) else Modifier),
        contentAlignment = if (isBlock) Alignment.Center else Alignment.CenterStart,
    ) {
        if (painter.intrinsicSize == Size.Zero && latex.isNotEmpty()) {
            // Render error fallback
            Text(
                text = "Formula rendering error",
                color = Color.Red,
                fontSize = 12.sp,
            )
        } else {
            Image(
                painter = painter,
                contentDescription = "LaTeX Formula",
                modifier = Modifier,
            )
        }
    }
}

@Composable
fun LaTeXViewInline(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = if (isSystemInDarkTheme()) Color.White else Color.Black,
) {
    LaTeXView(
        latex = latex,
        isBlock = false,
        modifier = modifier,
        textColor = textColor,
    )
}
