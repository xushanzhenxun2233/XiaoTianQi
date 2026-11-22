package com.xiafengguan;

import com.xiafengguan.WeatherModels.DailyForecast;
import com.xiafengguan.WeatherModels.LaborAdvice;
import com.xiafengguan.WeatherModels.WeatherData;

import java.util.ArrayList;
import java.util.List;

public final class LaborAdvisor {

    private LaborAdvisor() {}

    public static LaborAdvice evaluate(WeatherData data) {
        LaborAdvice advice = new LaborAdvice();
        DailyForecast today = data.getToday();

        int code = data.weatherCode;
        int dayCode = today != null ? today.weatherCode : code;
        double curTemp = data.currentTemp;
        double maxTemp = today != null ? Math.max(curTemp, today.maxTemp) : curTemp;
        double minTemp = today != null ? Math.min(curTemp, today.minTemp) : curTemp;
        double wind = today != null ? Math.max(data.windSpeed, today.maxWindSpeed) : data.windSpeed;
        int windLevel = WeatherModels.getWindLevel(wind);
        double uv = today != null ? today.uvMax : 0;
        int precipProb = today != null ? today.precipProb : 0;
        int humidity = data.humidity;

        int level = LaborAdvice.LEVEL_GOOD;
        List<String> reasons = new ArrayList<>();
        List<String> tips = new ArrayList<>();

        // 1. 极端降水/雷暴评估
        if (isThunderstorm(code) || isThunderstorm(dayCode)) {
            level = Math.max(level, LaborAdvice.LEVEL_AVOID);
            reasons.add("有雷暴或冰雹风险");
            tips.add("切勿在空旷地带、大树下或高处作业，谨防雷击与冰雹砸伤。");
            tips.add("如正在户外作业，请立即停止并转移至安全室内躲避。");
        } else if (isHeavyPrecip(code) || isHeavyPrecip(dayCode)) {
            level = Math.max(level, LaborAdvice.LEVEL_AVOID);
            reasons.add("有强降水/冻雨或大雪");
            tips.add("雨雪较大、路面湿滑或结冰，能见度低，建议推迟户外劳动。");
            tips.add("如确需外出，请穿戴防滑雨鞋与防水衣具，避开低洼积水和陡坡。");
        } else if (isModeratePrecip(code) || isModeratePrecip(dayCode)) {
            level = Math.max(level, LaborAdvice.LEVEL_POOR);
            reasons.add("有中雨或降雪");
            tips.add("请穿好防雨防滑装备，注意保护工具和随身物品防潮。");
            tips.add("及时擦干汗水与雨水，避免受凉感冒，走路干活防滑倒。");
        } else if (isLightPrecip(code) || isLightPrecip(dayCode) || precipProb >= 55) {
            level = Math.max(level, LaborAdvice.LEVEL_CAUTION);
            reasons.add("可能有小雨");
            tips.add("随身携带雨衣或雨具，穿防滑鞋，留意地面湿滑。");
        } else if (code == 45 || code == 48 || dayCode == 45 || dayCode == 48) {
            level = Math.max(level, LaborAdvice.LEVEL_CAUTION);
            reasons.add("有雾能见度偏低");
            tips.add("户外作业注意来往车辆与周围环境，可穿亮色衣物提高辨识度。");
        }

        // 2. 气温评估
        if (maxTemp >= 35.0 || curTemp >= 35.0) {
            level = Math.max(level, LaborAdvice.LEVEL_AVOID);
            reasons.add("气温过高（达35℃以上）");
            tips.add("严防中暑与热射病，尽量避开 10:00–16:00 高温暴晒时段作业。");
            tips.add("少量多次补充淡盐水或温开水，出现头晕、乏力立即到阴凉处休息。");
        } else if (maxTemp >= 30.0 || curTemp >= 30.0) {
            level = Math.max(level, LaborAdvice.LEVEL_CAUTION);
            reasons.add("天气较炎热");
            tips.add("避开正午暴晒时段，戴好遮阳帽、穿浅色透气衣物，及时补水防暑。");
            if (humidity >= 70) {
                tips.add("空气湿度较高，体感闷热，更易出汗中暑，注意增加阴凉休息频次。");
            }
        } else if (curTemp <= -5.0 || minTemp <= -8.0) {
            level = Math.max(level, LaborAdvice.LEVEL_POOR);
            reasons.add("天气严寒");
            tips.add("做好防寒防冻，穿戴保暖手套、帽子和防风外套，预防手耳冻伤。");
            tips.add("早晚可能有霜冻或暗冰，行走和搬运重物时格外防滑。");
        } else if (curTemp <= 5.0 || minTemp <= 3.0) {
            level = Math.max(level, LaborAdvice.LEVEL_CAUTION);
            reasons.add("气温偏低");
            tips.add("注意添衣保暖，干活前先简单活动筋骨热身，出汗后及时避风防感冒。");
        }

        // 3. 大风评估
        if (windLevel >= 7) {
            level = Math.max(level, LaborAdvice.LEVEL_AVOID);
            reasons.add("风力极大（" + windLevel + "级）");
            tips.add("禁止登高、搭架或在大树、广告牌、临时工棚附近作业，谨防坠物。");
        } else if (windLevel >= 6) {
            level = Math.max(level, LaborAdvice.LEVEL_POOR);
            reasons.add("风力较大（" + windLevel + "级）");
            tips.add("注意防风沙迷眼，固定好轻质物料与工具，尽量避免高空作业。");
        } else if (windLevel >= 4) {
            tips.add("今日有一定风力（" + windLevel + "级），户外作业注意防风与防尘。");
        }

        // 4. 紫外线与温差补充建议
        if (uv >= 6.0 && maxTemp < 35.0) {
            tips.add("紫外线较强，长时间户外劳动建议戴宽檐帽、穿长袖做好防晒。");
        }
        if ((maxTemp - minTemp) >= 12.0) {
            tips.add("今日昼夜温差较大（约 " + Math.round(maxTemp - minTemp) + "℃），建议洋葱式穿衣，热了可脱、凉了可加。");
        }

        // 5. 若天气整体舒适，补充通用健康劳动提醒
        if (tips.isEmpty()) {
            tips.add("天气整体舒适，适合安排农活、户外维修、搬运等室外劳动。");
            tips.add("注意劳逸结合，每工作 1 小时左右适当喝水、活动肩颈与腰背。");
            tips.add("穿戴合脚防滑的鞋子与必要防护手套，注意作业安全。");
        } else if (tips.size() == 1) {
            tips.add("合理安排体力，及时补充水分，避免过度疲劳。");
        }

        advice.level = level;
        advice.precautions = tips;

        switch (level) {
            case LaborAdvice.LEVEL_GOOD:
                advice.verdictTitle = "适合外出劳动";
                advice.verdictSummary = "今日气温与风力都比较适宜，户外作业舒适度高。";
                advice.badgeBgColor = 0xFFE3F6F0;
                advice.badgeTextColor = 0xFF136F5C;
                break;
            case LaborAdvice.LEVEL_CAUTION:
                advice.verdictTitle = "较适合外出劳动（注意防护）";
                advice.verdictSummary = "可以开展户外劳动，但" + joinReasons(reasons) + "，请做好对应防护。";
                advice.badgeBgColor = 0xFFFFF4DC;
                advice.badgeTextColor = 0xFF9C5D00;
                break;
            case LaborAdvice.LEVEL_POOR:
                advice.verdictTitle = "不太适合外出劳动";
                advice.verdictSummary = "今日" + joinReasons(reasons) + "，建议减少户外作业时间。";
                advice.badgeBgColor = 0xFFFFEBDD;
                advice.badgeTextColor = 0xFFC24900;
                break;
            default:
                advice.verdictTitle = "不建议外出劳动";
                advice.verdictSummary = "今日" + joinReasons(reasons) + "，户外作业风险较高，建议暂缓。";
                advice.badgeBgColor = 0xFFFFE4E4;
                advice.badgeTextColor = 0xFFB81D24;
                break;
        }
        return advice;
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons.isEmpty()) return "天气略有变化";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(reasons.get(i));
        }
        return sb.toString();
    }

    private static boolean isThunderstorm(int code) {
        return code >= 95;
    }

    private static boolean isHeavyPrecip(int code) {
        return code == 56 || code == 57 || code == 65 || code == 66 || code == 67
                || code == 75 || code == 82 || code == 86;
    }

    private static boolean isModeratePrecip(int code) {
        return code == 63 || code == 73 || code == 81;
    }

    private static boolean isLightPrecip(int code) {
        return code == 51 || code == 53 || code == 55 || code == 61
                || code == 71 || code == 77 || code == 80 || code == 85;
    }
}
