package io.github.lengsukq.opaldesk.widgets.renderers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetInstance
import io.github.lengsukq.opaldesk.widgets.WidgetSizeClass
import io.github.lengsukq.opaldesk.widgets.data.DailyWeatherPoint
import io.github.lengsukq.opaldesk.widgets.data.HourlyWeatherPoint
import io.github.lengsukq.opaldesk.widgets.data.WeatherCityOption
import io.github.lengsukq.opaldesk.widgets.data.WeatherSnapshot
import io.github.lengsukq.opaldesk.widgets.data.WidgetWeatherRepository
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WeatherWidgetContent(
    instance: DesktopWidgetInstance,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val weatherFlow = remember(ctx) { WidgetWeatherRepository.observe(ctx) }
    val snapshot by weatherFlow.collectAsState()
    var showCityPicker by remember(instance.id) { mutableStateOf(false) }
    var showDaily by remember(instance.id) { mutableStateOf(false) }

    val atmosphere = remember(snapshot.weatherCode, snapshot.isDay) {
        weatherAtmosphereAccent(snapshot.weatherCode, snapshot.isDay)
    }

    Box(modifier = modifier.fillMaxSize()) {
        WidgetAmbientGlow(
            accent = atmosphere,
            centerFractionX = 0.84f,
            centerFractionY = 0.16f,
            radiusFraction = 0.82f,
        )
        WidgetAmbientGlow(
            accent = if (snapshot.isDay) Color(0xFF38BDF8) else Color(0xFF6366F1),
            centerFractionX = 0.14f,
            centerFractionY = 0.88f,
            radiusFraction = 0.56f,
            alpha = 0.11f,
        )

        if (showCityPicker) {
            WeatherCityPickerOverlay(
                isZh = isZh,
                currentCity = snapshot.cityName,
                accent = atmosphere,
                onSelectCity = { city ->
                    WidgetWeatherRepository.selectCity(ctx, city)
                    showCityPicker = false
                },
                onClose = { showCityPicker = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            WeatherMainView(
                snapshot = snapshot,
                sizeClass = sizeClass,
                isZh = isZh,
                accent = atmosphere,
                showDaily = showDaily,
                onToggleForecastMode = { showDaily = !showDaily },
                onOpenCityPicker = { showCityPicker = true },
                onRefresh = { WidgetWeatherRepository.refresh(ctx, force = true) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WeatherMainView(
    snapshot: WeatherSnapshot,
    sizeClass: WidgetSizeClass,
    isZh: Boolean,
    accent: Color,
    showDaily: Boolean,
    onToggleForecastMode: () -> Unit,
    onOpenCityPicker: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val conditionText = WidgetWeatherRepository.describeWeatherCode(snapshot.weatherCode, isZh)
    val isCompact = sizeClass == WidgetSizeClass.COMPACT
    val isExpanded = sizeClass == WidgetSizeClass.EXPANDED

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top bar: tappable city pill + forecast toggle + refresh button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (colors.isDark) 0.20f else 0.13f))
                    .border(0.8.dp, accent.copy(alpha = if (colors.isDark) 0.44f else 0.30f), CircleShape)
                    .clickable(onClick = onOpenCityPicker)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                OpalText(
                    text = snapshot.cityName,
                    color = colors.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                OpalText(
                    text = "▾",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!isCompact) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (colors.isDark) Color(0x20FFFFFF) else Color(0x66FFFFFF))
                            .border(0.75.dp, colors.borderSubtle, CircleShape)
                            .clickable(onClick = onToggleForecastMode)
                            .padding(horizontal = 8.dp, vertical = 3.5.dp),
                    ) {
                        OpalText(
                            text = if (showDaily) {
                                if (isZh) "4天" else "4D"
                            } else {
                                if (isZh) "逐时" else "4H"
                            },
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                WidgetGlassActionButton(
                    onClick = onRefresh,
                    size = 26.dp,
                    accent = accent,
                    active = snapshot.isLoading,
                    contentDescription = if (isZh) "刷新天气" else "Refresh weather",
                ) { tint ->
                    WidgetGlyphIcon(WidgetGlyph.RESET, tint = tint, size = 12.dp)
                }
            }
        }

        // Center hero: temperature + condition + hand-drawn weather icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp),
            ) {
                WeatherMiniGlyph(
                    weatherCode = snapshot.weatherCode,
                    isDay = snapshot.isDay,
                    modifier = Modifier.size(if (isCompact) 36.dp else if (isExpanded) 52.dp else 44.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        OpalText(
                            text = "${snapshot.temperatureC}°",
                            color = colors.textPrimary,
                            fontSize = if (isCompact) 28.sp else if (isExpanded) 42.sp else 34.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                        )
                    }
                    OpalText(
                        text = if (snapshot.isLoading) {
                            if (isZh) "更新中…" else "Updating…"
                        } else {
                            conditionText
                        },
                        color = colors.textSecondary,
                        fontSize = if (isCompact) 10.5.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }

            if (!isCompact) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    WeatherMetricCapsule(
                        label = if (isZh) "高/低" else "H/L",
                        value = "${snapshot.highC}° / ${snapshot.lowC}°",
                        accent = accent,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        WeatherMetricCapsule(
                            label = if (isZh) "体感" else "Feels",
                            value = "${snapshot.apparentTempC}°",
                        )
                        WeatherMetricCapsule(
                            label = if (isZh) "湿度" else "Hum",
                            value = "${snapshot.humidityPercent}%",
                        )
                        if (isExpanded) {
                            WeatherMetricCapsule(
                                label = if (isZh) "风速" else "Wind",
                                value = "${snapshot.windKmh}km/h",
                            )
                        }
                    }
                }
            }
        }

        if (isCompact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeatherMetricCapsule(
                    label = "H/L",
                    value = "${snapshot.highC}°/${snapshot.lowC}°",
                    accent = accent,
                )
                WeatherMetricCapsule(
                    label = if (isZh) "湿" else "H",
                    value = "${snapshot.humidityPercent}%",
                )
            }
        } else {
            // Bottom forecast strip (4-hour or 4-day)
            WidgetInnerPanel(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (!showDaily && snapshot.hourly.isNotEmpty()) {
                    HourlyForecastStrip(
                        points = snapshot.hourly.take(4),
                        isDay = snapshot.isDay,
                        accent = accent,
                    )
                } else if (snapshot.daily.isNotEmpty()) {
                    DailyForecastStrip(
                        points = snapshot.daily.take(4),
                        accent = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherMetricCapsule(
    label: String,
    value: String,
    accent: Color? = null,
) {
    val colors = LocalOpalColors.current
    val bg = if (accent != null) {
        accent.copy(alpha = if (colors.isDark) 0.16f else 0.11f)
    } else if (colors.isDark) {
        Color(0x1CFFFFFF)
    } else {
        Color(0x5CFFFFFF)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.7.dp, colors.borderHairline, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        OpalText(
            text = label,
            color = colors.textMuted,
            fontSize = 9.5.sp,
            maxLines = 1,
        )
        OpalText(
            text = value,
            color = accent ?: colors.textPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HourlyForecastStrip(
    points: List<HourlyWeatherPoint>,
    isDay: Boolean,
    accent: Color,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        points.forEach { pt ->
            val hourNum = pt.hourLabel.take(2).toIntOrNull()
            val pointIsDay = if (hourNum != null) hourNum in 6..18 else isDay
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OpalText(
                    text = pt.hourLabel,
                    color = colors.textMuted,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                )
                WeatherMiniGlyph(
                    weatherCode = pt.weatherCode,
                    isDay = pointIsDay,
                    modifier = Modifier.size(18.dp),
                )
                OpalText(
                    text = "${pt.tempC}°",
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DailyForecastStrip(
    points: List<DailyWeatherPoint>,
    accent: Color,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        points.forEachIndexed { idx, pt ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OpalText(
                    text = pt.dayLabel,
                    color = if (idx == 0) accent else colors.textMuted,
                    fontSize = 9.5.sp,
                    fontWeight = if (idx == 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                WeatherMiniGlyph(
                    weatherCode = pt.weatherCode,
                    isDay = true,
                    modifier = Modifier.size(18.dp),
                )
                OpalText(
                    text = "${pt.maxC}°/${pt.minC}°",
                    color = colors.textPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun WeatherCityPickerOverlay(
    isZh: Boolean,
    currentCity: String,
    accent: Color,
    onSelectCity: (WeatherCityOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherCityOption>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val popular = remember(isZh) { WidgetWeatherRepository.popularCities(isZh) }

    LaunchedEffect(query, isZh) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(320L)
        results = WidgetWeatherRepository.searchCities(q, isZh)
        searching = false
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalTextInput(
                value = query,
                onValueChange = { query = it },
                placeholder = if (isZh) "搜索城市…" else "Search city…",
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.8.dp, colors.borderSubtle, CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                OpalGlyphIcon(glyph = ChromeGlyph.CLOSE, tint = colors.textPrimary, size = 11.dp)
            }
        }

        if (query.trim().isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                popular.forEach { city ->
                    val active = city.name.equals(currentCity, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) accent.copy(alpha = 0.24f) else OpalPalette.ChipFill)
                            .border(
                                0.8.dp,
                                if (active) accent.copy(alpha = 0.60f) else colors.borderHairline,
                                CircleShape,
                            )
                            .clickable { onSelectCity(city) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        OpalText(
                            text = city.name,
                            color = if (active) accent else colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    searching -> OpalText(
                        text = if (isZh) "正在搜索城市…" else "Searching cities…",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    results.isEmpty() -> OpalText(
                        text = if (isZh) "未找到匹配城市" else "No matching cities",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    else -> results.forEach { city ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (colors.isDark) Color(0x1CFFFFFF) else Color(0x64FFFFFF))
                                .border(0.7.dp, colors.borderHairline, RoundedCornerShape(10.dp))
                                .clickable { onSelectCity(city) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = city.name,
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (city.admin.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                OpalText(
                                    text = city.admin,
                                    color = colors.textMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherMiniGlyph(
    weatherCode: Int,
    isDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val sunGold = Color(0xFFFFC145)
    val sunWarm = Color(0xFFFF9F0A)
    val moonSilver = Color(0xFFE2E8F0)
    val moonGlow = Color(0xFF93C5FD)
    val cloudLight = Color(0xFFF1F5F9)
    val cloudMid = Color(0xFFCBD5E1)
    val rainBlue = Color(0xFF38BDF8)
    val snowIce = Color(0xFFE0F2FE)
    val boltGold = Color(0xFFFACC15)

    Canvas(modifier = modifier) {
        when (weatherCode) {
            0, 1 -> if (isDay) {
                drawSunGlyph(Offset(size.width * 0.5f, size.height * 0.5f), size.minDimension * 0.24f, sunGold, sunWarm)
            } else {
                drawMoonGlyph(Offset(size.width * 0.50f, size.height * 0.50f), size.minDimension * 0.30f, moonSilver, moonGlow)
            }
            2 -> {
                if (isDay) {
                    drawSunGlyph(Offset(size.width * 0.36f, size.height * 0.34f), size.minDimension * 0.17f, sunGold, sunWarm)
                } else {
                    drawMoonGlyph(Offset(size.width * 0.34f, size.height * 0.34f), size.minDimension * 0.18f, moonSilver, moonGlow)
                }
                drawCloudShape(size.width * 0.20f, size.height * 0.42f, size.width * 0.68f, size.height * 0.36f, cloudLight, cloudMid)
            }
            3 -> drawCloudShape(size.width * 0.14f, size.height * 0.30f, size.width * 0.72f, size.height * 0.42f, cloudLight, cloudMid)
            45, 48 -> {
                drawCloudShape(size.width * 0.16f, size.height * 0.20f, size.width * 0.68f, size.height * 0.36f, cloudLight, cloudMid)
                val sw = (size.minDimension * 0.065f).coerceAtLeast(1.3.dp.toPx())
                drawLine(cloudMid.copy(alpha = 0.85f), Offset(size.width * 0.20f, size.height * 0.68f), Offset(size.width * 0.80f, size.height * 0.68f), sw, StrokeCap.Round)
                drawLine(cloudMid.copy(alpha = 0.65f), Offset(size.width * 0.28f, size.height * 0.81f), Offset(size.width * 0.72f, size.height * 0.81f), sw, StrokeCap.Round)
            }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> {
                drawCloudShape(size.width * 0.16f, size.height * 0.18f, size.width * 0.68f, size.height * 0.38f, cloudLight, cloudMid)
                val sw = (size.minDimension * 0.065f).coerceAtLeast(1.3.dp.toPx())
                listOf(0.32f, 0.50f, 0.68f).forEachIndexed { idx, fx ->
                    val yTop = size.height * if (idx == 1) 0.64f else 0.60f
                    val yBot = size.height * if (idx == 1) 0.86f else 0.80f
                    drawLine(rainBlue, Offset(size.width * fx, yTop), Offset(size.width * (fx - 0.06f), yBot), sw, StrokeCap.Round)
                }
            }
            71, 73, 75, 77, 85, 86 -> {
                drawCloudShape(size.width * 0.16f, size.height * 0.18f, size.width * 0.68f, size.height * 0.38f, cloudLight, cloudMid)
                val r = (size.minDimension * 0.055f).coerceAtLeast(1.4.dp.toPx())
                drawCircle(snowIce, radius = r, center = Offset(size.width * 0.32f, size.height * 0.72f))
                drawCircle(snowIce, radius = r * 1.1f, center = Offset(size.width * 0.50f, size.height * 0.80f))
                drawCircle(snowIce, radius = r, center = Offset(size.width * 0.68f, size.height * 0.72f))
            }
            95, 96, 99 -> {
                drawCloudShape(size.width * 0.15f, size.height * 0.16f, size.width * 0.70f, size.height * 0.38f, cloudMid, Color(0xFF94A3B8))
                val bolt = Path().apply {
                    moveTo(size.width * 0.53f, size.height * 0.54f)
                    lineTo(size.width * 0.41f, size.height * 0.72f)
                    lineTo(size.width * 0.51f, size.height * 0.72f)
                    lineTo(size.width * 0.44f, size.height * 0.90f)
                    lineTo(size.width * 0.61f, size.height * 0.68f)
                    lineTo(size.width * 0.50f, size.height * 0.68f)
                    close()
                }
                drawPath(bolt, boltGold)
            }
            else -> drawCloudShape(size.width * 0.15f, size.height * 0.30f, size.width * 0.70f, size.height * 0.40f, cloudLight, cloudMid)
        }
    }
}

private fun DrawScope.drawSunGlyph(center: Offset, radius: Float, core: Color, ray: Color) {
    drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.45f), Color.Transparent), center, radius * 2f), radius * 2f, center)
    val rayWidth = (radius * 0.24f).coerceAtLeast(1.2.dp.toPx())
    for (i in 0 until 8) {
        val angle = i * (PI / 4.0)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        drawLine(
            ray,
            Offset(center.x + cosA * radius * 1.32f, center.y + sinA * radius * 1.32f),
            Offset(center.x + cosA * radius * 1.78f, center.y + sinA * radius * 1.78f),
            rayWidth,
            StrokeCap.Round,
        )
    }
    drawCircle(Brush.verticalGradient(listOf(core, ray)), radius, center)
}

private fun DrawScope.drawMoonGlyph(center: Offset, radius: Float, moonColor: Color, starColor: Color) {
    drawCircle(Brush.radialGradient(listOf(starColor.copy(alpha = 0.34f), Color.Transparent), center, radius * 1.9f), radius * 1.9f, center)
    val sw = (radius * 0.38f).coerceAtLeast(2.dp.toPx())
    drawArc(
        color = moonColor,
        startAngle = 36f,
        sweepAngle = 252f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawCircle(starColor, radius = radius * 0.14f, center = Offset(center.x + radius * 0.72f, center.y - radius * 0.65f))
    drawCircle(starColor.copy(alpha = 0.7f), radius = radius * 0.10f, center = Offset(center.x + radius * 1.05f, center.y - radius * 0.18f))
}

private fun DrawScope.drawCloudShape(left: Float, top: Float, width: Float, height: Float, topColor: Color, bottomColor: Color) {
    val brush = Brush.verticalGradient(listOf(topColor, bottomColor), startY = top, endY = top + height)
    drawCircle(brush, radius = height * 0.42f, center = Offset(left + width * 0.34f, top + height * 0.48f))
    drawCircle(brush, radius = height * 0.50f, center = Offset(left + width * 0.58f, top + height * 0.40f))
    drawRoundRect(brush, Offset(left + width * 0.08f, top + height * 0.46f), Size(width * 0.84f, height * 0.54f), CornerRadius(height * 0.34f, height * 0.34f))
}

private fun weatherAtmosphereAccent(weatherCode: Int, isDay: Boolean): Color = when {
    weatherCode in listOf(95, 96, 99) -> Color(0xFF8B5CF6)
    weatherCode in listOf(71, 73, 75, 77, 85, 86) -> Color(0xFF93C5FD)
    weatherCode in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82) -> Color(0xFF0EA5E9)
    weatherCode in listOf(0, 1) && isDay -> Color(0xFFFFB020)
    !isDay -> Color(0xFF6366F1)
    else -> Color(0xFF38BDF8)
}
