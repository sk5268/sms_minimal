package com.example.finance

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.formatRupees
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

// Modern, harmonious, eye-easing color palette for dark-mode fintech
val CategoryChartPalette = listOf(
    Color(0xFF6366F1), // Soft Indigo / Iris
    Color(0xFF10B981), // Emerald / Mint Green
    Color(0xFFF59E0B), // Warm Amber / Honey
    Color(0xFFF43F5E), // Coral Rose
    Color(0xFF8B5CF6), // Purple / Violet
    Color(0xFF06B6D4), // Cyan / Ocean
    Color(0xFFFB923C), // Warm Tangerine
    Color(0xFF38BDF8), // Sky Blue
    Color(0xFFEC4899), // Soft Magenta / Pink
    Color(0xFF14B8A6), // Teal
    Color(0xFFA855F7), // Orchid
    Color(0xFF64748B)  // Slate Grey
)

fun pickNextCategoryColor(existingCategories: List<CategoryEntity>): Int {
    val paletteColors = CategoryChartPalette.map { it.toArgb() }
    val existingColors = existingCategories.map { it.colorArgb }.toSet()
    return paletteColors.firstOrNull { it !in existingColors }
        ?: paletteColors[existingCategories.size % paletteColors.size]
}

// Maps legacy high-contrast neon colors to modern eye-pleasing tones
private val LegacyColorMigrationMap = mapOf(
    0xFFFF9F0A.toInt() to Color(0xFFFB923C), // Warm Tangerine
    0xFF4086FF.toInt() to Color(0xFF6366F1), // Soft Indigo
    0xFF32D74B.toInt() to Color(0xFF10B981), // Emerald
    0xFFFF453A.toInt() to Color(0xFFF43F5E), // Coral Rose
    0xFF00E5FF.toInt() to Color(0xFF06B6D4), // Ocean Cyan
    0xFFBF5AF2.toInt() to Color(0xFF8B5CF6), // Soft Violet
    0xFFFFD60A.toInt() to Color(0xFFF59E0B), // Warm Amber
    0xFFFF375F.toInt() to Color(0xFFEC4899), // Soft Pink
    0xFF64D2FF.toInt() to Color(0xFF38BDF8), // Sky Blue
    0xFF8A8A93.toInt() to Color(0xFF64748B), // Slate Grey
    0xFF6B7280.toInt() to Color(0xFF64748B), // Slate Grey
    0xFFE040FB.toInt() to Color(0xFFA855F7)  // Soft Orchid
)

fun modernCategoryColor(cat: CategoryEntity?, fallbackIndex: Int = 0): Color {
    if (cat == null) return CategoryChartPalette[fallbackIndex % CategoryChartPalette.size]
    val legacyMatch = LegacyColorMigrationMap[cat.colorArgb]
    if (legacyMatch != null) return legacyMatch
    if (cat.colorArgb != 0) return Color(cat.colorArgb)
    return CategoryChartPalette[(cat.id % CategoryChartPalette.size).toInt()]
}

fun resolveCategoryColors(
    categoryIds: List<Long>,
    categoryMap: Map<Long, CategoryEntity>
): List<Color> {
    val usedRgb = mutableSetOf<Int>()
    return categoryIds.mapIndexed { index, categoryId ->
        val cat = categoryMap[categoryId]
        var color = modernCategoryColor(cat, index)
        if ((color.toArgb() and 0x00FFFFFF) in usedRgb) {
            var offset = 1
            while ((color.toArgb() and 0x00FFFFFF) in usedRgb && offset <= CategoryChartPalette.size) {
                color = CategoryChartPalette[(index + offset) % CategoryChartPalette.size]
                offset++
            }
        }
        usedRgb.add(color.toArgb() and 0x00FFFFFF)
        color
    }
}

data class CategoryChartSlice(
    val categoryId: Long = 0L,
    val name: String,
    val amount: Long,
    val color: Color
)

data class CategorySeries(
    val categoryId: Long = 0L,
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
        animationSpec = tween(900, easing = FastOutSlowInEasing),
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
        val gridColor = TextSecondary.copy(alpha = 0.10f)
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
                    colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
                    startY = y,
                    endY = padTop + chartH
                )
            )
            drawLine(
                color = lineColor.copy(alpha = 0.18f),
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
            drawCircle(lineColor.copy(alpha = 0.28f), radius = 8f, center = points.first())
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
            color = lineColor.copy(alpha = 0.15f),
            style = Stroke(width = 10f, cap = StrokeCap.Round)
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
        points.forEach { p ->
            drawCircle(lineColor.copy(alpha = 0.28f), radius = 5f, center = p)
            drawCircle(lineColor, radius = 3f, center = p)
        }
        drawContext.canvas.restore()
    }
}

@Composable
fun CategoryMultiLineChart(
    series: List<CategorySeries>,
    dayStarts: List<Long>,
    selectedCategoryId: Long? = null,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "category_multi_line"
    )

    val animationKey = remember(series, dayStarts) {
        buildString {
            append(dayStarts.firstOrNull() ?: 0L)
            append('-')
            append(dayStarts.lastOrNull() ?: 0L)
            series.forEach { s ->
                append('|')
                append(s.categoryId)
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
        val gridColor = TextSecondary.copy(alpha = 0.10f)
        val labelPaint = Paint().apply {
            color = TextSecondary.copy(alpha = 0.85f).toArgb()
            textSize = 28f
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

        val isAnySelected = selectedCategoryId != null && selectedCategoryId > 0L

        series.forEach { categorySeries ->
            val isSelected = isAnySelected && categorySeries.categoryId == selectedCategoryId
            val alpha = if (isAnySelected && !isSelected) 0.18f else 1.0f
            val lineWidth = if (isSelected) 3.5f else 2f
            val glowWidth = if (isSelected) 10f else 6f

            val points = categorySeries.points.map { (day, amount) ->
                Offset(dayToX(day), amountToY(amount))
            }
            if (points.size <= 1) {
                points.forEach { p ->
                    drawCircle(categorySeries.color.copy(alpha = 0.28f * alpha), radius = 5f, center = p)
                    drawCircle(categorySeries.color.copy(alpha = alpha), radius = 3f, center = p)
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
                color = categorySeries.color.copy(alpha = 0.14f * alpha),
                style = Stroke(width = glowWidth, cap = StrokeCap.Round)
            )
            drawPath(
                path = linePath,
                color = categorySeries.color.copy(alpha = alpha),
                style = Stroke(width = lineWidth, cap = StrokeCap.Round)
            )
            points.forEach { p ->
                drawCircle(categorySeries.color.copy(alpha = 0.28f * alpha), radius = 4f, center = p)
                drawCircle(categorySeries.color.copy(alpha = alpha), radius = if (isSelected) 3.5f else 2.5f, center = p)
            }
        }

        drawContext.canvas.restore()
    }
}

@Composable
fun CategoryDonutChart(
    slices: List<CategoryChartSlice>,
    selectedCategoryId: Long? = null,
    onSliceClick: ((Long) -> Unit)? = null,
    centerSubtitle: String? = null,
    centerAmount: String? = null,
    centerTitle: String? = null,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "donut"
    )

    LaunchedEffect(slices) {
        progress = 0f
        progress = 1f
    }

    Box(
        modifier = modifier.height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, onSliceClick) {
                    if (onSliceClick != null) {
                        detectTapGestures { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                            val outerR = minOf(size.width, size.height) * 0.42f
                            val ringWidth = outerR * 0.28f
                            val innerR = outerR - ringWidth

                            if (distance >= innerR * 0.7f && distance <= outerR * 1.25f) {
                                val touchAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                val normalizedAngle = (touchAngle + 90f + 360f) % 360f

                                var currentAngle = 0f
                                val total = slices.sumOf { it.amount }.coerceAtLeast(1).toFloat()
                                for (slice in slices) {
                                    val sweep = (slice.amount / total) * 360f
                                    if (normalizedAngle >= currentAngle && normalizedAngle < currentAngle + sweep) {
                                        onSliceClick(slice.categoryId)
                                        return@detectTapGestures
                                    }
                                    currentAngle += sweep
                                }
                            } else if (distance < innerR * 0.7f) {
                                onSliceClick(-1L)
                            }
                        }
                    }
                }
        ) {
            if (slices.isEmpty()) return@Canvas

            val total = slices.sumOf { it.amount }.coerceAtLeast(1).toFloat()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = minOf(size.width, size.height) * 0.42f
            val baseRingWidth = outerR * 0.28f
            val arcRadius = outerR - baseRingWidth / 2f
            val segmentGap = if (slices.size > 1) 2.5f else 0f
            var startAngle = -90f

            // Sleek subtle background track
            drawCircle(
                color = DarkSurfaceElevated.copy(alpha = 0.5f),
                radius = arcRadius,
                center = Offset(cx, cy),
                style = Stroke(width = baseRingWidth, cap = StrokeCap.Butt)
            )

            val isAnySelected = selectedCategoryId != null && selectedCategoryId > 0L

            slices.forEach { slice ->
                val sweep = (slice.amount / total) * 360f * animatedProgress
                if (sweep <= segmentGap) return@forEach

                val isSelected = isAnySelected && slice.categoryId == selectedCategoryId
                val ringWidth = if (isSelected) baseRingWidth + 4.dp.toPx() else baseRingWidth
                val sliceArcRadius = if (isSelected) arcRadius + 2.dp.toPx() else arcRadius
                val sliceAlpha = if (isAnySelected && !isSelected) 0.28f else 1.0f

                val effectiveSweep = (sweep - segmentGap).coerceAtLeast(0.5f)
                val effectiveStart = startAngle + segmentGap / 2f

                drawArc(
                    color = slice.color.copy(alpha = sliceAlpha),
                    startAngle = effectiveStart,
                    sweepAngle = effectiveSweep,
                    useCenter = false,
                    topLeft = Offset(cx - sliceArcRadius, cy - sliceArcRadius),
                    size = Size(sliceArcRadius * 2f, sliceArcRadius * 2f),
                    style = Stroke(width = ringWidth, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }

        // Center interactive display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .padding(4.dp)
        ) {
            if (!centerSubtitle.isNullOrBlank()) {
                Text(
                    text = centerSubtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            if (!centerAmount.isNullOrBlank()) {
                Text(
                    text = centerAmount,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            if (!centerTitle.isNullOrBlank()) {
                Text(
                    text = centerTitle,
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

