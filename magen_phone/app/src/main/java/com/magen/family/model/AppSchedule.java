package com.magen.family.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * AppSchedule — לוח זמנים פר-אפליקציה.
 *
 *   • daily_limit_minutes — מקסימום זמן יומי (0 = ללא הגבלה)
 *   • allowed_hours       — שעות מותרות (למשל [16:00-20:00])
 *   • allowed_days        — ימי שבוע מותרים (1=ראשון … 7=שבת)
 *
 * כל אחד מהשדות הוא restriction נפרד — אפליקציה נחסמת אם *לפחות אחד* מהם מופר.
 */
public class AppSchedule {

    public final String packageName;
    public int dailyLimitMinutes;     // 0 = ללא הגבלה
    public int allowedHourStart;      // 0-23, -1 = ללא הגבלת שעות
    public int allowedHourEnd;        // 0-23
    public List<Integer> allowedDays; // 1-7 (Calendar.SUNDAY...), null = כל הימים

    public AppSchedule(String packageName) {
        this.packageName = packageName;
        this.dailyLimitMinutes = 0;
        this.allowedHourStart = -1;
        this.allowedHourEnd = -1;
        this.allowedDays = null;
    }

    public boolean isAllowedNow(long usedMinutesToday) {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int day  = now.get(Calendar.DAY_OF_WEEK); // 1=Sunday … 7=Saturday

        if (dailyLimitMinutes > 0 && usedMinutesToday >= dailyLimitMinutes) return false;

        if (allowedDays != null && !allowedDays.isEmpty() && !allowedDays.contains(day))
            return false;

        if (allowedHourStart >= 0 && allowedHourEnd >= 0) {
            if (allowedHourStart <= allowedHourEnd) {
                if (hour < allowedHourStart || hour >= allowedHourEnd) return false;
            } else {
                // חוצה חצות (למשל 22-6)
                if (hour < allowedHourStart && hour >= allowedHourEnd) return false;
            }
        }
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("package", packageName);
        o.put("daily_limit_minutes", dailyLimitMinutes);
        o.put("hour_start", allowedHourStart);
        o.put("hour_end", allowedHourEnd);
        if (allowedDays != null) {
            JSONArray arr = new JSONArray();
            for (int d : allowedDays) arr.put(d);
            o.put("days", arr);
        }
        return o;
    }

    public static AppSchedule fromJson(JSONObject o) throws JSONException {
        AppSchedule s = new AppSchedule(o.getString("package"));
        s.dailyLimitMinutes = o.optInt("daily_limit_minutes", 0);
        s.allowedHourStart  = o.optInt("hour_start", -1);
        s.allowedHourEnd    = o.optInt("hour_end", -1);
        if (o.has("days")) {
            JSONArray arr = o.getJSONArray("days");
            s.allowedDays = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) s.allowedDays.add(arr.getInt(i));
        }
        return s;
    }
}
