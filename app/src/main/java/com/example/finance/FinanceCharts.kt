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
import androidx.compose.ui.graphics.Brush
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
            .height(120.dp)
    ) {
        val padLeft = 8f
        val padBottom = 12f
        val padTop = 8f
        val chartW = size.width - padLeft - 8f
        val chartH = size.height - padTop - padBottom

        // Subtle grid
        val gridColor = TextSecondary.copy(alpha = 0.12f)
        for (i in 1..3) {
            val gy = padTop + chartH * i / 4f
            drawLine(gridColor, Offset(padLeft, gy), Offset(size.width - 8f, gy), 1f)
        }

        if (dailyTotals.isEmpty()) {
            drawLine(
                gridColor.copy(alpha = 0.35f),
                Offset(padLeft, padTop + chartH * 0.5f),
                Offset(size.width - 8f, padTop + chartH * 0.5f),
                1.5f
            )
            return@Canvas
        }

        val max = dailyTotals.maxOf { it.totalPaise }.coerceAtLeast(1).toFloat()
        val count = dailyTotals.size
        val stepX = if (count <= 1) chartW else chartW / (count - 1)

        val points = dailyTotals.mapIndexed { index, point ->
            val x = if (count <= 1) padLeft + chartW / 2f else padLeft + index * stepX
            val y = padTop + chartH - (point.totalPaise / max) * chartH * 0.9f
            Offset(x, y)
        }

        if (points.size == 1) {
            val y = points.first().y
            val left = padLeft
            val right = padLeft + chartW
            val flatArea = Path().apply {
                moveTo(left, y)
                lineTo(right, y)
                lineTo(right, padTop + chartH)
                lineTo(left, padTop + chartH)
                close()
            }
            drawPath(
                flatArea,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f)),
                    startY = y,
                    endY = padTop + chartH
                )
            )
            drawLine(
                color = lineColor.copy(alpha = 0.25f),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 10f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = lineColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
            drawCircle(lineColor.copy(alpha = 0.35f), radius = 8f, center = points.first())
            drawCircle(lineColor, radius = 5f, center = points.first())
            return@Canvas
        }

        val areaPath = Path().apply {
            moveTo(points.first().x, padTop + chartH)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, padTop + chartH)
            close()
        }
        val clipW = chartW * animatedProgress + padLeft
        drawContext.canvas.save()
        drawContext.canvas.clipRect(0f, 0f, clipW, size.height)
        drawPath(
            areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f)),
                startY = padTop,
                endY = padTop + chartH
            )
        )
        drawContext.canvas.restore()

        val linePath = Path().apply {
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        drawContext.canvas.save()
        drawContext.canvas.clipRect(0f, 0f, clipW, size.height)
        drawPath(
            path = linePath,
            color = lineColor.copy(alpha = 0.2f),
            style = Stroke(width = 10f, cap = StrokeCap.Round)
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
        points.forEach { p ->
            drawCircle(lineColor.copy(alpha = 0.35f), radius = 5f, center = p)
            drawCircle(lineColor, radius = 3f, center = p)
        }
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

    val rowHeight = 32.dp
    val chartHeight = rowHeight * entries.size.coerceAtLeast(1)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        if (entries.isEmpty()) return@Canvas

        val max = entries.maxOf { it.second }.coerceAtLeast(1).toFloat()
        val barMaxWidth = size.width * 0.72f
        val barHeight = 16f
        val rowPx = size.height / entries.size

        entries.forEachIndexed { index, (_, value) ->
            val centerY = index * rowPx + rowPx / 2f
            val y = centerY - barHeight / 2f
            val barWidth = (value / max) * barMaxWidth * animatedProgress
            drawRoundRect(
                color = TextSecondary.copy(alpha = 0.22f),
                topLeft = Offset(0f, y),
                size = Size(barMaxWidth, barHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = colors.getOrElse(index) { AccentGreen },
                topLeft = Offset(0f, y),
                size = Size(barWidth.coerceAtLeast(10f), barHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
        }
    }
}

@Composable
fun CategoryDonutChart(
    entries: List<Pair<String, Long>>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800),
        label = "donut"
    )

    LaunchedEffect(entries) {
        progress = 0f
        progress = 1f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        if (entries.isEmpty()) return@Canvas

        val total = entries.sumOf { it.second }.coerceAtLeast(1).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = minOf(size.width, size.height) * 0.38f
        val innerR = outerR * 0.58f
        var startAngle = -90f

        entries.forEachIndexed { index, (_, value) ->
            val sweep = (value / total) * 360f * animatedProgress
            if (sweep <= 0f) return@forEachIndexed
            val color = colors.getOrElse(index) { AccentGreen }
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - outerR, cy - outerR),
                size = Size(outerR * 2, outerR * 2)
            )
            startAngle += sweep
        }

        // Donut hole
        drawCircle(color = Color(0xFF0E0F12), radius = innerR, center = Offset(cx, cy))

        drawCircle(
            color = TextSecondary.copy(alpha = 0.15f),
            radius = outerR,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )
    }
}
