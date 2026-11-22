package com.xiafengguan;

import java.util.ArrayList;
import java.util.List;

public final class WeatherModels {

    private WeatherModels() {}

    public static class DailyForecast {
        public String date;         // yyyy-MM-dd
        public String dayLabel;     // 今天 / 明天 / 后天 / 周X
        public String weatherText;  // 晴 / 多云 / 小雨 ...
        public String nightWeather; // 夜间天气（可选）
        public int weatherCode;     // 统一映射的 WMO 风格代码，便于图标与劳动评估
        public double maxTemp;
        public double minTemp;
        public int precipProb;      // 0-100，未知为 -1
        public double maxWindSpeed; // km/h
        public String windDesc;     // 如 "东南风 3-4级"
        public double uvMax;
    }

    public static class WeatherData {
        public String cityName;     // 展示名，如 "荆州石首市"
        public String province;
        public String city;
        public String county;
        public double latitude;
        public double longitude;

        public double currentTemp;
        public double apparentTemp;
        public int humidity;        // -1 表示未知
        public double windSpeed;    // km/h
        public String windDesc;     // 如 "西南风 4-5级"
        public String weatherText;  // 如 "多云"
        public int weatherCode;
        public String updateTime;   // 如 "15:40" 或 "11-22 15:40"
        public long fetchedAtMillis;
        public boolean isFromCache;
        public boolean isOfflineShifted; // 是否已按离线日期自动推进到今天
        public boolean isSeverelyExpired;
        public int staleHours;

        public List<DailyForecast> dailyList = new ArrayList<>();

        public DailyForecast getToday() {
            return dailyList.isEmpty() ? null : dailyList.get(0);
        }
    }

    public static class CityOption {
        public String name;       // 展示名，如 "荆州石首市"
        public String province;   // 如 "湖北"
        public String city;       // 如 "荆州"
        public String county;     // 如 "石首"
        public double latitude;
        public double longitude;

        public CityOption(String name, String province, String city, String county, double latitude, double longitude) {
            this.name = name;
            this.province = province;
            this.city = city;
            this.county = county;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getDisplayName() {
            StringBuilder sb = new StringBuilder();
            if (province != null && !province.isEmpty()) sb.append(province);
            if (city != null && !city.isEmpty() && !city.equals(province)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(city);
            }
            if (county != null && !county.isEmpty() && !county.equals(city)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(county);
            }
            return sb.length() > 0 ? sb.toString() : name;
        }
    }

    public static class LaborAdvice {
        public static final int LEVEL_GOOD = 0;
        public static final int LEVEL_CAUTION = 1;
        public static final int LEVEL_POOR = 2;
        public static final int LEVEL_AVOID = 3;

        public int level;
        public String verdictTitle;
        public String verdictSummary;
        public int badgeBgColor;
        public int badgeTextColor;
        public List<String> precautions = new ArrayList<>();
    }

    /**
     * 将中文天气描述映射为统一天气码（便于图标和外出劳动规则评估）
     */
    public static int mapTextToCode(String text) {
        if (text == null) return 2;
        if (text.contains("冰雹")) return 96;
        if (text.contains("雷")) return 95;
        if (text.contains("暴雪") || text.contains("大雪")) return 75;
        if (text.contains("中雪")) return 73;
        if (text.contains("雪")) return 71;
        if (text.contains("冻雨")) return 66;
        if (text.contains("暴雨") || text.contains("大暴雨")) return 82;
        if (text.contains("大雨")) return 65;
        if (text.contains("中雨")) return 63;
        if (text.contains("阵雨")) return 80;
        if (text.contains("小雨") || text.contains("毛毛雨") || text.contains("雨")) return 61;
        if (text.contains("雾") || text.contains("霾") || text.contains("沙") || text.contains("尘")) return 45;
        if (text.contains("阴")) return 3;
        if (text.contains("多云")) return 2;
        if (text.contains("晴")) return 0;
        return 2;
    }

    public static String getWeatherText(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "大部晴朗";
            case 2: return "多云";
            case 3: return "阴";
            case 45: return "雾";
            case 48: return "冻雾";
            case 51: return "毛毛雨";
            case 53: return "小雨";
            case 55: return "密毛毛雨";
            case 56:
            case 57: return "冻雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66:
            case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "霰";
            case 80: return "小阵雨";
            case 81: return "中阵雨";
            case 82: return "暴雨";
            case 85: return "小阵雪";
            case 86: return "大阵雪";
            case 95: return "雷阵雨";
            case 96:
            case 99: return "雷暴冰雹";
            default: return "多云";
        }
    }

    public static String getWeatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code == 1 || code == 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "🌤️";
    }

    /**
     * 将腾讯风力字符串（如 "4-5"、"3"、"1-3"）转为估算风速 km/h
     */
    public static double parseWindPowerToKmh(String powerStr) {
        if (powerStr == null || powerStr.trim().isEmpty()) return 10.0;
        int maxLevel = 2;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < powerStr.length(); i++) {
            char c = powerStr.charAt(i);
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (num.length() > 0) {
                try {
                    maxLevel = Math.max(maxLevel, Integer.parseInt(num.toString()));
                } catch (Exception ignored) {}
                num.setLength(0);
            }
        }
        if (num.length() > 0) {
            try {
                maxLevel = Math.max(maxLevel, Integer.parseInt(num.toString()));
            } catch (Exception ignored) {}
        }
        switch (maxLevel) {
            case 0: return 1.0;
            case 1: return 4.0;
            case 2: return 9.0;
            case 3: return 16.0;
            case 4: return 24.0;
            case 5: return 34.0;
            case 6: return 44.0;
            case 7: return 56.0;
            case 8: return 68.0;
            default: return 80.0;
        }
    }

    public static int getWindLevel(double kmh) {
        if (kmh < 1) return 0;
        if (kmh < 6) return 1;
        if (kmh < 12) return 2;
        if (kmh < 20) return 3;
        if (kmh < 29) return 4;
        if (kmh < 39) return 5;
        if (kmh < 50) return 6;
        if (kmh < 62) return 7;
        if (kmh < 75) return 8;
        return 9;
    }

    public static String getWindDesc(double kmh) {
        int level = getWindLevel(kmh);
        if (level <= 1) return "微风";
        return level + "级风";
    }
}
