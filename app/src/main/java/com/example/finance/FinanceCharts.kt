package com.example.finance

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.formatRupees
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val CategoryChartPalette = listOf(
    Color(0xFFFF9F0A),
    Color(0xFF4086FF),
    Color(0xFF32D74B),
    Color(0xFFFF453A),
    Color(0xFF00E5FF),
    Color(0xFFBF5AF2),
    Color(0xFFFFD60A),
    Color(0xFFFF375F),
    Color(0xFF64D2FF),
    Color(0xFF8A8A93)
)

fun resolveCategoryColors(
    categoryIds: List<Long>,
    categoryMap: Map<Long, CategoryEntity>
): List<Color> {
    val usedRgb = mutableSetOf<Int>()
    return categoryIds.map { categoryId ->
        val cat = categoryMap[categoryId]
        var color = if (cat != null && cat.colorArgb != 0) {
            Color(cat.colorArgb)
        } else {
            CategoryChartPalette[(categoryId % CategoryChartPalette.size).toInt()]
        }
        if ((color.toArgb() and 0x00FFFFFF) in usedRgb) {
            var offset = 1
            while ((color.toArgb() and 0x00FFFFFF) in usedRgb && offset <= CategoryChartPalette.size) {
                color = CategoryChartPalette[(categoryId.toInt() + offset) % CategoryChartPalette.size]
                offset++
            }
        }
        usedRgb.add(color.toArgb() and 0x00FFFFFF)
        color
    }
}

data class CategoryChartSlice(
    val name: String,
    val amount: Long,
    val color: Color
)

data class CategorySeries(
    val name: String,
    val color: Color,
    val points: List<Pair<Long, Long>>
)

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
fun CategoryMultiLineChart(
    series: List<CategorySeries>,
    dayStarts: List<Long>,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800),
        label = "category_multi_line"
    )

    val animationKey = remember(series, dayStarts) {
        buildString {
            append(dayStarts.firstOrNull() ?: 0L)
            append('-')
            append(dayStarts.lastOrNull() ?: 0L)
            series.forEach { s ->
                append('|')
                append(s.name)
                append(':')
                append(s.points.sumOf { it.second })
            }
        }
    }

    LaunchedEffect(animationKey) {
        progress = 0f
        progress = 1f
    }

    val dateLabelFormat = remember { SimpleDateFormat("d MMM", Locale.US) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        if (series.isEmpty() || dayStarts.isEmpty()) return@Canvas

        val padLeft = 52f
        val padRight = 12f
        val padTop = 10f
        val padBottom = 28f
        val chartW = size.width - padLeft - padRight
        val chartH = size.height - padTop - padBottom
        val gridColor = TextSecondary.copy(alpha = 0.12f)
        val labelPaint = Paint().apply {
            color = TextSecondary.copy(alpha = 0.85f).toArgb()
            textSize = 22f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val maxAmount = series
            .flatMap { it.points }
            .maxOfOrNull { it.second }
            ?.coerceAtLeast(1)
            ?.toFloat()
            ?: 1f

        for (i in 0..3) {
            val gy = padTop + chartH * i / 3f
            drawLine(gridColor, Offset(padLeft, gy), Offset(size.width - padRight, gy), 1f)
        }

        val yLabelAmounts = listOf(maxAmount, maxAmount / 2f, 0f)
        yLabelAmounts.forEachIndexed { index, value ->
            val gy = padTop + chartH * index / 2f
            val label = formatRupees(value.toLong())
            drawContext.canvas.nativeCanvas.drawText(
                label,
                4f,
                gy + 7f,
                labelPaint
            )
        }

        val dayCount = dayStarts.size
        val stepX = if (dayCount <= 1) chartW else chartW / (dayCount - 1)
        val dayIndex = dayStarts.withIndex().associate { it.value to it.index }

        fun dayToX(dayStart: Long): Float {
            val index = dayIndex[dayStart] ?: return padLeft
            return if (dayCount <= 1) padLeft + chartW / 2f else padLeft + index * stepX
        }

        fun amountToY(amount: Long): Float {
            return padTop + chartH - (amount / maxAmount) * chartH * 0.9f
        }

        val xLabelIndices = when {
            dayCount <= 1 -> listOf(0)
            dayCount == 2 -> listOf(0, 1)
            else -> listOf(0, dayCount / 2, dayCount - 1)
        }
        xLabelIndices.forEach { index ->
            val x = if (dayCount <= 1) padLeft + chartW / 2f else padLeft + index * stepX
            val label = dateLabelFormat.format(Date(dayStarts[index]))
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x - 24f,
                size.height - 6f,
                labelPaint
            )
        }

        val clipW = padLeft + chartW * animatedProgress
        drawContext.canvas.save()
        drawContext.canvas.clipRect(0f, 0f, clipW, size.height)

        series.forEach { categorySeries ->
            val points = categorySeries.points.map { (day, amount) ->
                Offset(dayToX(day), amountToY(amount))
            }
            if (points.size <= 1) {
                points.forEach { p ->
                    drawCircle(categorySeries.color.copy(alpha = 0.35f), radius = 5f, center = p)
                    drawCircle(categorySeries.color, radius = 3f, center = p)
                }
                return@forEach
            }

            val linePath = Path().apply {
                points.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = linePath,
                color = categorySeries.color.copy(alpha = 0.18f),
                style = Stroke(width = 7f, cap = StrokeCap.Round)
            )
            drawPath(
                path = linePath,
                color = categorySeries.color,
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )
            points.forEach { p ->
                drawCircle(categorySeries.color.copy(alpha = 0.35f), radius = 4f, center = p)
                drawCircle(categorySeries.color, radius = 2.5f, center = p)
            }
        }

        drawContext.canvas.restore()
    }
}

@Composable
fun CategoryDonutChart(
    slices: List<CategoryChartSlice>,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800),
        label = "donut"
    )

    LaunchedEffect(slices) {
        progress = 0f
        progress = 1f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        if (slices.isEmpty()) return@Canvas

        val total = slices.sumOf { it.amount }.coerceAtLeast(1).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = minOf(size.width, size.height) * 0.38f
        val innerR = outerR * 0.58f
        val ringWidth = outerR - innerR
        val arcRadius = innerR + ringWidth / 2f
        val segmentGap = 1.2f
        var startAngle = -90f

        slices.forEach { slice ->
            val sweep = (slice.amount / total) * 360f * animatedProgress
            if (sweep <= segmentGap) return@forEach
            drawArc(
                color = slice.color,
                startAngle = startAngle + segmentGap / 2f,
                sweepAngle = sweep - segmentGap,
                useCenter = false,
                topLeft = Offset(cx - arcRadius, cy - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = ringWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }

        drawCircle(
            color = TextSecondary.copy(alpha = 0.15f),
            radius = outerR,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )
    }
}
