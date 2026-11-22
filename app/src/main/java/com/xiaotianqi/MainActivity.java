package com.xiaotianqi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xiaotianqi.WeatherModels.CityOption;
import com.xiaotianqi.WeatherModels.DailyForecast;
import com.xiaotianqi.WeatherModels.LaborAdvice;
import com.xiaotianqi.WeatherModels.WeatherData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TtsManager.Callback, LocationListener {

    private static final int REQ_LOCATION = 1001;
    private static final long GPS_TIMEOUT_MS = 8000L;

    private static class NetStateCallback extends ConnectivityManager.NetworkCallback {
        private final MainActivity host;
        NetStateCallback(MainActivity host) {
            this.host = host;
        }
        @Override
        public void onAvailable(Network network) {
            host.onNetworkStateChanged(true);
        }
        @Override
        public void onLost(Network network) {
            host.onNetworkStateChanged(false);
        }
    }

    private LinearLayout layoutTopHero;
    private TextView tvCity;
    private TextView btnToggleTts;
    private TextView btnRefresh;
    private ImageView btnSettings;
    private TextView tvCurrentTemp;
    private TextView tvWeatherLine;
    private TextView tvLunarDate;
    private LinearLayout layoutExtremeAlert;
    private TextView tvExtremeAlert;
    private LinearLayout layoutStaleBanner;
    private TextView tvStaleText;
    private TextView btnOpenData;
    private LinearLayout layoutForecastList;
    private TextView tvLaborVerdict;
    private TextView tvLaborSummary;
    private LinearLayout layoutPrecautions;
    private LinearLayout layoutTtsBanner;
    private TextView tvTtsBannerText;

    private WeatherRepository repository;
    private TtsManager ttsManager;
    private LocationManager locationManager;
    private ConnectivityManager connectivityManager;
    private NetStateCallback netStateCallback;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WeatherData currentWeather;
    private LaborAdvice currentAdvice;
    private boolean hasAutoSpoken = false;
    private boolean ttsPausedTemporarily = false; // 默认开启播报，顶部可暂时关闭
    private boolean hasShownTtsDialog = false;
    private boolean hasShownExpiredDialog = false;
    private boolean waitingForGps = false;
    private boolean simulatedOffline = false;
    private float appliedFontScale = AppSettings.FONT_LARGE;
    private String appliedApiId = AppSettings.API_TENCENT;
    private String appliedCustomApiUrl = "";
    private int flashStep = 0;

    private final Runnable extremeFlashTask = this::stepExtremeFlash;

    private void stepExtremeFlash() {
        if (isFinishing() || isDestroyed() || layoutExtremeAlert == null) return;
        if (flashStep >= 20) {
            // 闪烁满 5 秒（20 * 250ms）后稳定停留在高对比度红底白字
            layoutExtremeAlert.setBackgroundColor(0xFFC62828);
            tvExtremeAlert.setTextColor(0xFFFFFFFF);
            return;
        }
        if (flashStep % 2 == 0) {
            layoutExtremeAlert.setBackgroundColor(0xFFFFD600);
            tvExtremeAlert.setTextColor(0xFF111111);
        } else {
            layoutExtremeAlert.setBackgroundColor(0xFFC62828);
            tvExtremeAlert.setTextColor(0xFFFFFFFF);
        }
        flashStep++;
        mainHandler.postDelayed(extremeFlashTask, 250L);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppSettings.wrapContext(newBase));
    }

    private final Runnable gpsTimeoutTask = () -> {
        if (!waitingForGps) return;
        stopGpsListening();
        Location fallbackLoc = getBestLastKnownLocation();
        if (fallbackLoc != null) {
            resolveAndLoadLocation(fallbackLoc.getLatitude(), fallbackLoc.getLongitude());
        } else {
            CityOption fallbackCity = repository.getSavedCity();
            loadWeatherForCity(fallbackCity, true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedFontScale = AppSettings.getFontScale(this);
        appliedApiId = AppSettings.getSelectedApiId(this);
        appliedCustomApiUrl = AppSettings.getCustomApiUrl(this);
        setContentView(R.layout.activity_main);

        bindViews();
        tvLunarDate.setText(LunarCalendarUtil.getTodayLunarDisplay());

        repository = new WeatherRepository(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
        ttsManager = new TtsManager(this, this);

        // 先根据当前联网状态更新顶部地区是否可切换（断网置灰且禁止切换）
        updateCitySwitchState(isNetworkConnected());

        // 先展示本地多天离线缓存（自动对齐到今天）
        WeatherData cached = repository.loadCachedWeather();
        if (cached != null) {
            renderWeather(cached);
        }

        // 若未手动固定城市，先请求 GPS 定位授权；无权限默认展示荆州石首市
        if (!repository.hasManualCity()) {
            requestLocationOrFallback();
        } else {
            loadWeatherForCity(repository.getSavedCity(), true);
        }
    }

    private void bindViews() {
        layoutTopHero = findViewById(R.id.layoutTopHero);
        tvCity = findViewById(R.id.tvCity);
        btnToggleTts = findViewById(R.id.btnToggleTts);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        tvCurrentTemp = findViewById(R.id.tvCurrentTemp);
        tvWeatherLine = findViewById(R.id.tvWeatherLine);
        tvLunarDate = findViewById(R.id.tvLunarDate);
        layoutExtremeAlert = findViewById(R.id.layoutExtremeAlert);
        tvExtremeAlert = findViewById(R.id.tvExtremeAlert);
        layoutStaleBanner = findViewById(R.id.layoutStaleBanner);
        tvStaleText = findViewById(R.id.tvStaleText);
        btnOpenData = findViewById(R.id.btnOpenData);
        layoutForecastList = findViewById(R.id.layoutForecastList);
        tvLaborVerdict = findViewById(R.id.tvLaborVerdict);
        tvLaborSummary = findViewById(R.id.tvLaborSummary);
        layoutPrecautions = findViewById(R.id.layoutPrecautions);
        layoutTtsBanner = findViewById(R.id.layoutTtsBanner);
        tvTtsBannerText = findViewById(R.id.tvTtsBannerText);

        tvCity.setOnClickListener(v -> {
            if (!isNetworkConnected()) {
                updateCitySwitchState(false);
                return;
            }
            showCityPickerDialog();
        });

        btnToggleTts.setOnClickListener(v -> {
            if (!ttsPausedTemporarily) {
                ttsPausedTemporarily = true;
                ttsManager.stop();
                btnToggleTts.setText("开启播报");
                Toast.makeText(MainActivity.this, "已暂时关闭播报", Toast.LENGTH_SHORT).show();
            } else {
                ttsPausedTemporarily = false;
                btnToggleTts.setText("关闭播报");
                speakTodayWeather(true);
            }
        });

        btnRefresh.setOnClickListener(v -> {
            simulatedOffline = false;
            CityOption city = repository.getSavedCity();
            Toast.makeText(MainActivity.this, "正在刷新天气...", Toast.LENGTH_SHORT).show();
            loadWeatherForCity(city, false);
        });

        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        btnOpenData.setOnClickListener(v -> openMobileDataSettings());
        layoutTtsBanner.setOnClickListener(v -> showInstallTtsDialog(ttsManager.getUnavailableReason()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("xiao_test_action")) {
            String action = intent.getStringExtra("xiao_test_action");
            intent.removeExtra("xiao_test_action");
            if (!TextUtils.isEmpty(action)) {
                runTestScenario(action);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Math.abs(AppSettings.getFontScale(this) - appliedFontScale) > 0.01f) {
            recreate();
            return;
        }
        updateCitySwitchState(!simulatedOffline && isNetworkConnected());
        if (ttsManager != null) {
            ttsManager.setSpeechRate(AppSettings.getSpeechRate(this));
        }
        String nowApi = AppSettings.getSelectedApiId(this);
        String nowCustomUrl = AppSettings.getCustomApiUrl(this);
        if (!nowApi.equals(appliedApiId) || !nowCustomUrl.equals(appliedCustomApiUrl)) {
            appliedApiId = nowApi;
            appliedCustomApiUrl = nowCustomUrl;
            if (!simulatedOffline) {
                Toast.makeText(this, "正在使用新天气源刷新...", Toast.LENGTH_SHORT).show();
                loadWeatherForCity(repository.getSavedCity(), false);
            }
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            netStateCallback = new NetStateCallback(this);
            NetworkRequest req = new NetworkRequest.Builder().build();
            connectivityManager.registerNetworkCallback(req, netStateCallback);
        } catch (Exception ignored) {
        }
    }

    void onNetworkStateChanged(boolean available) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean online = available && isNetworkConnected();
            updateCitySwitchState(online);
            if (online) {
                // 恢复联网后，自动刷新当前城市并后台缓存全部 4 个预设地点
                loadWeatherForCity(repository.getSavedCity(), false);
            }
        });
    }

    private void updateCitySwitchState(boolean online) {
        String cityLabel = currentWeather != null
                ? currentWeather.cityName
                : (repository != null ? repository.getSavedCity().name : WeatherRepository.DEFAULT_CITY.name);
        if (online) {
            tvCity.setText(cityLabel + " ▾");
            tvCity.setTextColor(0xFFFFFFFF);
            tvCity.setAlpha(1.0f);
            tvCity.setEnabled(true);
            tvCity.setClickable(true);
        } else {
            tvCity.setText(cityLabel);
            tvCity.setTextColor(0xFFBDBDBD);
            tvCity.setAlpha(0.75f);
            tvCity.setEnabled(false);
            tvCity.setClickable(false);
        }
    }

    @Override
    public void onTtsReady() {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            layoutTtsBanner.setVisibility(View.GONE);
            if (currentWeather != null && !hasAutoSpoken && !ttsPausedTemporarily) {
                speakTodayWeather(false);
            }
        });
    }

    @Override
    public void onTtsUnavailable(final String reason) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            layoutTtsBanner.setVisibility(View.VISIBLE);
            tvTtsBannerText.setText(reason);
            if (!hasShownTtsDialog) {
                hasShownTtsDialog = true;
                showInstallTtsDialog(reason);
            }
        });
    }

    private void requestLocationOrFallback() {
        if (hasLocationPermission()) {
            startGpsLocationAndFetch();
        } else {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startGpsLocationAndFetch();
            } else {
                repository.saveCity(WeatherRepository.DEFAULT_CITY, false);
                Toast.makeText(this, "未获取定位权限，已默认显示荆州石首市天气", Toast.LENGTH_SHORT).show();
                loadWeatherForCity(WeatherRepository.DEFAULT_CITY, true);
            }
        }
    }

    private void startGpsLocationAndFetch() {
        if (!hasLocationPermission() || locationManager == null) {
            loadWeatherForCity(repository.getSavedCity(), true);
            return;
        }

        Location gpsLast = getLastKnownSafe(LocationManager.GPS_PROVIDER);
        long now = System.currentTimeMillis();
        if (gpsLast != null && (now - gpsLast.getTime()) <= 30L * 60L * 1000L) {
            resolveAndLoadLocation(gpsLast.getLatitude(), gpsLast.getLongitude());
            return;
        }

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 10f, this, Looper.getMainLooper());
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 10f, this, Looper.getMainLooper());
                requested = true;
            }
        } catch (SecurityException ignored) {
        }

        if (requested) {
            waitingForGps = true;
            tvWeatherLine.setText("正在通过 GPS 获取位置...");
            mainHandler.removeCallbacks(gpsTimeoutTask);
            mainHandler.postDelayed(gpsTimeoutTask, GPS_TIMEOUT_MS);
        } else {
            Location best = getBestLastKnownLocation();
            if (best != null) {
                resolveAndLoadLocation(best.getLatitude(), best.getLongitude());
            } else {
                loadWeatherForCity(repository.getSavedCity(), true);
            }
        }
    }

    private void stopGpsListening() {
        waitingForGps = false;
        mainHandler.removeCallbacks(gpsTimeoutTask);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!waitingForGps || location == null) return;
        stopGpsListening();
        resolveAndLoadLocation(location.getLatitude(), location.getLongitude());
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    private Location getLastKnownSafe(String provider) {
        if (!hasLocationPermission() || locationManager == null) return null;
        try {
            return locationManager.getLastKnownLocation(provider);
        } catch (Exception e) {
            return null;
        }
    }

    private Location getBestLastKnownLocation() {
        if (!hasLocationPermission() || locationManager == null) return null;
        Location best = null;
        try {
            List<String> providers = locationManager.getProviders(true);
            for (String p : providers) {
                Location l = getLastKnownSafe(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private void resolveAndLoadLocation(final double lat, final double lon) {
        tvWeatherLine.setText("已定位，正在获取天气...");
        executor.execute(() -> {
            CityOption resolved = repository.resolveCityFromCoordinates(lat, lon);
            repository.saveCity(resolved, false);
            fetchWeatherInBackground(resolved, true);
        });
    }

    private void loadWeatherForCity(final CityOption city, final boolean autoSpeak) {
        boolean online = isNetworkConnected();
        updateCitySwitchState(online);
        if (!online) {
            handleOfflineOrFetchError(autoSpeak, "当前手机未联网");
            return;
        }
        if (currentWeather == null) {
            tvWeatherLine.setText("正在获取天气...");
        }
        executor.execute(() -> fetchWeatherInBackground(city, autoSpeak));
    }

    private void fetchWeatherInBackground(final CityOption city, final boolean autoSpeak) {
        try {
            final WeatherData data = repository.fetchWeather(city);
            // 每次有网时，后台静默把全部 4 个预设地点的数据都缓存下来兜底
            executor.execute(() -> repository.prefetchAllPresetCities());
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                simulatedOffline = false;
                layoutStaleBanner.setVisibility(View.GONE);
                renderWeather(data);
                updateCitySwitchState(true);
                checkAndFlashExtremeWeather(data, true);
                if (autoSpeak && !hasAutoSpoken && !ttsPausedTemporarily) {
                    speakTodayWeather(false);
                }
            });
        } catch (final Exception e) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateCitySwitchState(isNetworkConnected());
                handleOfflineOrFetchError(autoSpeak, "网络请求失败");
            });
        }
    }

    private void handleOfflineOrFetchError(boolean autoSpeak, String reason) {
        if (currentWeather == null) {
            currentWeather = repository.loadCachedWeather();
            if (currentWeather != null) {
                renderWeather(currentWeather);
            }
        }

        if (currentWeather != null) {
            updateStaleBanner(currentWeather, true);
            if (currentWeather.isSeverelyExpired && !hasShownExpiredDialog) {
                hasShownExpiredDialog = true;
                showExpiredDataDialog(currentWeather);
            }
            if (autoSpeak && !hasAutoSpoken && !ttsPausedTemporarily) {
                speakTodayWeather(false);
            }
        } else {
            tvWeatherLine.setText(reason + "，请打开流量后刷新");
            showNoDataOfflineDialog();
        }
    }

    private void updateStaleBanner(WeatherData data, boolean networkFailed) {
        if (!data.isFromCache && !networkFailed) {
            layoutStaleBanner.setVisibility(View.GONE);
            return;
        }

        layoutStaleBanner.setVisibility(View.VISIBLE);
        if (data.isSeverelyExpired) {
            int days = Math.max(2, data.staleHours / 24);
            layoutStaleBanner.setBackgroundColor(0xFFFFEFEF);
            tvStaleText.setText("天气数据已过期（超 " + days + " 天未更新），建议打开流量更新");
            tvStaleText.setTextColor(0xFFB81D24);
            btnOpenData.setText("去开流量");
            btnOpenData.setTextColor(0xFFB81D24);
        } else {
            String ageText = data.staleHours >= 24
                    ? (data.staleHours / 24) + " 天前"
                    : Math.max(1, data.staleHours) + " 小时前";
            layoutStaleBanner.setBackgroundColor(0xFFFFF8E6);
            tvStaleText.setText("当前无网络，已使用离线缓存天气（更新于 " + ageText + "）");
            tvStaleText.setTextColor(0xFF9C5D00);
            btnOpenData.setText("开流量更新");
            btnOpenData.setTextColor(0xFF1565C0);
        }
    }

    /**
     * 根据气温平滑计算顶部背景纯色：
     * 越冷越蓝（<=0℃深蓝），适宜温度（16℃~25℃）为绿色，越热越红（>=36℃深红）
     */
    private int getColorForTemperature(double temp) {
        final double[] stops = {-5.0, 5.0, 15.0, 22.0, 28.0, 36.0};
        final int[] colors = {
                0xFF0D47A1, // -5℃ 及以下：深蓝
                0xFF1976D2, //  5℃：冷蓝
                0xFF2E7D32, // 15℃：适宜绿
                0xFF388E3C, // 22℃：舒适绿
                0xFFE65100, // 28℃：偏热橙红
                0xFFC62828  // 36℃ 及以上：酷热红
        };

        if (temp <= stops[0]) return colors[0];
        if (temp >= stops[stops.length - 1]) return colors[colors.length - 1];

        for (int i = 0; i < stops.length - 1; i++) {
            if (temp >= stops[i] && temp <= stops[i + 1]) {
                float ratio = (float) ((temp - stops[i]) / (stops[i + 1] - stops[i]));
                return blendColor(colors[i], colors[i + 1], ratio);
            }
        }
        return colors[2];
    }

    private int blendColor(int c1, int c2, float ratio) {
        int r = Math.round(Color.red(c1) + (Color.red(c2) - Color.red(c1)) * ratio);
        int g = Math.round(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio);
        int b = Math.round(Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * ratio);
        return Color.rgb(r, g, b);
    }

    private void renderWeather(WeatherData data) {
        currentWeather = data;
        currentAdvice = LaborAdvisor.evaluate(data);

        // 顶部温感纯色（同步状态栏颜色）
        int topColor = getColorForTemperature(data.currentTemp);
        layoutTopHero.setBackgroundColor(topColor);
        getWindow().setStatusBarColor(topColor);

        // 1. 顶部：城市 + 居中大号气温 + 紧挨整天气温/天气状态/风向风速 + 农历日期
        updateCitySwitchState(!simulatedOffline && isNetworkConnected());
        tvCurrentTemp.setText(Math.round(data.currentTemp) + "°");

        String desc = !TextUtils.isEmpty(data.weatherText)
                ? data.weatherText
                : WeatherModels.getWeatherText(data.weatherCode);
        String wind = !TextUtils.isEmpty(data.windDesc)
                ? data.windDesc
                : WeatherModels.getWindDesc(data.windSpeed);

        DailyForecast today = data.getToday();
        StringBuilder lineSb = new StringBuilder();
        if (today != null) {
            lineSb.append(Math.round(today.minTemp)).append("°~").append(Math.round(today.maxTemp)).append("°  ");
        }
        lineSb.append(desc);
        if (!TextUtils.isEmpty(wind)) {
            lineSb.append("  ").append(wind);
        }
        tvWeatherLine.setText(lineSb.toString());

        String lunarText = LunarCalendarUtil.getTodayLunarDisplay();
        if (data.isFromCache && data.isOfflineShifted) {
            tvLunarDate.setText(lunarText + "  ·  离线预报");
        } else if (data.isFromCache) {
            tvLunarDate.setText(lunarText + "  ·  缓存于 " + data.updateTime);
        } else {
            tvLunarDate.setText(lunarText);
        }

        if (data.isSeverelyExpired) {
            updateStaleBanner(data, true);
        }

        // 2. 中间：近几日天气（纯白底、无装饰）
        layoutForecastList.removeAllViews();
        int count = Math.min(data.dailyList.size(), 7);
        for (int i = 0; i < count; i++) {
            DailyForecast df = data.dailyList.get(i);
            layoutForecastList.addView(buildForecastRow(df, i == 0));
            if (i < count - 1) {
                View divider = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                divider.setLayoutParams(dlp);
                divider.setBackgroundColor(0xFFF0F0F0);
                layoutForecastList.addView(divider);
            }
        }

        // 3. 下面：今日外出劳动建议（纯白底、无装饰）
        tvLaborVerdict.setText(currentAdvice.verdictTitle);
        tvLaborVerdict.setTextColor(currentAdvice.badgeTextColor);
        tvLaborSummary.setText(currentAdvice.verdictSummary);

        layoutPrecautions.removeAllViews();
        for (String tip : currentAdvice.precautions) {
            TextView tvTip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            tvTip.setLayoutParams(lp);
            tvTip.setText("· " + tip);
            tvTip.setTextColor(0xFF333333);
            tvTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvTip.setLineSpacing(dp(3), 1.0f);
            layoutPrecautions.addView(tvTip);
        }
    }

    private View buildForecastRow(DailyForecast df, boolean isToday) {
        LinearLayout rowWrap = new LinearLayout(this);
        rowWrap.setOrientation(LinearLayout.VERTICAL);
        rowWrap.setPadding(0, dp(11), 0, dp(11));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDay = new TextView(this);
        LinearLayout.LayoutParams lpDay = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvDay.setLayoutParams(lpDay);
        tvDay.setMinWidth(dp(56));
        tvDay.setPadding(0, 0, dp(12), 0);
        tvDay.setSingleLine(true);
        tvDay.setText(df.dayLabel);
        tvDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvDay.setTextColor(isToday ? 0xFF111111 : 0xFF333333);
        if (isToday) {
            tvDay.setTypeface(null, Typeface.BOLD);
        }
        topRow.addView(tvDay);

        TextView tvCond = new TextView(this);
        LinearLayout.LayoutParams lpCond = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvCond.setLayoutParams(lpCond);
        tvCond.setPadding(0, 0, dp(8), 0);
        String wText = !TextUtils.isEmpty(df.weatherText)
                ? df.weatherText
                : WeatherModels.getWeatherText(df.weatherCode);
        tvCond.setText(wText);
        tvCond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvCond.setTextColor(0xFF333333);
        topRow.addView(tvCond);

        TextView tvRange = new TextView(this);
        LinearLayout.LayoutParams lpRange = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvRange.setLayoutParams(lpRange);
        tvRange.setSingleLine(true);
        tvRange.setText(Math.round(df.minTemp) + "° / " + Math.round(df.maxTemp) + "°");
        tvRange.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvRange.setTextColor(0xFF111111);
        tvRange.setTypeface(null, Typeface.BOLD);
        topRow.addView(tvRange);

        rowWrap.addView(topRow);

        // 第二行：公历 + 农历日期 + 常规风向风速参数
        int windLevel = WeatherModels.getWindLevel(df.maxWindSpeed);
        String windText = !TextUtils.isEmpty(df.windDesc) ? df.windDesc : WeatherModels.getWindDesc(df.maxWindSpeed);
        String dateLunar = LunarCalendarUtil.getSolarAndLunarForDate(df.date);

        StringBuilder infoSb = new StringBuilder();
        if (!TextUtils.isEmpty(dateLunar)) {
            infoSb.append(dateLunar);
        }
        if (!TextUtils.isEmpty(windText)) {
            if (infoSb.length() > 0) infoSb.append("  ·  ");
            infoSb.append(windText);
        }
        if (df.precipProb >= 20) {
            if (infoSb.length() > 0) infoSb.append("  ·  ");
            infoSb.append("降水 ").append(df.precipProb).append("%");
        }
        if (infoSb.length() > 0) {
            TextView tvInfo = new TextView(this);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            infoLp.topMargin = dp(4);
            tvInfo.setLayoutParams(infoLp);
            tvInfo.setText(infoSb.toString());
            tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvInfo.setTextColor(0xFF666666);
            rowWrap.addView(tvInfo);
        }

        // 第三行（仅在有需注意事项时显示）：高对比度强调标签
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(5);
        tagRow.setLayoutParams(tagLp);

        if (df.weatherCode >= 95) {
            addContrastTag(tagRow, "雷暴防雷", 0xFFC62828, 0xFFFFFFFF);
        } else if (df.weatherCode == 82 || df.weatherCode == 65 || df.weatherCode == 56 || df.weatherCode == 57
                || df.weatherCode == 66 || df.weatherCode == 67) {
            addContrastTag(tagRow, "强降雨防滑", 0xFFC62828, 0xFFFFFFFF);
        } else if (df.weatherCode == 75 || df.weatherCode == 86) {
            addContrastTag(tagRow, "大雪防冻滑", 0xFFC62828, 0xFFFFFFFF);
        } else if (df.weatherCode == 63 || df.weatherCode == 81 || df.weatherCode == 73
                || df.weatherCode == 61 || df.weatherCode == 80 || df.weatherCode == 51
                || df.weatherCode == 53 || df.weatherCode == 55 || df.weatherCode == 71 || df.precipProb >= 50) {
            String rainLabel = df.precipProb >= 20 ? ("有雨 " + df.precipProb + "%") : "有雨带伞";
            addContrastTag(tagRow, rainLabel, 0xFF1565C0, 0xFFFFFFFF);
        }

        if (windLevel >= 5) {
            addContrastTag(tagRow, "大风 " + windLevel + "级", 0xFFD84315, 0xFFFFFFFF);
        }

        if (df.maxTemp >= 35.0) {
            addContrastTag(tagRow, "高温防暑", 0xFFB71C1C, 0xFFFFFFFF);
        } else if (df.minTemp <= 0.0) {
            addContrastTag(tagRow, "低温防冻", 0xFF0D47A1, 0xFFFFFFFF);
        }

        if (tagRow.getChildCount() > 0) {
            rowWrap.addView(tagRow);
        }

        return rowWrap;
    }

    private void addContrastTag(LinearLayout parent, String text, int bgColor, int textColor) {
        TextView tag = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tag.setLayoutParams(lp);
        tag.setPadding(dp(7), dp(2), dp(7), dp(2));
        tag.setBackgroundColor(bgColor);
        tag.setText(text);
        tag.setTextColor(textColor);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tag.setTypeface(null, Typeface.BOLD);
        parent.addView(tag);
    }

    /**
     * 联网获取天气时，若今天或近日存在极端天气，高亮展示并闪烁 2 秒提醒
     */
    private void checkAndFlashExtremeWeather(WeatherData data, boolean isOnline) {
        mainHandler.removeCallbacks(extremeFlashTask);
        if (!isOnline || data == null) {
            layoutExtremeAlert.setVisibility(View.GONE);
            return;
        }

        StringBuilder alertSb = new StringBuilder();
        int checkCount = Math.min(data.dailyList.size(), 4);
        for (int i = 0; i < checkCount; i++) {
            DailyForecast df = data.dailyList.get(i);
            String hazard = detectExtremeHazard(df, i == 0 ? data.currentTemp : df.maxTemp, i == 0 ? data.windSpeed : df.maxWindSpeed);
            if (hazard != null) {
                if (alertSb.length() > 0) alertSb.append("；");
                alertSb.append(df.dayLabel).append(" ").append(hazard);
            }
        }

        if (alertSb.length() == 0) {
            layoutExtremeAlert.setVisibility(View.GONE);
            return;
        }

        tvExtremeAlert.setText("极端天气提醒：" + alertSb.toString() + "，请注意安全防护！");
        layoutExtremeAlert.setVisibility(View.VISIBLE);
        flashStep = 0;
        mainHandler.post(extremeFlashTask);
    }

    private String detectExtremeHazard(DailyForecast df, double tempCheck, double windCheck) {
        int code = df.weatherCode;
        int windLv = WeatherModels.getWindLevel(Math.max(windCheck, df.maxWindSpeed));
        if (code >= 95) return "雷暴/冰雹";
        if (code == 82 || code == 65 || code == 56 || code == 57 || code == 66 || code == 67) return "暴雨/冻雨";
        if (code == 75 || code == 86) return "大雪/暴雪";
        if (windLv >= 6) return "大风(" + windLv + "级)";
        if (df.maxTemp >= 35.0 || tempCheck >= 35.0) return "极端高温(" + Math.round(Math.max(df.maxTemp, tempCheck)) + "°C)";
        if (df.minTemp <= -5.0 || tempCheck <= -5.0) return "严寒低温(" + Math.round(Math.min(df.minTemp, tempCheck)) + "°C)";
        return null;
    }

    private void runTestScenario(String action) {
        if ("test_reset_real".equals(action)) {
            simulatedOffline = false;
            layoutExtremeAlert.setVisibility(View.GONE);
            layoutStaleBanner.setVisibility(View.GONE);
            hasAutoSpoken = false;
            Toast.makeText(this, "已恢复真实天气数据", Toast.LENGTH_SHORT).show();
            loadWeatherForCity(repository.getSavedCity(), false);
            return;
        }
        if ("test_open_data_panel".equals(action)) {
            openMobileDataSettings();
            return;
        }
        if ("test_no_tts".equals(action)) {
            showInstallTtsDialog("【测试模拟】当前设备未安装中文 TTS 语音引擎。");
            return;
        }
        if ("test_speak_now".equals(action)) {
            ttsPausedTemporarily = false;
            btnToggleTts.setText("关闭播报");
            speakTodayWeather(true);
            return;
        }
        if ("test_offline_mode".equals(action)) {
            simulatedOffline = true;
            layoutExtremeAlert.setVisibility(View.GONE);
            updateCitySwitchState(false);
            if (currentWeather != null) {
                currentWeather.isFromCache = true;
                currentWeather.isSeverelyExpired = false;
                currentWeather.staleHours = 5;
                updateStaleBanner(currentWeather, true);
            }
            Toast.makeText(this, "【测试】已切换为断网状态，顶部地区已置灰禁点", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("test_expired_data".equals(action)) {
            simulatedOffline = true;
            layoutExtremeAlert.setVisibility(View.GONE);
            updateCitySwitchState(false);
            WeatherData fake = buildMockWeather("荆州石首市", 18, 12, 20, "多云", 2, 12, "东南风 2级");
            fake.isFromCache = true;
            fake.isSeverelyExpired = true;
            fake.staleHours = 75;
            fake.updateTime = "3天前";
            renderWeather(fake);
            updateStaleBanner(fake, true);
            showExpiredDataDialog(fake);
            return;
        }

        simulatedOffline = false;
        layoutStaleBanner.setVisibility(View.GONE);
        WeatherData mock;
        if ("test_extreme_heat".equals(action)) {
            mock = buildMockWeather("荆州石首市", 38, 29, 39, "晴", 0, 15, "南风 3级");
        } else if ("test_extreme_cold".equals(action)) {
            mock = buildMockWeather("武清区", -6, -9, -2, "大雪", 75, 45, "北风 6级");
        } else {
            // test_extreme_storm
            mock = buildMockWeather("沅陵县", 22, 18, 24, "雷暴冰雹", 96, 52, "西南风 7级");
        }
        renderWeather(mock);
        updateCitySwitchState(true);
        checkAndFlashExtremeWeather(mock, true);
        if (!ttsPausedTemporarily) {
            speakTodayWeather(true);
        }
    }

    private WeatherData buildMockWeather(String city, double cur, double min, double max,
                                         String wText, int code, double windKmh, String windDesc) {
        WeatherData data = new WeatherData();
        data.cityName = city;
        data.currentTemp = cur;
        data.apparentTemp = cur;
        data.humidity = 68;
        data.weatherText = wText;
        data.weatherCode = code;
        data.windSpeed = windKmh;
        data.windDesc = windDesc;
        data.updateTime = "测试模拟";
        data.fetchedAtMillis = System.currentTimeMillis();
        data.isFromCache = false;

        String[] labels = {"今天", "明天", "后天", "周三", "周四"};
        int[] codes = {code, 65, 61, 2, 0};
        String[] texts = {wText, "大雨", "小雨", "多云", "晴"};
        double[] winds = {windKmh, 42, 22, 14, 10};
        String[] wDescs = {windDesc, "北风 6级", "东风 4级", "东南风 3级", "微风"};
        int[] probs = {90, 80, 55, 10, 0};

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < labels.length; i++) {
            DailyForecast df = new DailyForecast();
            df.date = sdf.format(cal.getTime());
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            df.dayLabel = labels[i];
            df.weatherCode = codes[i];
            df.weatherText = texts[i];
            df.minTemp = i == 0 ? min : min + i;
            df.maxTemp = i == 0 ? max : max - i;
            df.maxWindSpeed = winds[i];
            df.windDesc = wDescs[i];
            df.precipProb = probs[i];
            data.dailyList.add(df);
        }
        return data;
    }

    private void speakTodayWeather(boolean userTriggered) {
        if (ttsPausedTemporarily && !userTriggered) {
            return;
        }
        if (currentWeather == null) {
            if (userTriggered) {
                Toast.makeText(this, "天气数据加载中，请稍候", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (ttsManager.isInitialized() && !ttsManager.isAvailable()) {
            if (userTriggered) {
                showInstallTtsDialog(ttsManager.getUnavailableReason());
            }
            return;
        }

        hasAutoSpoken = true;
        String wText = !TextUtils.isEmpty(currentWeather.weatherText)
                ? currentWeather.weatherText
                : WeatherModels.getWeatherText(currentWeather.weatherCode);

        StringBuilder sb = new StringBuilder();
        sb.append("魈天气为您播报。");
        sb.append(currentWeather.cityName).append("，");
        sb.append("今天").append(wText).append("，");
        if (!currentWeather.isOfflineShifted) {
            sb.append("当前气温").append(Math.round(currentWeather.currentTemp)).append("度");
        }

        DailyForecast today = currentWeather.getToday();
        if (today != null) {
            if (!currentWeather.isOfflineShifted) sb.append("，");
            sb.append("最高气温").append(Math.round(today.maxTemp)).append("度");
            sb.append("，最低气温").append(Math.round(today.minTemp)).append("度");
        }
        if (currentAdvice != null) {
            sb.append("。今天").append(currentAdvice.verdictTitle).append("。");
        }
        if (currentWeather.isSeverelyExpired) {
            sb.append("提醒您，当前天气数据已过期，建议打开手机流量更新。");
        }

        ttsManager.speak(sb.toString());
    }

    private void showExpiredDataDialog(WeatherData data) {
        if (isFinishing() || isDestroyed()) return;
        int days = Math.max(2, data.staleHours / 24);
        new AlertDialog.Builder(this)
                .setTitle("天气数据已过期，建议打开流量更新")
                .setMessage("当前手机未联网，天气数据距离上次更新已超过 " + days + " 天（上次更新：" + data.updateTime + "）。\n\n为了准确判断今天是否适合外出劳动，建议您打开手机移动流量（或连接 Wi-Fi）后刷新天气，更新完成后即可关闭流量，软件会自动缓存未来一周天气。")
                .setPositiveButton("去打开流量", (d, w) -> openMobileDataSettings())
                .setNeutralButton("立即刷新", (d, w) -> loadWeatherForCity(repository.getSavedCity(), false))
                .setNegativeButton("先看旧数据", null)
                .show();
    }

    private void showNoDataOfflineDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("当前未联网，无法获取天气")
                .setMessage("手机当前未连接网络，且本地还没有缓存过天气数据。\n\n请先打开手机移动流量（或连接 Wi-Fi）刷新一次，软件会自动帮您把未来 7 天的天气存到手机里，之后断网也能看！")
                .setPositiveButton("去打开流量", (d, w) -> openMobileDataSettings())
                .setNeutralButton("重试刷新", (d, w) -> loadWeatherForCity(repository.getSavedCity(), true))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void openMobileDataSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panel = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                startActivity(panel);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "请从屏幕顶部下拉，点击打开“移动数据/流量”开关", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void showInstallTtsDialog(String reason) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("建议安装语音朗读引擎（TTS）")
                .setMessage(reason + "\n\n为了在打开「魈天气」时自动播报今日气温，建议您安装并启用中文语音引擎（如：讯飞语记 / 微软语音 / Google 文字转语音，或在系统设置中开启文字转语音）。")
                .setPositiveButton("安装语音数据", (dialog, which) -> {
                    boolean opened = TtsManager.openInstallTtsData(MainActivity.this);
                    if (!opened) {
                        Toast.makeText(MainActivity.this, "无法自动打开安装页面，请前往应用商店搜索“语音引擎”安装", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("系统语音设置", (dialog, which) -> {
                    boolean opened = TtsManager.openTtsSettings(MainActivity.this);
                    if (!opened) {
                        Toast.makeText(MainActivity.this, "请在手机“设置 -> 辅助功能/系统 -> 文字转语音”中配置", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void showCityPickerDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(14), dp(18), dp(8));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText etSearch = new EditText(this);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etSearch.setLayoutParams(etLp);
        etSearch.setHint("输入城市或县名（如：石首、荆州）");
        etSearch.setSingleLine(true);
        searchRow.addView(etSearch);

        Button btnSearch = new Button(this);
        btnSearch.setText("搜索");
        searchRow.addView(btnSearch);
        container.addView(searchRow);

        final LinearLayout resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setPadding(0, dp(6), 0, dp(6));
        container.addView(resultContainer);

        TextView btnRelocate = new TextView(this);
        btnRelocate.setText("使用 GPS 自动定位");
        btnRelocate.setTextColor(0xFF1565C0);
        btnRelocate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnRelocate.setTypeface(null, Typeface.BOLD);
        btnRelocate.setPadding(dp(4), dp(10), dp(4), dp(10));
        container.addView(btnRelocate);

        TextView tvHot = new TextView(this);
        tvHot.setText("常用城市");
        tvHot.setTextColor(0xFF666666);
        tvHot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvHot.setPadding(dp(4), dp(6), dp(4), dp(6));
        container.addView(tvHot);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("切换城市")
                .setView(container)
                .setNegativeButton("取消", null)
                .create();

        btnRelocate.setOnClickListener(v -> {
            dialog.dismiss();
            hasAutoSpoken = false;
            requestLocationOrFallback();
        });

        for (int i = 0; i < WeatherRepository.PRESET_CITIES.length; i += 2) {
            LinearLayout gridRow = new LinearLayout(this);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2 && (i + j) < WeatherRepository.PRESET_CITIES.length; j++) {
                final CityOption opt = WeatherRepository.PRESET_CITIES[i + j];
                TextView chip = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                chip.setLayoutParams(lp);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(6), dp(11), dp(6), dp(11));
                chip.setText(opt.name.equals("荆州石首市") ? "石首市" : opt.name);
                chip.setTextColor(0xFF111111);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                chip.setBackgroundColor(0xFFF5F5F5);
                chip.setOnClickListener(v -> {
                    dialog.dismiss();
                    repository.saveCity(opt, true);
                    hasAutoSpoken = false;
                    loadWeatherForCity(opt, true);
                });
                gridRow.addView(chip);
            }
            container.addView(gridRow);
        }

        btnSearch.setOnClickListener(v -> {
            final String q = etSearch.getText().toString().trim();
            if (q.isEmpty()) return;
            resultContainer.removeAllViews();
            TextView loading = new TextView(MainActivity.this);
            loading.setText("正在搜索「" + q + "」...");
            loading.setTextColor(Color.GRAY);
            loading.setPadding(dp(4), dp(6), dp(4), dp(6));
            resultContainer.addView(loading);

            executor.execute(() -> {
                try {
                    final List<CityOption> list = repository.searchCities(q);
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        resultContainer.removeAllViews();
                        if (list.isEmpty()) {
                            TextView empty = new TextView(MainActivity.this);
                            empty.setText("未找到匹配城市，请换个名称试试");
                            empty.setTextColor(Color.GRAY);
                            empty.setPadding(dp(4), dp(6), dp(4), dp(6));
                            resultContainer.addView(empty);
                            return;
                        }
                        for (final CityOption item : list) {
                            TextView tv = new TextView(MainActivity.this);
                            tv.setText(item.getDisplayName());
                            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                            tv.setTextColor(0xFF111111);
                            tv.setPadding(dp(6), dp(9), dp(6), dp(9));
                            tv.setOnClickListener(v2 -> {
                                dialog.dismiss();
                                repository.saveCity(item, true);
                                hasAutoSpoken = false;
                                loadWeatherForCity(item, true);
                            });
                            resultContainer.addView(tv);
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        resultContainer.removeAllViews();
                        TextView err = new TextView(MainActivity.this);
                        err.setText("搜索失败，请检查网络连接");
                        err.setTextColor(0xFFB81D24);
                        err.setPadding(dp(4), dp(6), dp(4), dp(6));
                        resultContainer.addView(err);
                    });
                }
            });
        });

        dialog.show();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(extremeFlashTask);
        stopGpsListening();
        if (connectivityManager != null && netStateCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(netStateCallback);
            } catch (Exception ignored) {
            }
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
        }
        executor.shutdownNow();
    }
}
