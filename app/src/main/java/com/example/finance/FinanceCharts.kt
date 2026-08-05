package com.example.finance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.TextSecondary

@Composable
fun SpendSparkline(
    dailyTotals: List<DailyTotal>,
    modifier: Modifier = Modifier,
    lineColor: Color = AccentBlue
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(900),
        label = "sparkline"
    )

    LaunchedEffect(dailyTotals) {
        progress = 0f
        progress = 1f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        if (dailyTotals.isEmpty()) return@Canvas

        val max = dailyTotals.maxOf { it.totalPaise }.coerceAtLeast(1).toFloat()
        val stepX = size.width / (dailyTotals.size - 1).coerceAtLeast(1)
        val path = Path()

        dailyTotals.forEachIndexed { index, point ->
            val x = index * stepX
            val y = size.height - (point.totalPaise / max) * size.height * 0.85f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor.copy(alpha = 0.25f),
            style = Stroke(width = 8f, cap = StrokeCap.Round)
        )

        val clipPath = Path()
        clipPath.addRect(
            androidx.compose.ui.geometry.Rect(
                0f,
                0f,
                size.width * animatedProgress,
                size.height
            )
        )

        drawContext.canvas.save()
        drawContext.canvas.clipPath(clipPath)
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
        drawContext.canvas.restore()
    }
}

@Composable
fun CategoryBarChart(
    entries: List<Pair<String, Long>>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(700),
        label = "bars"
    )

    LaunchedEffect(entries) {
        progress = 0f
        progress = 1f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((entries.size.coerceAtLeast(1) * 28).dp)
    ) {
        if (entries.isEmpty()) return@Canvas

        val max = entries.maxOf { it.second }.coerceAtLeast(1).toFloat()
        val barHeight = 10f
        val gap = 18f

        entries.forEachIndexed { index, (_, value) ->
            val y = index * gap
            val barWidth = (value / max) * size.width * 0.72f * animatedProgress
            drawRoundRect(
                color = TextSecondary.copy(alpha = 0.15f),
                topLeft = Offset(0f, y),
                size = Size(size.width * 0.72f, barHeight),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = colors.getOrElse(index) { AccentGreen },
                topLeft = Offset(0f, y),
                size = Size(barWidth.coerceAtLeast(4f), barHeight),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
    }
}
