package com.xiafengguan;

import android.icu.util.ChineseCalendar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LunarCalendarUtil {

    private static final String[] LUNAR_MONTHS = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
    };

    private static final String[] LUNAR_DAYS = {
            "", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    private LunarCalendarUtil() {}

    public static String getTodayLunarDisplay() {
        return formatSolarAndLunar(new Date());
    }

    public static String getSolarAndLunarForDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.trim().isEmpty()) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = sdf.parse(yyyyMMdd.trim());
            if (d != null) {
                return formatSolarAndLunar(d);
            }
        } catch (Exception ignored) {
        }
        return yyyyMMdd;
    }

    private static String formatSolarAndLunar(Date date) {
        String solar = new SimpleDateFormat("M月d日", Locale.CHINA).format(date);
        try {
            ChineseCalendar cc = new ChineseCalendar();
            cc.setTime(date);
            int month = cc.get(ChineseCalendar.MONTH); // 0-11
            int day = cc.get(ChineseCalendar.DAY_OF_MONTH); // 1-30
            int isLeap = cc.get(ChineseCalendar.IS_LEAP_MONTH);

            String monthStr = (month >= 0 && month < LUNAR_MONTHS.length) ? LUNAR_MONTHS[month] : "";
            String dayStr = (day >= 1 && day < LUNAR_DAYS.length) ? LUNAR_DAYS[day] : "";
            String leapPrefix = (isLeap == 1) ? "闰" : "";
            return solar + " 农历" + leapPrefix + monthStr + dayStr;
        } catch (Exception e) {
            return solar;
        }
    }
}
