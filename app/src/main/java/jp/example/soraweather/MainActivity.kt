package jp.example.soraweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoraWeatherApp() }
    }
}

data class WeatherNow(
    val temperature: Double, val feelsLike: Double, val humidity: Int,
    val wind: Double, val code: Int, val precipitation: Double
)
data class HourForecast(val time: String, val temperature: Double, val code: Int, val rain: Double)
data class DayForecast(val date: String, val min: Double, val max: Double, val code: Int, val rain: Double)
data class WeatherData(val now: WeatherNow, val hours: List<HourForecast>, val days: List<DayForecast>)

private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 12000
    connection.setRequestProperty("User-Agent", "SoraWeather/1.0")
    try {
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
}

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

private suspend fun geocode(city: String): Pair<Double, Double> {
    val json = getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=8&language=ja&format=json")
    val results = json.optJSONArray("results") ?: throw Exception("都市が見つかりませんでした")
    if (results.length() == 0) throw Exception("都市が見つかりませんでした")
    val item = results.getJSONObject(0)
    return item.getDouble("latitude") to item.getDouble("longitude")
}

private suspend fun fetchWeather(lat: Double, lon: Double): WeatherData {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
        "&hourly=temperature_2m,precipitation_probability,weather_code" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&forecast_days=7&timezone=auto"
    val j = getJson(url)
    val c = j.getJSONObject("current")
    val h = j.getJSONObject("hourly")
    val d = j.getJSONObject("daily")
    val times = h.getJSONArray("time")
    val temps = h.getJSONArray("temperature_2m")
    val codes = h.getJSONArray("weather_code")
    val rains = h.getJSONArray("precipitation_probability")
    val nowTime = c.getString("time").take(13)
    val start = (0 until times.length()).firstOrNull { times.getString(it).startsWith(nowTime) } ?: 0
    val hours = (start until minOf(start + 12, times.length())).map {
        HourForecast(times.getString(it).substringAfter("T"), temps.getDouble(it),
            codes.getInt(it), rains.optDouble(it, 0.0))
    }
    val dates = d.getJSONArray("time")
    val mins = d.getJSONArray("temperature_2m_min")
    val maxs = d.getJSONArray("temperature_2m_max")
    val dcodes = d.getJSONArray("weather_code")
    val dr = d.getJSONArray("precipitation_probability_max")
    val days = (0 until dates.length()).map {
        DayForecast(dates.getString(it), mins.getDouble(it), maxs.getDouble(it),
            dcodes.getInt(it), dr.optDouble(it, 0.0))
    }
    val now = WeatherNow(c.getDouble("temperature_2m"), c.getDouble("apparent_temperature"),
        c.getInt("relative_humidity_2m"), c.getDouble("wind_speed_10m"),
        c.getInt("weather_code"), c.optDouble("precipitation", 0.0))
    return WeatherData(now, hours, days)
}

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "快晴"; 1 -> "晴れ"; 2 -> "一部曇り"; 3 -> "曇り"
    45, 48 -> "霧"; 51, 53, 55 -> "霧雨"; 56, 57 -> "凍結性の霧雨"
    61, 63, 65 -> "雨"; 66, 67 -> "凍結性の雨"; 71, 73, 75, 77 -> "雪"
    80, 81, 82 -> "にわか雨"; 85, 86 -> "にわか雪"
    95, 96, 99 -> "雷雨"; else -> "不明"
}
private fun weatherEmoji(code: Int): String = when (code) {
    0, 1 -> "☀️"; 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
    in 51..67 -> "🌧️"; in 71..77 -> "❄️"; in 80..82 -> "🌦️"
    in 85..86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "🌡️"
}
private fun dayName(date: String): String = try {
    val d = LocalDate.parse(date)
    val wd = listOf("月", "火", "水", "木", "金", "土", "日")[d.dayOfWeek.value - 1]
    "${d.monthValue}/${d.dayOfMonth} ($wd)"
} catch (_: Exception) { date }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoraWeatherApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf("現在地") }
    var searchText by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf<WeatherData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var wardsOnly by remember { mutableStateOf(false) }
    val tokyoWards = listOf("千代田区", "中央区", "港区", "新宿区", "文京区", "台東区", "墨田区", "江東区", "品川区", "目黒区", "大田区", "世田谷区", "渋谷区", "中野区", "杉並区", "豊島区", "北区", "荒川区", "板橋区", "練馬区", "足立区", "葛飾区", "江戸川区")

    fun load(lat: Double, lon: Double) {
        scope.launch {
            loading = true; error = null
            try { weather = fetchWeather(lat, lon) }
            catch (e: Exception) { error = e.message ?: "天気を取得できませんでした" }
            finally { loading = false }
        }
    }
    fun useLocation() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            val loc: Location? = providers.firstNotNullOfOrNull { p ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager.getLastKnownLocation(p)
                } else null
            }
            if (loc != null) { city = "現在地"; load(loc.latitude, loc.longitude) }
            else { error = "位置情報を取得できません。位置情報をONにするか、都市を検索してください" }
        } catch (_: SecurityException) { error = "位置情報の権限が必要です" }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) useLocation()
        else error = "位置情報の権限が許可されませんでした"
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) useLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB7D7FF), background = Color(0xFF0D1B2A),
        surface = Color(0xFF172B43), onSurface = Color(0xFFF3F7FC)
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF193B63), Color(0xFF0D1B2A))))) {
            Scaffold(containerColor = Color.Transparent, topBar = {
                TopAppBar(
                    title = { Text("Sora Weather", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "都市を検索") }
                        IconButton(onClick = { useLocation() }) { Icon(Icons.Default.LocationOn, "現在地") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "設定") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White, actionIconContentColor = Color.White)
                )
            }) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(city, color = Color(0xFFB9D5F3), fontSize = 16.sp)
                            Text(weather?.let { weatherEmoji(it.now.code) } ?: "🌤️", fontSize = 76.sp)
                            Text(weather?.let { "${it.now.temperature.toInt()}°" } ?: "--°",
                                color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Light)
                            Text(weather?.let { weatherLabel(it.now.code) } ?: "天気を読み込み中",
                                color = Color.White, fontSize = 20.sp)
                            weather?.let { Text("体感 ${it.now.feelsLike.toInt()}°  •  降水量 ${it.now.precipitation} mm",
                                color = Color(0xFFB9D5F3), fontSize = 13.sp) }
                        }
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    error?.let { msg -> item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF663D45))) {
                            Text(msg, Modifier.padding(14.dp), color = Color.White)
                        }
                    } }
                    weather?.let { w ->
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("湿度", "${w.now.humidity}%", "💧", Modifier.weight(1f))
                                MetricCard("風速", "${w.now.wind} km/h", "💨", Modifier.weight(1f))
                                MetricCard("降水量", "${w.now.precipitation} mm", "🌧️", Modifier.weight(1f))
                            }
                        }
                        item { SectionTitle("時間ごとの天気") }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C3654))) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    w.hours.take(8).forEach { h ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                                            Text(h.time, color = Color(0xFFB9D5F3), fontSize = 12.sp)
                                            Text(weatherEmoji(h.code), fontSize = 22.sp)
                                            Text("${h.temperature.toInt()}°", color = Color.White, fontWeight = FontWeight.SemiBold)
                                            Text("${h.rain.toInt()}%", color = Color(0xFF9BCBFF), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        item { SectionTitle("7日間の天気予報") }
                        items(w.days) { day ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C3654))) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dayName(day.date), color = Color.White, modifier = Modifier.weight(1.3f), fontSize = 14.sp)
                                    Text(weatherEmoji(day.code), fontSize = 22.sp, modifier = Modifier.weight(.6f))
                                    Text("${day.min.toInt()}°", color = Color(0xFFB9D5F3), modifier = Modifier.weight(.5f))
                                    Text("${day.max.toInt()}°", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(.5f))
                                    Text("☂ ${day.rain.toInt()}%", color = Color(0xFF9BCBFF), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item {
                        Text("天気データ: Open-Meteo", color = Color(0xFF7895B5), fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("地域選択の設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("地域選択モード")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !wardsOnly, onClick = { wardsOnly = false })
                        Column {
                            Text("全国対応", fontWeight = FontWeight.SemiBold)
                            Text("全国の都道府県・市区町村を検索", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = wardsOnly, onClick = { wardsOnly = true })
                        Column {
                            Text("東京都23区のみ", fontWeight = FontWeight.SemiBold)
                            Text("23区から一覧で選択", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    showSearch = true
                    searchText = ""
                }) { Text("地域を選択") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("閉じる") } }
        )
    }

    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text(if (wardsOnly) "東京23区から選択" else "全国から地域を検索") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (wardsOnly) {
                        Text("東京都の区を選択してください", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(tokyoWards) { ward ->
                                TextButton(onClick = {
                                    searchText = ward
                                    scope.launch {
                                        loading = true; error = null
                                        try {
                                            val (lat, lon) = geocode("$ward 東京都")
                                            city = ward
                                            weather = fetchWeather(lat, lon)
                                            showSearch = false
                                        } catch (e: Exception) { error = e.message ?: "地域を取得できませんでした" }
                                        finally { loading = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(ward, modifier = Modifier.fillMaxWidth()) }
                            }
                        }
                    } else {
                        OutlinedTextField(value = searchText, onValueChange = { searchText = it },
                            label = { Text("都道府県・市区町村・区（例：大阪市北区）") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val query = searchText.trim()
                    if (!wardsOnly && query.isNotEmpty()) scope.launch {
                        loading = true; error = null
                        try {
                            val (lat, lon) = geocode(query)
                            city = query
                            weather = fetchWeather(lat, lon)
                            showSearch = false
                        } catch (e: Exception) { error = e.message ?: "検索に失敗しました" }
                        finally { loading = false }
                    }
                }) { Text("検索") }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1C3654)),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 21.sp)
            Text(title, color = Color(0xFFB9D5F3), fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}
