package io.github.lengsukq.opaldesk.widgets.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class HourlyWeatherPoint(
    val hourLabel: String,
    val tempC: Int,
    val weatherCode: Int,
)

data class DailyWeatherPoint(
    val dayLabel: String,
    val weatherCode: Int,
    val maxC: Int,
    val minC: Int,
)

data class WeatherCityOption(
    val name: String,
    val admin: String,
    val latitude: Double,
    val longitude: Double,
)

data class WeatherSnapshot(
    val cityName: String,
    val temperatureC: Int,
    val apparentTempC: Int,
    val highC: Int,
    val lowC: Int,
    val humidityPercent: Int,
    val windKmh: Int,
    val weatherCode: Int,
    val isDay: Boolean,
    val hourly: List<HourlyWeatherPoint>,
    val daily: List<DailyWeatherPoint>,
    val isLoading: Boolean = false,
    val isPlaceholder: Boolean = false,
)

object WidgetWeatherRepository {
    private const val PREFS_NAME = "opaldesk_widget_weather"
    private const val KEY_CITY_NAME = "city_name"
    private const val KEY_CITY_ADMIN = "city_admin"
    private const val KEY_CITY_LAT = "city_lat"
    private const val KEY_CITY_LON = "city_lon"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private const val HTTP_TIMEOUT_MS = 6_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isFetching = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    @Volatile
    private var lastSuccessMs: Long = 0L

    private val state = MutableStateFlow(defaultPlaceholder(isZh = isSystemZh()))

    fun popularCities(isZh: Boolean): List<WeatherCityOption> = if (isZh) {
        listOf(
            WeatherCityOption("上海", "上海市", 31.2304, 121.4737),
            WeatherCityOption("北京", "北京市", 39.9042, 116.4074),
            WeatherCityOption("深圳", "广东省", 22.5431, 114.0579),
            WeatherCityOption("杭州", "浙江省", 30.2741, 120.1551),
            WeatherCityOption("成都", "四川省", 30.5728, 104.0668),
            WeatherCityOption("东京", "日本", 35.6762, 139.6503),
        )
    } else {
        listOf(
            WeatherCityOption("Shanghai", "China", 31.2304, 121.4737),
            WeatherCityOption("Beijing", "China", 39.9042, 116.4074),
            WeatherCityOption("Shenzhen", "China", 22.5431, 114.0579),
            WeatherCityOption("Tokyo", "Japan", 35.6762, 139.6503),
            WeatherCityOption("San Francisco", "California", 37.7749, -122.4194),
            WeatherCityOption("London", "United Kingdom", 51.5072, -0.1276),
        )
    }

    fun observe(context: Context): StateFlow<WeatherSnapshot> {
        val appCtx = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            val saved = loadSavedCity(appCtx)
            if (saved != null) {
                state.value = state.value.copy(cityName = saved.name)
            }
        }
        val stale = System.currentTimeMillis() - lastSuccessMs >= CACHE_TTL_MS
        if (state.value.isPlaceholder || stale) {
            refresh(appCtx, force = false)
        }
        return state.asStateFlow()
    }

    fun refresh(context: Context, force: Boolean = false) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && !state.value.isPlaceholder && now - lastSuccessMs < CACHE_TTL_MS) {
            return
        }
        if (!isFetching.compareAndSet(false, true)) {
            return
        }
        state.value = state.value.copy(isLoading = true)
        scope.launch {
            try {
                val isZh = isContextZh(appCtx)
                val city = resolveCity(appCtx, isZh)
                val snapshot = fetchOpenMeteoForecast(city, isZh)
                if (snapshot != null) {
                    state.value = snapshot
                    lastSuccessMs = System.currentTimeMillis()
                } else {
                    state.value = state.value.copy(cityName = city.name, isLoading = false)
                }
            } catch (_: Throwable) {
                state.value = state.value.copy(isLoading = false)
            } finally {
                isFetching.set(false)
            }
        }
    }

    fun selectCity(context: Context, city: WeatherCityOption) {
        val appCtx = context.applicationContext
        saveCity(appCtx, city)
        lastSuccessMs = 0L
        state.value = state.value.copy(cityName = city.name, isLoading = true)
        scope.launch {
            isFetching.set(true)
            try {
                val isZh = isContextZh(appCtx)
                val snapshot = fetchOpenMeteoForecast(city, isZh)
                if (snapshot != null) {
                    state.value = snapshot
                    lastSuccessMs = System.currentTimeMillis()
                } else {
                    state.value = state.value.copy(cityName = city.name, isLoading = false)
                }
            } catch (_: Throwable) {
                state.value = state.value.copy(isLoading = false)
            } finally {
                isFetching.set(false)
            }
        }
    }

    suspend fun searchCities(query: String, isZh: Boolean): List<WeatherCityOption> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        runCatching {
            val url = Uri.Builder()
                .scheme("https")
                .authority("geocoding-api.open-meteo.com")
                .appendEncodedPath("v1/search")
                .appendQueryParameter("name", trimmed)
                .appendQueryParameter("count", "8")
                .appendQueryParameter("language", if (isZh) "zh" else "en")
                .appendQueryParameter("format", "json")
                .build()
                .toString()
            val body = httpGet(url) ?: return@runCatching emptyList()
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return@runCatching emptyList()
            val list = ArrayList<WeatherCityOption>(results.length())
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val admin1 = obj.optString("admin1").trim()
                val country = obj.optString("country").trim()
                val admin = listOf(admin1, country).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                list.add(WeatherCityOption(name = name, admin = admin, latitude = lat, longitude = lon))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun describeWeatherCode(code: Int, isZh: Boolean): String = if (isZh) {
        when (code) {
            0 -> "晴朗"
            1 -> "大部晴朗"
            2 -> "多云"
            3 -> "阴天"
            45, 48 -> "有雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻毛雨"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75, 77 -> "大雪"
            80, 81, 82 -> "阵雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷暴冰雹"
            else -> "多云"
        }
    } else {
        when (code) {
            0 -> "Clear"
            1 -> "Mostly Clear"
            2 -> "Partly Cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing Drizzle"
            61 -> "Light Rain"
            63 -> "Rain"
            65 -> "Heavy Rain"
            66, 67 -> "Freezing Rain"
            71 -> "Light Snow"
            73 -> "Snow"
            75, 77 -> "Heavy Snow"
            80, 81, 82 -> "Showers"
            85, 86 -> "Snow Showers"
            95 -> "Thunderstorm"
            96, 99 -> "Severe Storm"
            else -> "Cloudy"
        }
    }

    private fun resolveCity(context: Context, isZh: Boolean): WeatherCityOption {
        loadSavedCity(context)?.let { return it }
        detectCityByIp()?.let { detected ->
            saveCity(context, detected)
            return detected
        }
        return if (isZh) {
            WeatherCityOption("上海", "上海市", 31.2304, 121.4737)
        } else {
            WeatherCityOption("Shanghai", "China", 31.2304, 121.4737)
        }
    }

    private fun detectCityByIp(): WeatherCityOption? {
        runCatching {
            val primaryUrl = Uri.Builder()
                .scheme("https")
                .authority("ipwho.is")
                .path("/")
                .build()
                .toString()
            val body = httpGet(primaryUrl) ?: return@runCatching null
            val json = JSONObject(body)
            if (!json.optBoolean("success", true)) return@runCatching null
            val city = json.optString("city").trim()
            val lat = json.optDouble("latitude", Double.NaN)
            val lon = json.optDouble("longitude", Double.NaN)
            if (city.isNotEmpty() && !lat.isNaN() && !lon.isNaN()) {
                val region = json.optString("region").trim()
                val country = json.optString("country").trim()
                val admin = listOf(region, country).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                return WeatherCityOption(name = city, admin = admin, latitude = lat, longitude = lon)
            }
            null
        }.getOrNull()?.let { return it }

        return runCatching {
            val fallbackUrl = Uri.Builder()
                .scheme("https")
                .authority("get.geojs.io")
                .appendEncodedPath("v1/ip/geo.json")
                .build()
                .toString()
            val body = httpGet(fallbackUrl) ?: return@runCatching null
            val json = JSONObject(body)
            val city = json.optString("city").trim()
            val lat = json.optString("latitude").toDoubleOrNull() ?: Double.NaN
            val lon = json.optString("longitude").toDoubleOrNull() ?: Double.NaN
            if (city.isNotEmpty() && !lat.isNaN() && !lon.isNaN()) {
                val region = json.optString("region").trim()
                val country = json.optString("country").trim()
                val admin = listOf(region, country).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                WeatherCityOption(name = city, admin = admin, latitude = lat, longitude = lon)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun fetchOpenMeteoForecast(city: WeatherCityOption, isZh: Boolean): WeatherSnapshot? {
        val lat = String.format(Locale.US, "%.4f", city.latitude)
        val lon = String.format(Locale.US, "%.4f", city.longitude)
        val url = Uri.Builder()
            .scheme("https")
            .authority("api.open-meteo.com")
            .appendEncodedPath("v1/forecast")
            .appendQueryParameter("latitude", lat)
            .appendQueryParameter("longitude", lon)
            .appendQueryParameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m")
            .appendQueryParameter("hourly", "temperature_2m,weather_code")
            .appendQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
            .appendQueryParameter("timezone", "auto")
            .appendQueryParameter("forecast_days", "5")
            .build()
            .toString()
        val body = httpGet(url) ?: return null
        val root = JSONObject(body)
        val current = root.optJSONObject("current") ?: return null

        val tempC = current.optDouble("temperature_2m", 22.0).roundToInt()
        val apparentC = current.optDouble("apparent_temperature", tempC.toDouble()).roundToInt()
        val humidity = current.optDouble("relative_humidity_2m", 55.0).roundToInt().coerceIn(0, 100)
        val windKmh = current.optDouble("wind_speed_10m", 8.0).roundToInt().coerceAtLeast(0)
        val weatherCode = current.optInt("weather_code", 1)
        val isDay = current.optInt("is_day", 1) == 1
        val currentIso = current.optString("time", "")

        val hourlyPoints = parseHourlyPoints(root.optJSONObject("hourly"), currentIso)
        val dailyPoints = parseDailyPoints(root.optJSONObject("daily"), isZh)
        val highC = dailyPoints.firstOrNull()?.maxC ?: (tempC + 3)
        val lowC = dailyPoints.firstOrNull()?.minC ?: (tempC - 4)

        return WeatherSnapshot(
            cityName = city.name,
            temperatureC = tempC,
            apparentTempC = apparentC,
            highC = highC,
            lowC = lowC,
            humidityPercent = humidity,
            windKmh = windKmh,
            weatherCode = weatherCode,
            isDay = isDay,
            hourly = hourlyPoints,
            daily = dailyPoints,
            isLoading = false,
            isPlaceholder = false,
        )
    }

    private fun parseHourlyPoints(hourlyObj: JSONObject?, currentIso: String): List<HourlyWeatherPoint> {
        if (hourlyObj == null) return emptyList()
        val times = hourlyObj.optJSONArray("time") ?: return emptyList()
        val temps = hourlyObj.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = hourlyObj.optJSONArray("weather_code") ?: return emptyList()
        val count = minOf(times.length(), temps.length(), codes.length())
        if (count == 0) return emptyList()

        val currentHourPrefix = currentIso.take(13)
        var startIdx = 0
        if (currentHourPrefix.length == 13) {
            for (i in 0 until count) {
                val t = times.optString(i)
                if (t.take(13) > currentHourPrefix) {
                    startIdx = i
                    break
                }
            }
        }
        val out = ArrayList<HourlyWeatherPoint>(4)
        for (offset in 0 until 4) {
            val idx = startIdx + offset
            if (idx >= count) break
            val rawTime = times.optString(idx)
            val label = rawTime.substringAfter('T', rawTime).take(5)
            out.add(
                HourlyWeatherPoint(
                    hourLabel = label,
                    tempC = temps.optDouble(idx, 20.0).roundToInt(),
                    weatherCode = codes.optInt(idx, 1),
                ),
            )
        }
        return out
    }

    private fun parseDailyPoints(dailyObj: JSONObject?, isZh: Boolean): List<DailyWeatherPoint> {
        if (dailyObj == null) return emptyList()
        val times = dailyObj.optJSONArray("time") ?: return emptyList()
        val codes = dailyObj.optJSONArray("weather_code") ?: return emptyList()
        val maxs = dailyObj.optJSONArray("temperature_2m_max") ?: return emptyList()
        val mins = dailyObj.optJSONArray("temperature_2m_min") ?: return emptyList()
        val count = minOf(times.length(), codes.length(), maxs.length(), mins.length(), 4)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val out = ArrayList<DailyWeatherPoint>(count)
        for (i in 0 until count) {
            val dateStr = times.optString(i)
            val label = when (i) {
                0 -> if (isZh) "今天" else "Today"
                1 -> if (isZh) "明天" else "Tmrw"
                else -> formatWeekday(sdf, dateStr, isZh)
            }
            out.add(
                DailyWeatherPoint(
                    dayLabel = label,
                    weatherCode = codes.optInt(i, 1),
                    maxC = maxs.optDouble(i, 24.0).roundToInt(),
                    minC = mins.optDouble(i, 16.0).roundToInt(),
                ),
            )
        }
        return out
    }

    private fun formatWeekday(sdf: SimpleDateFormat, dateStr: String, isZh: Boolean): String {
        val date = runCatching { sdf.parse(dateStr) }.getOrNull() ?: return dateStr.takeLast(5)
        val cal = Calendar.getInstance().apply { time = date }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> if (isZh) "周日" else "Sun"
            Calendar.MONDAY -> if (isZh) "周一" else "Mon"
            Calendar.TUESDAY -> if (isZh) "周二" else "Tue"
            Calendar.WEDNESDAY -> if (isZh) "周三" else "Wed"
            Calendar.THURSDAY -> if (isZh) "周四" else "Thu"
            Calendar.FRIDAY -> if (isZh) "周五" else "Fri"
            Calendar.SATURDAY -> if (isZh) "周六" else "Sat"
            else -> dateStr.takeLast(5)
        }
    }

    private fun loadSavedCity(context: Context): WeatherCityOption? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_CITY_NAME, null)?.trim().orEmpty()
        if (name.isEmpty()) return null
        val latStr = prefs.getString(KEY_CITY_LAT, null) ?: return null
        val lonStr = prefs.getString(KEY_CITY_LON, null) ?: return null
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        val admin = prefs.getString(KEY_CITY_ADMIN, "").orEmpty()
        return WeatherCityOption(name = name, admin = admin, latitude = lat, longitude = lon)
    }

    private fun saveCity(context: Context, city: WeatherCityOption) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CITY_NAME, city.name)
            .putString(KEY_CITY_ADMIN, city.admin)
            .putString(KEY_CITY_LAT, city.latitude.toString())
            .putString(KEY_CITY_LON, city.longitude.toString())
            .apply()
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpalDesk/1.0")
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isContextZh(context: Context): Boolean {
        val lang = runCatching {
            context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: Locale.getDefault().language
        return lang.equals("zh", ignoreCase = true)
    }

    private fun isSystemZh(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)

    private fun defaultPlaceholder(isZh: Boolean): WeatherSnapshot = WeatherSnapshot(
        cityName = if (isZh) "上海" else "Shanghai",
        temperatureC = 22,
        apparentTempC = 21,
        highC = 25,
        lowC = 17,
        humidityPercent = 58,
        windKmh = 12,
        weatherCode = 1,
        isDay = true,
        hourly = listOf(
            HourlyWeatherPoint("14:00", 23, 1),
            HourlyWeatherPoint("15:00", 24, 2),
            HourlyWeatherPoint("16:00", 23, 2),
            HourlyWeatherPoint("17:00", 21, 1),
        ),
        daily = listOf(
            DailyWeatherPoint(if (isZh) "今天" else "Today", 1, 25, 17),
            DailyWeatherPoint(if (isZh) "明天" else "Tmrw", 2, 24, 16),
            DailyWeatherPoint(if (isZh) "后天" else "Day 3", 61, 21, 15),
            DailyWeatherPoint(if (isZh) "大后天" else "Day 4", 0, 26, 17),
        ),
        isLoading = false,
        isPlaceholder = true,
    )
}
