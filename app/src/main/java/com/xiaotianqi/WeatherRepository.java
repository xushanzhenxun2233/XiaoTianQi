package com.xiaotianqi;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;

import com.xiaotianqi.WeatherModels.CityOption;
import com.xiaotianqi.WeatherModels.DailyForecast;
import com.xiaotianqi.WeatherModels.WeatherData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class WeatherRepository {

    public static final CityOption DEFAULT_CITY =
            new CityOption("荆州石首市", "湖北", "荆州", "石首", 29.7213, 112.4265);

    public static final CityOption[] PRESET_CITIES = new CityOption[] {
            DEFAULT_CITY,
            new CityOption("沅陵县", "湖南", "怀化", "沅陵", 28.4557, 110.3963),
            new CityOption("武清区", "天津", "天津", "武清", 39.3842, 117.0444),
            new CityOption("通州区", "北京", "北京", "通州", 39.9097, 116.6564)
    };

    private static final String PREFS_NAME = "xiao_weather_prefs_v2";
    private static final String KEY_NAME = "city_name";
    private static final String KEY_PROVINCE = "city_province";
    private static final String KEY_CITY = "city_city";
    private static final String KEY_COUNTY = "city_county";
    private static final String KEY_LAT = "city_lat";
    private static final String KEY_LON = "city_lon";
    private static final String KEY_MANUAL_CITY = "manual_city";
    private static final String KEY_CACHE_NORMALIZED = "cache_normalized_json";
    private static final String KEY_CITY_CACHE_PREFIX = "cache_city_";

    private final Context context;
    private final SharedPreferences prefs;

    public WeatherRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasManualCity() {
        return prefs.getBoolean(KEY_MANUAL_CITY, false);
    }

    public CityOption getSavedCity() {
        String name = prefs.getString(KEY_NAME, DEFAULT_CITY.name);
        String prov = prefs.getString(KEY_PROVINCE, DEFAULT_CITY.province);
        String city = prefs.getString(KEY_CITY, DEFAULT_CITY.city);
        String county = prefs.getString(KEY_COUNTY, DEFAULT_CITY.county);
        double lat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, Double.doubleToLongBits(DEFAULT_CITY.latitude)));
        double lon = Double.longBitsToDouble(prefs.getLong(KEY_LON, Double.doubleToLongBits(DEFAULT_CITY.longitude)));
        return new CityOption(name, prov, city, county, lat, lon);
    }

    public void saveCity(CityOption opt, boolean isManual) {
        prefs.edit()
                .putString(KEY_NAME, opt.name)
                .putString(KEY_PROVINCE, opt.province != null ? opt.province : "")
                .putString(KEY_CITY, opt.city != null ? opt.city : "")
                .putString(KEY_COUNTY, opt.county != null ? opt.county : "")
                .putLong(KEY_LAT, Double.doubleToLongBits(opt.latitude))
                .putLong(KEY_LON, Double.doubleToLongBits(opt.longitude))
                .putBoolean(KEY_MANUAL_CITY, isManual)
                .apply();
    }

    private static String cityCacheKey(CityOption opt) {
        if (opt == null) return KEY_CITY_CACHE_PREFIX + "default";
        String key = (opt.province != null ? opt.province : "") + "_"
                + (opt.city != null ? opt.city : "") + "_"
                + (opt.county != null ? opt.county : "") + "_"
                + (opt.name != null ? opt.name : "");
        return KEY_CITY_CACHE_PREFIX + key;
    }

    /**
     * 读取指定城市的本地多天缓存（若无则回退到最近一次缓存），并自动对齐“今天”的预报
     */
    public WeatherData loadCachedWeather(CityOption opt) {
        String json = null;
        if (opt != null) {
            json = prefs.getString(cityCacheKey(opt), null);
        }
        if (json == null || json.isEmpty()) {
            json = prefs.getString(KEY_CACHE_NORMALIZED, null);
        }
        if (json == null || json.isEmpty()) return null;
        try {
            WeatherData data = deserializeNormalized(json);
            data.isFromCache = true;
            alignCachedDataToToday(data);
            return data;
        } catch (Exception ignored) {
            return null;
        }
    }

    public WeatherData loadCachedWeather() {
        return loadCachedWeather(getSavedCity());
    }

    /**
     * 每次有网时，在后台把所有 4 个预设地点（石首市、沅陵县、武清区、通州区）的天气数据都缓存下来兜底
     */
    public void prefetchAllPresetCities() {
        long now = System.currentTimeMillis();
        for (CityOption preset : PRESET_CITIES) {
            try {
                // 若 30 分钟内已缓存过该预设城市，跳过重复请求
                String existing = prefs.getString(cityCacheKey(preset), null);
                if (existing != null && !existing.isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject(existing);
                        long ts = obj.optLong("fetchedAtMillis", 0L);
                        if ((now - ts) < 30L * 60L * 1000L) {
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                }
                fetchWeatherInternal(preset, false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 优先使用中国大陆直连的腾讯天气（7天完整预报），失败时自动降级到 Open-Meteo（10天预报）
     */
    public WeatherData fetchWeather(CityOption opt) throws Exception {
        return fetchWeatherInternal(opt, true);
    }

    private WeatherData fetchWeatherInternal(CityOption opt, boolean updatePrimaryCache) throws Exception {
        WeatherData data = null;
        Exception primaryError = null;

        if (opt.province != null && !opt.province.isEmpty() && opt.city != null && !opt.city.isEmpty()) {
            try {
                data = fetchFromTencent(opt);
                if (updatePrimaryCache) {
                    AppSettings.setWeatherProvider(context, "腾讯天气（中国大陆直连）");
                }
            } catch (Exception e) {
                primaryError = e;
            }
        }

        if (data == null && (opt.latitude != 0 || opt.longitude != 0)) {
            try {
                data = fetchFromOpenMeteo(opt);
                if (updatePrimaryCache) {
                    AppSettings.setWeatherProvider(context, "Open-Meteo（备用坐标服务）");
                }
            } catch (Exception e) {
                if (primaryError == null) primaryError = e;
            }
        }

        if (data == null) {
            throw (primaryError != null ? primaryError : new Exception("无法获取天气数据"));
        }

        data.isFromCache = false;
        data.isOfflineShifted = false;
        data.isSeverelyExpired = false;
        data.staleHours = 0;
        data.fetchedAtMillis = System.currentTimeMillis();

        // 持久化该城市的完整多日数据，方便断网后连续多天查看
        try {
            String serialized = serializeNormalized(data);
            SharedPreferences.Editor editor = prefs.edit().putString(cityCacheKey(opt), serialized);
            if (updatePrimaryCache) {
                editor.putString(KEY_CACHE_NORMALIZED, serialized);
            }
            editor.apply();
        } catch (Exception ignored) {
        }
        return data;
    }

    /**
     * 腾讯天气中国大陆直连接口（返回实时天气 + 今天及未来共 7 天预报）
     */
    private WeatherData fetchFromTencent(CityOption opt) throws Exception {
        StringBuilder urlSb = new StringBuilder("https://wis.qq.com/weather/common?source=pc&weather_type=observe|forecast_24h");
        urlSb.append("&province=").append(URLEncoder.encode(opt.province, "UTF-8"));
        urlSb.append("&city=").append(URLEncoder.encode(opt.city, "UTF-8"));
        if (opt.county != null && !opt.county.isEmpty()) {
            urlSb.append("&county=").append(URLEncoder.encode(opt.county, "UTF-8"));
        }

        String body = httpGet(urlSb.toString());
        JSONObject root = new JSONObject(body);
        if (root.optInt("status", 0) != 200) {
            throw new Exception("Tencent weather status error");
        }
        JSONObject dataObj = root.getJSONObject("data");
        JSONObject observe = dataObj.optJSONObject("observe");
        JSONObject forecast = dataObj.optJSONObject("forecast_24h");
        if (forecast == null || forecast.length() == 0) {
            throw new Exception("Empty forecast from Tencent");
        }

        WeatherData data = new WeatherData();
        data.cityName = opt.name;
        data.province = opt.province;
        data.city = opt.city;
        data.county = opt.county;
        data.latitude = opt.latitude;
        data.longitude = opt.longitude;
        data.fetchedAtMillis = System.currentTimeMillis();
        data.updateTime = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());

        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        // forecast_24h 包含 "0"（昨天）到 "7"（未来第6天），我们保留今天及之后所有日期（共7天）
        for (int idx = 0; idx <= 10; idx++) {
            JSONObject item = forecast.optJSONObject(String.valueOf(idx));
            if (item == null) continue;
            String dateStr = item.optString("time", "");
            if (dateStr.isEmpty()) continue;
            if (dateStr.compareTo(todayStr) < 0) continue; // 跳过昨天

            DailyForecast df = new DailyForecast();
            df.date = dateStr;
            df.weatherText = item.optString("day_weather", "多云");
            df.nightWeather = item.optString("night_weather", "");
            df.weatherCode = WeatherModels.mapTextToCode(df.weatherText);
            df.maxTemp = parseDoubleSafe(item.optString("max_degree", "0"), 0);
            df.minTemp = parseDoubleSafe(item.optString("min_degree", "0"), 0);
            df.precipProb = -1;
            String windDir = item.optString("day_wind_direction", "");
            String windPow = item.optString("day_wind_power", "");
            df.maxWindSpeed = WeatherModels.parseWindPowerToKmh(windPow);
            df.windDesc = buildWindText(windDir, windPow, df.maxWindSpeed);
            df.uvMax = 0;
            data.dailyList.add(df);
        }

        if (data.dailyList.isEmpty()) {
            throw new Exception("No valid daily forecast");
        }
        assignDayLabels(data.dailyList);

        DailyForecast today = data.dailyList.get(0);
        if (observe != null && observe.has("degree")) {
            data.currentTemp = parseDoubleSafe(observe.optString("degree", ""), (today.maxTemp + today.minTemp) / 2.0);
            data.apparentTemp = data.currentTemp;
            data.humidity = parseIntSafe(observe.optString("humidity", "-1"), -1);
            data.weatherText = observe.optString("weather", today.weatherText);
            data.weatherCode = WeatherModels.mapTextToCode(data.weatherText);
            String wDir = observe.optString("wind_direction_name", "");
            String wPow = observe.optString("wind_power", "");
            data.windSpeed = WeatherModels.parseWindPowerToKmh(wPow);
            data.windDesc = buildWindText(wDir, wPow, data.windSpeed);
        } else {
            data.currentTemp = Math.round((today.maxTemp + today.minTemp) / 2.0);
            data.apparentTemp = data.currentTemp;
            data.humidity = -1;
            data.weatherText = today.weatherText;
            data.weatherCode = today.weatherCode;
            data.windSpeed = today.maxWindSpeed;
            data.windDesc = today.windDesc;
        }

        return data;
    }

    /**
     * 备用引擎：Open-Meteo（按经纬度拉取 10 天预报）
     */
    private WeatherData fetchFromOpenMeteo(CityOption opt) throws Exception {
        String urlStr = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,uv_index_max"
                        + "&timezone=auto&forecast_days=10",
                opt.latitude, opt.longitude);

        String body = httpGet(urlStr);
        JSONObject root = new JSONObject(body);
        JSONObject current = root.getJSONObject("current");
        JSONObject daily = root.getJSONObject("daily");

        WeatherData data = new WeatherData();
        data.cityName = opt.name;
        data.province = opt.province;
        data.city = opt.city;
        data.county = opt.county;
        data.latitude = opt.latitude;
        data.longitude = opt.longitude;
        data.currentTemp = current.optDouble("temperature_2m", 0);
        data.apparentTemp = current.optDouble("apparent_temperature", data.currentTemp);
        data.humidity = current.optInt("relative_humidity_2m", -1);
        data.windSpeed = current.optDouble("wind_speed_10m", 0);
        data.windDesc = WeatherModels.getWindDesc(data.windSpeed);
        data.weatherCode = current.optInt("weather_code", 0);
        data.weatherText = WeatherModels.getWeatherText(data.weatherCode);
        data.fetchedAtMillis = System.currentTimeMillis();
        data.updateTime = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());

        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maxs = daily.getJSONArray("temperature_2m_max");
        JSONArray mins = daily.getJSONArray("temperature_2m_min");
        JSONArray probs = daily.optJSONArray("precipitation_probability_max");
        JSONArray winds = daily.optJSONArray("wind_speed_10m_max");
        JSONArray uvs = daily.optJSONArray("uv_index_max");

        for (int i = 0; i < times.length(); i++) {
            DailyForecast df = new DailyForecast();
            df.date = times.getString(i);
            df.weatherCode = codes.optInt(i, 0);
            df.weatherText = WeatherModels.getWeatherText(df.weatherCode);
            df.maxTemp = maxs.optDouble(i, 0);
            df.minTemp = mins.optDouble(i, 0);
            df.precipProb = probs != null && !probs.isNull(i) ? probs.optInt(i, -1) : -1;
            df.maxWindSpeed = winds != null && !winds.isNull(i) ? winds.optDouble(i, 0) : 0;
            df.windDesc = WeatherModels.getWindDesc(df.maxWindSpeed);
            df.uvMax = uvs != null && !uvs.isNull(i) ? uvs.optDouble(i, 0) : 0;
            data.dailyList.add(df);
        }
        assignDayLabels(data.dailyList);
        return data;
    }

    /**
     * 通过 GPS 经纬度解析中国省/市/县（优先系统 Geocoder）
     */
    public CityOption resolveCityFromCoordinates(double lat, double lon) {
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    String rawProv = cleanAdminName(addr.getAdminArea());
                    String rawCity = cleanAdminName(addr.getLocality());
                    String rawCounty = cleanAdminName(addr.getSubLocality());
                    if (rawCity.isEmpty() && !rawProv.isEmpty()) {
                        rawCity = rawProv;
                    }
                    String display;
                    if (!rawCity.isEmpty() && !rawCounty.isEmpty() && !rawCounty.equals(rawCity)) {
                        display = rawCity + rawCounty;
                    } else if (!rawCounty.isEmpty()) {
                        display = rawCounty;
                    } else if (!rawCity.isEmpty()) {
                        display = rawCity;
                    } else {
                        display = "当前位置";
                    }
                    return new CityOption(display, rawProv, rawCity, rawCounty, lat, lon);
                }
            }
        } catch (Exception ignored) {
        }
        return new CityOption("当前位置", "", "", "", lat, lon);
    }

    /**
     * 中国大陆直连城市搜索（腾讯天气，支持搜索县级市如“石首”、“监利”、“公安”）
     */
    public List<CityOption> searchCities(String query) throws Exception {
        List<CityOption> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return result;

        String encoded = URLEncoder.encode(query.trim(), "UTF-8");
        String urlStr = "https://wis.qq.com/city/like?source=pc&city=" + encoded;
        String body = httpGet(urlStr);
        JSONObject root = new JSONObject(body);
        JSONObject dataObj = root.optJSONObject("data");
        if (dataObj == null) return result;

        Iterator<String> keys = dataObj.keys();
        while (keys.hasNext() && result.size() < 8) {
            String key = keys.next();
            String val = dataObj.optString(key, "");
            if (val.isEmpty()) continue;
            String[] parts = val.split(",");
            String prov = parts.length >= 1 ? parts[0].trim() : "";
            String city = parts.length >= 2 ? parts[1].trim() : prov;
            String county = parts.length >= 3 ? parts[2].trim() : "";
            String displayName;
            if (!county.isEmpty() && !county.equals(city)) {
                displayName = city + county;
            } else {
                displayName = city;
            }
            result.add(new CityOption(displayName, prov, city, county, 0, 0));
        }
        return result;
    }

    /**
     * 断网时将多日缓存按系统当前日期自动对齐到“今天”，并计算数据过期程度
     */
    private void alignCachedDataToToday(WeatherData data) {
        long now = System.currentTimeMillis();
        long ageMs = Math.max(0L, now - data.fetchedAtMillis);
        data.staleHours = (int) (ageMs / (1000L * 60L * 60L));

        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
        String fetchDayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(data.fetchedAtMillis));

        int todayIndex = -1;
        for (int i = 0; i < data.dailyList.size(); i++) {
            if (todayStr.equals(data.dailyList.get(i).date)) {
                todayIndex = i;
                break;
            }
        }

        if (todayIndex > 0) {
            // 已经跨天断网，自动推进到今天的离线预报！
            List<DailyForecast> shifted = new ArrayList<>(data.dailyList.subList(todayIndex, data.dailyList.size()));
            data.dailyList = shifted;
            assignDayLabels(data.dailyList);

            DailyForecast today = data.dailyList.get(0);
            data.currentTemp = Math.round((today.maxTemp + today.minTemp) / 2.0);
            data.apparentTemp = data.currentTemp;
            data.humidity = -1;
            data.weatherText = today.weatherText;
            data.weatherCode = today.weatherCode;
            data.windSpeed = today.maxWindSpeed;
            data.windDesc = today.windDesc;
            data.isOfflineShifted = true;
        } else if (todayIndex == 0) {
            assignDayLabels(data.dailyList);
            data.isOfflineShifted = !todayStr.equals(fetchDayStr);
        } else {
            // 缓存里已经完全不包含今天的日期 -> 严重过期
            assignDayLabels(data.dailyList);
            data.isSeverelyExpired = true;
        }

        // 超过 48 小时未联网更新，判定为严重过期
        if (data.staleHours >= 48) {
            data.isSeverelyExpired = true;
        }
    }

    private void assignDayLabels(List<DailyForecast> list) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String todayStr = sdf.format(new Date());
        Calendar calTmp = Calendar.getInstance();
        calTmp.add(Calendar.DAY_OF_YEAR, 1);
        String tomorrowStr = sdf.format(calTmp.getTime());
        calTmp.add(Calendar.DAY_OF_YEAR, 1);
        String afterTomorrowStr = sdf.format(calTmp.getTime());

        String[] weekNames = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        for (DailyForecast df : list) {
            if (todayStr.equals(df.date)) {
                df.dayLabel = "今天";
            } else if (tomorrowStr.equals(df.date)) {
                df.dayLabel = "明天";
            } else if (afterTomorrowStr.equals(df.date)) {
                df.dayLabel = "后天";
            } else {
                try {
                    Date d = sdf.parse(df.date);
                    Calendar c = Calendar.getInstance();
                    c.setTime(d);
                    df.dayLabel = weekNames[c.get(Calendar.DAY_OF_WEEK)];
                } catch (Exception e) {
                    df.dayLabel = df.date.length() >= 5 ? df.date.substring(5) : df.date;
                }
            }
        }
    }

    private String serializeNormalized(WeatherData data) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("cityName", data.cityName);
        obj.put("province", data.province != null ? data.province : "");
        obj.put("city", data.city != null ? data.city : "");
        obj.put("county", data.county != null ? data.county : "");
        obj.put("latitude", data.latitude);
        obj.put("longitude", data.longitude);
        obj.put("currentTemp", data.currentTemp);
        obj.put("apparentTemp", data.apparentTemp);
        obj.put("humidity", data.humidity);
        obj.put("windSpeed", data.windSpeed);
        obj.put("windDesc", data.windDesc != null ? data.windDesc : "");
        obj.put("weatherText", data.weatherText != null ? data.weatherText : "");
        obj.put("weatherCode", data.weatherCode);
        obj.put("updateTime", data.updateTime != null ? data.updateTime : "");
        obj.put("fetchedAtMillis", data.fetchedAtMillis);

        JSONArray arr = new JSONArray();
        for (DailyForecast df : data.dailyList) {
            JSONObject d = new JSONObject();
            d.put("date", df.date);
            d.put("weatherText", df.weatherText != null ? df.weatherText : "");
            d.put("nightWeather", df.nightWeather != null ? df.nightWeather : "");
            d.put("weatherCode", df.weatherCode);
            d.put("maxTemp", df.maxTemp);
            d.put("minTemp", df.minTemp);
            d.put("precipProb", df.precipProb);
            d.put("maxWindSpeed", df.maxWindSpeed);
            d.put("windDesc", df.windDesc != null ? df.windDesc : "");
            d.put("uvMax", df.uvMax);
            arr.put(d);
        }
        obj.put("dailyList", arr);
        return obj.toString();
    }

    private WeatherData deserializeNormalized(String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        WeatherData data = new WeatherData();
        data.cityName = obj.optString("cityName", DEFAULT_CITY.name);
        data.province = obj.optString("province", "");
        data.city = obj.optString("city", "");
        data.county = obj.optString("county", "");
        data.latitude = obj.optDouble("latitude", DEFAULT_CITY.latitude);
        data.longitude = obj.optDouble("longitude", DEFAULT_CITY.longitude);
        data.currentTemp = obj.optDouble("currentTemp", 0);
        data.apparentTemp = obj.optDouble("apparentTemp", data.currentTemp);
        data.humidity = obj.optInt("humidity", -1);
        data.windSpeed = obj.optDouble("windSpeed", 0);
        data.windDesc = obj.optString("windDesc", "");
        data.weatherText = obj.optString("weatherText", "多云");
        data.weatherCode = obj.optInt("weatherCode", 2);
        data.updateTime = obj.optString("updateTime", "");
        data.fetchedAtMillis = obj.optLong("fetchedAtMillis", 0L);

        JSONArray arr = obj.optJSONArray("dailyList");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i);
                DailyForecast df = new DailyForecast();
                df.date = d.optString("date", "");
                df.weatherText = d.optString("weatherText", "多云");
                df.nightWeather = d.optString("nightWeather", "");
                df.weatherCode = d.optInt("weatherCode", 2);
                df.maxTemp = d.optDouble("maxTemp", 0);
                df.minTemp = d.optDouble("minTemp", 0);
                df.precipProb = d.optInt("precipProb", -1);
                df.maxWindSpeed = d.optDouble("maxWindSpeed", 0);
                df.windDesc = d.optString("windDesc", "");
                df.uvMax = d.optDouble("uvMax", 0);
                data.dailyList.add(df);
            }
        }
        return data;
    }

    private static String cleanAdminName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.endsWith("省") || s.endsWith("市")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String buildWindText(String dir, String pow, double kmh) {
        String d = dir != null ? dir.trim() : "";
        String p = pow != null ? pow.trim() : "";
        if (!d.isEmpty() && !p.isEmpty()) {
            return d + " " + p + (p.endsWith("级") ? "" : "级");
        }
        if (!p.isEmpty()) {
            return p + (p.endsWith("级") ? "" : "级风");
        }
        return WeatherModels.getWindDesc(kmh);
    }

    private static double parseDoubleSafe(String s, double defVal) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return defVal;
        }
    }

    private static int parseIntSafe(String s, int defVal) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return defVal;
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 XiaoTianQi/1.1");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code);
            }
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
