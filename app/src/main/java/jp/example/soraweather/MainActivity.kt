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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate

private val Ink = Color(0xFFF4F8FF)
private val Muted = Color(0xFFC5D8F1)
private val Glass = Color(0x33204479)
private val Line = Color(0x33FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoraWeatherApp() }
    }
}

data class WeatherNow(val temperature: Double, val feelsLike: Double, val humidity: Int, val wind: Double, val code: Int, val precipitation: Double)
data class HourForecast(val time: String, val temperature: Double, val code: Int, val rain: Double)
data class DayForecast(val date: String, val min: Double, val max: Double, val code: Int, val rain: Double)
data class WeatherData(val now: WeatherNow, val hours: List<HourForecast>, val days: List<DayForecast>)

private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = 15000
    try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
}
private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
private suspend fun geocode(name: String): Pair<Double, Double> {
    val j = getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=10&language=ja&format=json")
    val a = j.optJSONArray("results") ?: throw Exception("地域が見つかりませんでした")
    if (a.length() == 0) throw Exception("地域が見つかりませんでした")
    val x = a.getJSONObject(0)
    return x.getDouble("latitude") to x.getDouble("longitude")
}
private suspend fun fetchWeather(lat: Double, lon: Double): WeatherData {
    val u = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7&timezone=auto"
    val j = getJson(u); val c = j.getJSONObject("current"); val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
    val ts=h.getJSONArray("time"); val te=h.getJSONArray("temperature_2m"); val co=h.getJSONArray("weather_code"); val ra=h.getJSONArray("precipitation_probability")
    val now=c.getString("time").take(13); val start=(0 until ts.length()).firstOrNull { ts.getString(it).startsWith(now) } ?: 0
    val hours=(start until minOf(start+24,ts.length())).map { HourForecast(ts.getString(it).substringAfter("T"),te.getDouble(it),co.getInt(it),ra.optDouble(it,0.0)) }
    val dates=d.getJSONArray("time"); val mn=d.getJSONArray("temperature_2m_min"); val mx=d.getJSONArray("temperature_2m_max"); val dc=d.getJSONArray("weather_code"); val dr=d.getJSONArray("precipitation_probability_max")
    val days=(0 until dates.length()).map { DayForecast(dates.getString(it),mn.getDouble(it),mx.getDouble(it),dc.getInt(it),dr.optDouble(it,0.0)) }
    return WeatherData(WeatherNow(c.getDouble("temperature_2m"),c.getDouble("apparent_temperature"),c.getInt("relative_humidity_2m"),c.getDouble("wind_speed_10m"),c.getInt("weather_code"),c.optDouble("precipitation",0.0)),hours,days)
}
private fun label(code:Int)=when(code){0->"快晴";1->"晴れ";2->"晴れ時々くもり";3->"くもり";45,48->"霧";51,53,55->"霧雨";61,63,65,80,81,82->"雨";71,73,75,77,85,86->"雪";95,96,99->"雷雨";else->"くもり"}
private fun emoji(code:Int)=when(code){0,1->"☀️";2->"🌤️";3->"☁️";45,48->"🌫️";in 51..67->"🌧️";in 71..77->"❄️";in 80..82->"🌦️";in 85..86->"🌨️";in 95..99->"⛈️";else->"🌡️"}
private fun dayLabel(s:String):String=try { val d=LocalDate.parse(s); listOf("月","火","水","木","金","土","日")[d.dayOfWeek.value-1].let { if(d==LocalDate.now()) "今日" else if(d==LocalDate.now().plusDays(1)) "明日" else "${d.monthValue}/${d.dayOfMonth} ($it)" } } catch(_:Exception){s}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoraWeatherApp() {
    val ctx= LocalContext.current; val scope= rememberCoroutineScope()
    var city by remember { mutableStateOf("現在地") }; var query by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<WeatherData?>(null) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }; var choosing by remember { mutableStateOf(false) }; var wards by remember { mutableStateOf(false) }
    val wardList=listOf("千代田区","中央区","港区","新宿区","文京区","台東区","墨田区","江東区","品川区","目黒区","大田区","世田谷区","渋谷区","中野区","杉並区","豊島区","北区","荒川区","板橋区","練馬区","足立区","葛飾区","江戸川区")
    fun load(lat:Double,lon:Double){ scope.launch { busy=true;error=null;try{data=fetchWeather(lat,lon)}catch(e:Exception){error=e.message?: "天気を取得できません"}finally{busy=false} } }
    fun locate(){
        try {
            val m=ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val allowed=ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val loc:Location?=if(allowed) listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).firstNotNullOfOrNull { try{m.getLastKnownLocation(it)}catch(_:Exception){null} } else null
            if(loc!=null){city="現在地";load(loc.latitude,loc.longitude)}else error="位置情報を取得できません。設定から権限を許可してください"
        }catch(_:Exception){error="位置情報を取得できません"}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ if(it.values.any{v->v})locate() else error="位置情報の権限が必要です" }
    LaunchedEffect(Unit){ val ok=ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; if(ok)locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF183A78),Color(0xFF285FA8),Color(0xFF78B9E8))))) {
        val wide=maxWidth>=700.dp
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal=if(wide) 26.dp else 18.dp)) {
            Row(Modifier.fillMaxWidth().height(58.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("SORA WEATHER",color=Ink.copy(alpha=.75f),fontSize=13.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick={choosing=true}){Icon(Icons.Default.Search,null,tint=Ink)}
                IconButton(onClick={locate}){Icon(Icons.Default.LocationOn,null,tint=Ink)}
                IconButton(onClick={settings=true}){Icon(Icons.Default.Settings,null,tint=Ink)}
            }
            if(wide) {
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.weight(.92f).fillMaxHeight(),contentAlignment=Alignment.CenterStart){ CurrentWeather(city,data,busy,error) }
                    Column(Modifier.weight(1.08f).fillMaxHeight().padding(bottom=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        data?.let { ForecastCard(it.days.take(5),Modifier.weight(1.05f).fillMaxWidth()); HourCard(it.hours,Modifier.weight(.95f).fillMaxWidth()); MetricsRow(it,Modifier.fillMaxWidth()) } ?: EmptyPanel(busy,error)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    item { CurrentWeather(city,data,busy,error) }
                    data?.let { w ->
                        item { ForecastCard(w.days.take(5),Modifier.fillMaxWidth()) }
                        item { HourCard(w.hours,Modifier.fillMaxWidth()) }
                        item { MetricsRow(w,Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
    if(settings) AlertDialog(onDismissRequest={settings=false},title={Text("設定")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("地域選択モード")
            Row(verticalAlignment=Alignment.CenterVertically){RadioButton(!wards,{wards=false});Column{Text("全国対応");Text("全国の地域を検索",style=MaterialTheme.typography.bodySmall)}}
            Row(verticalAlignment=Alignment.CenterVertically){RadioButton(wards,{wards=true});Column{Text("東京都23区のみ");Text("23区から一覧で選択",style=MaterialTheme.typography.bodySmall)}}
        }
    },confirmButton={TextButton(onClick={settings=false;choosing=true;query=""}){Text("地域を選択")}},dismissButton={TextButton(onClick={settings=false}){Text("閉じる")}})
    if(choosing) AlertDialog(onDismissRequest={choosing=false},title={Text(if(wards)"東京都23区" else "地域を検索")},text={
        Column {
            if(wards) LazyColumn(Modifier.heightIn(max=420.dp)){items(wardList){w->TextButton(onClick={scope.launch{busy=true;error=null;try{val p=geocode("$w 東京都");data=fetchWeather(p.first,p.second);city=w;choosing=false}catch(e:Exception){error=e.message?: "取得に失敗"}finally{busy=false}}},Modifier.fillMaxWidth()){Text(w,Modifier.fillMaxWidth())}}}
            else OutlinedTextField(query,{query=it},label={Text("都道府県・市区町村・区")},placeholder={Text("例：東京都足立区、大阪市北区")},singleLine=true)
        }
    },confirmButton={if(!wards)TextButton(onClick={scope.launch{if(query.isNotBlank()){busy=true;error=null;try{val p=geocode(query);data=fetchWeather(p.first,p.second);city=query;choosing=false}catch(e:Exception){error=e.message?: "地域が見つかりません"}finally{busy=false}}}}){Text("検索")}},dismissButton={TextButton(onClick={choosing=false}){Text("閉じる")}})
}

@Composable
private fun CurrentWeather(city:String,w:WeatherData?,busy:Boolean,error:String?) {
    Column(Modifier.fillMaxWidth().padding(vertical=if(city.isBlank())0.dp else 18.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(city,color=Ink,fontSize=if(city.length>12)20.sp else 24.sp,fontWeight=FontWeight.Medium)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(w?.let{it.now.temperature.toInt().toString()}?:"--",color=Ink,fontSize=if(city.isEmpty())88.sp else 112.sp,fontWeight=FontWeight.ExtraLight,lineHeight=112.sp)
            Text("°",color=Ink.copy(alpha=.9f),fontSize=54.sp,fontWeight=FontWeight.Light)
        }
        Text(w?.let{label(it.now.code)}?:if(busy)"天気を取得中…" else "天気情報",color=Ink,fontSize=20.sp,fontWeight=FontWeight.Medium)
        w?.let { Text("最高 ${it.days.firstOrNull()?.max?.toInt()?: "--"}°  /  最低 ${it.days.firstOrNull()?.min?.toInt()?: "--"}°",color=Muted,fontSize=16.sp) }
        Spacer(Modifier.height(8.dp))
        if(w!=null) Surface(color=Color(0x337EB4F0),shape=CircleShape){Text("✦  大気情報は取得可能な地域で表示",Modifier.padding(horizontal=13.dp,vertical=7.dp),color=Ink.copy(alpha=.88f),fontSize=12.sp)}
        if(error!=null) Text(error,color=Color(0xFFFFD4D4),fontSize=13.sp)
    }
}
@Composable
private fun GlassCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Card(modifier,shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Glass),elevation=CardDefaults.cardElevation(0.dp)){Column(Modifier.fillMaxSize().padding(18.dp),content=content)}
}
@Composable
private fun ForecastCard(days:List<DayForecast>,modifier:Modifier=Modifier){
    GlassCard(modifier){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("5日間予報",color=Muted,fontWeight=FontWeight.SemiBold,fontSize=14.sp);Spacer(Modifier.weight(1f));Text("天気予報",color=Muted.copy(alpha=.7f),fontSize=12.sp)}
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed{index,d->Row(Modifier.fillMaxWidth().height(43.dp),verticalAlignment=Alignment.CenterVertically){
            Text(dayLabel(d.date),Modifier.weight(1f),color=Ink,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text("風 --",Modifier.weight(.85f),color=Muted,fontSize=12.sp)
            Text(emoji(d.code),Modifier.weight(.45f),fontSize=20.sp)
            Text("${d.min.toInt()}°",color=Muted,fontSize=13.sp)
            Box(Modifier.weight(1f).padding(horizontal=8.dp).height(6.dp).clip(CircleShape).background(Color(0x334B78B6))){Box(Modifier.fillMaxWidth((d.max-d.min).let{(it/25.0).coerceIn(.2,1.0).toFloat()}).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(Color(0xFFB8E7A0),Color(0xFFFFD16D)))))}
            Text("${d.max.toInt()}°",color=Ink,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        };if(index<days.lastIndex)HorizontalDivider(color=Line)}
    }
}
@Composable
private fun HourCard(hours:List<HourForecast>,modifier:Modifier=Modifier){
    GlassCard(modifier){Text("24時間予報",color=Muted,fontSize=14.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(12.dp))
        val shown=hours.take(12)
        if(shown.isNotEmpty()){
            val min=shown.minOf{it.temperature};val max=shown.maxOf{it.temperature}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(0.dp)){
                shown.forEach{h->Column(Modifier.width(68.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text("${h.temperature.toInt()}°",color=Ink,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                    Spacer(Modifier.height(4.dp));Text(emoji(h.code),fontSize=20.sp)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.height(36.dp).fillMaxWidth(),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){val y=34f-((h.temperature-min)/(max-min).takeIf{it>0}?:1.0).toFloat()*25f;drawCircle(Color(0xFFFFE36A),4f,Offset(size.width/2,y+1))}}
                    Text("${h.time.take(5)}",color=Muted,fontSize=11.sp);Text("${h.rain.toInt()}%",color=Muted.copy(alpha=.75f),fontSize=10.sp)
                }}
            }
        }
    }
}
@Composable
private fun MetricsRow(w:WeatherData,modifier:Modifier=Modifier){
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Metric("湿度","${w.now.humidity}%","💧",Modifier.weight(1f))
        Metric("体感温度","${w.now.feelsLike.toInt()}°","🌡️",Modifier.weight(1f))
        Metric("風速","${w.now.wind.toInt()} km/h","〰️",Modifier.weight(1f))
    }
}
@Composable
private fun Metric(title:String,value:String,icon:String,modifier:Modifier){
    Card(modifier.height(124.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Color(0x33204479)),elevation=CardDefaults.cardElevation(0.dp)){
        Column(Modifier.fillMaxSize().padding(13.dp),verticalArrangement=Arrangement.SpaceBetween){
            Text(title,color=Muted,fontSize=12.sp);Text(value,color=Ink,fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text(icon,color=Ink,fontSize=25.sp)
        }
    }
}
@Composable
private fun EmptyPanel(busy:Boolean,error:String?){
    GlassCard(Modifier.fillMaxWidth().weight(1f)){Text(if(busy)"天気を取得しています…" else "天気情報を表示できません",color=Ink);error?.let{Text(it,color=Muted)}}
}
