package io.github.lengsukq.opaldesk.widgets.renderers

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.widgets.data.WeatherSnapshot
import io.github.lengsukq.opaldesk.widgets.data.WidgetWeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// Hitokoto (每日一言) Repository with 12 Curated Bilingual Fallbacks & Cache
// ---------------------------------------------------------------------------

internal data class HitokotoQuote(
    val text: String,
    val source: String,
)

internal object HitokotoRepository {
    private const val PREF_NAME = "opaldesk_hitokoto_cache"
    private const val KEY_TEXT = "quote_text"
    private const val KEY_SOURCE = "quote_source"
    private const val ENDPOINT = "https://v1.hitokoto.cn/?encode=json"
    private const val TIMEOUT_MS = 5_000

    private val zhFallbacks = listOf(
        HitokotoQuote("保持热爱，奔赴下一场山海。", "山海寄语"),
        HitokotoQuote("凡是过往，皆为序章。", "莎士比亚"),
        HitokotoQuote("慢下来，才能听见时间落下的声音。", "静思录"),
        HitokotoQuote("心有微光，不惧长夜漫漫。", "星夜集"),
        HitokotoQuote("专注当下这一刻，便是最高效的远行。", "效率手记"),
        HitokotoQuote("愿你眼里有光，手里有风，脚下有路。", "清风集"),
    )

    private val enFallbacks = listOf(
        HitokotoQuote("Simplicity is the ultimate sophistication.", "Leonardo da Vinci"),
        HitokotoQuote("Focus is a matter of deciding what things you're not going to do.", "John Carmack"),
        HitokotoQuote("Stay hungry, stay foolish.", "Steve Jobs"),
        HitokotoQuote("Small daily improvements over time lead to stunning results.", "Robin Sharma"),
        HitokotoQuote("Clarity precedes mastery.", "Robin Sharma"),
        HitokotoQuote("Make each day your masterpiece.", "John Wooden"),
    )

    private var fallbackCursor = 0

    fun loadInitial(context: Context, isZh: Boolean): HitokotoQuote {
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        val cachedText = prefs?.getString(KEY_TEXT, null)?.trim().orEmpty()
        val cachedSrc = prefs?.getString(KEY_SOURCE, null)?.trim().orEmpty()
        if (cachedText.isNotBlank()) {
            return HitokotoQuote(cachedText, cachedSrc.ifBlank { if (isZh) "一言" else "Hitokoto" })
        }
        return nextFallback(isZh)
    }

    fun nextFallback(isZh: Boolean): HitokotoQuote {
        val pool = if (isZh) zhFallbacks else enFallbacks
        val item = pool[fallbackCursor % pool.size]
        fallbackCursor = (fallbackCursor + 1) % pool.size
        return item
    }

    suspend fun fetchFresh(context: Context, isZh: Boolean): HitokotoQuote = withContext(Dispatchers.IO) {
        val remote = runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpalDesk/1.0")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val text = json.optString("hitokoto").trim()
                    val fromWho = json.optString("from_who").trim().takeIf { it.isNotEmpty() && it != "null" }
                    val fromWork = json.optString("from").trim().takeIf { it.isNotEmpty() && it != "null" }
                    val src = listOfNotNull(fromWho, fromWork).distinct().joinToString(" · ")
                        .ifBlank { if (isZh) "一言" else "Hitokoto" }
                    if (text.isNotBlank()) HitokotoQuote(text, src) else null
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        if (remote != null) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TEXT, remote.text)
                    .putString(KEY_SOURCE, remote.source)
                    .apply()
            }
            remote
        } else {
            nextFallback(isZh)
        }
    }
}

@Composable
internal fun HitokotoGlassCard(
    quote: HitokotoQuote,
    accent: Color,
    compact: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(14.dp)
    val fill = Brush.horizontalGradient(
        listOf(
            if (colors.isDark) Color(0x22FFFFFF) else Color(0x78FFFFFF),
            if (colors.isDark) Color(0x12FFFFFF) else Color(0x46FFFFFF),
        ),
    )
    val rim = Brush.linearGradient(
        listOf(
            colors.glassRimTop.copy(alpha = if (colors.isDark) 0.34f else 0.85f),
            colors.borderHairline,
        ),
    )

    Crossfade(
        targetState = quote,
        animationSpec = tween(260),
        label = "hitokoto_fade",
        modifier = modifier,
    ) { q ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(0.8.dp, rim, shape)
                .clickable(onClick = onRefresh)
                .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(if (compact) 18.dp else 28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(listOf(accent, colors.accentCyan.copy(alpha = 0.45f))),
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    text = "“${q.text}”",
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = colors.textPrimary.copy(alpha = 0.88f),
                        fontSize = if (compact) 10.5.sp else 11.5.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        lineHeight = if (compact) 14.sp else 16.sp,
                    ),
                )
                if (!compact) {
                    OpalText(
                        text = "—— ${q.source}",
                        color = colors.textMuted,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Unified Live Weather Card for Desktop Hub Overview Page (WidgetWeatherRepository)
// ---------------------------------------------------------------------------

@Composable
internal fun HubWeatherMiniCard(
    weather: WeatherSnapshot,
    isCompact: Boolean,
    isZh: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(16.dp)
    val accent = when (weather.weatherCode) {
        0, 1 -> if (weather.isDay) colors.accentAmber else Color(0xFF8B7CFF)
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99 -> colors.accentBlue
        else -> colors.accentCyan
    }
    val fill = Brush.verticalGradient(
        listOf(
            if (colors.isDark) Color(0x24FFFFFF) else Color(0x80FFFFFF),
            if (colors.isDark) Color(0x10FFFFFF) else Color(0x4CFFFFFF),
        ),
    )
    val rim = Brush.linearGradient(
        listOf(
            colors.glassRimTop.copy(alpha = if (colors.isDark) 0.38f else 0.90f),
            accent.copy(alpha = 0.28f),
        ),
    )
    val cityLabel = weather.cityName.ifBlank { if (isZh) "本地天气" else "Local" }
    val condLabel = WidgetWeatherRepository.describeWeatherCode(weather.weatherCode, isZh)

    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(0.85.dp, rim, shape)
            .clickable(onClick = onRefresh)
            .padding(horizontal = if (isCompact) 9.dp else 12.dp, vertical = if (isCompact) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 7.dp else 10.dp),
    ) {
        WeatherMiniGlyph(
            weatherCode = weather.weatherCode,
            isDay = weather.isDay,
            modifier = Modifier.size(if (isCompact) 28.dp else 36.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BasicText(
                    text = "${weather.temperatureC}°",
                    maxLines = 1,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = if (isCompact) 20.sp else 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
                OpalText(
                    text = condLabel,
                    color = colors.textSecondary,
                    fontSize = if (isCompact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = cityLabel,
                    color = colors.textMuted,
                    fontSize = if (isCompact) 9.5.sp else 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                OpalText(
                    text = "${weather.highC}°/${weather.lowC}°",
                    color = accent,
                    fontSize = if (isCompact) 9.5.sp else 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
