package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import com.magen.family.BuildConfig;

/** Runtime configuration for the private Magen VPS. No DeepSeek secret is stored on Android. */
public final class ServerConfig {
    private static final String PREFS="magen_server";
    private static final String K_ENABLED="enabled";
    private static final String K_URL="base_url";
    private static final String K_ENROLLED="enrolled";
    private static final String K_STRICT="strict_unknown";
    private static final String K_POLICY_VER="policy_version";
    private ServerConfig() {}
    private static SharedPreferences p(Context c){ return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    public static boolean isEnabled(Context c){ return p(c).getBoolean(K_ENABLED,true); }
    public static void setEnabled(Context c,boolean v){ p(c).edit().putBoolean(K_ENABLED,v).apply(); }
    public static String baseUrl(Context c){ String u=p(c).getString(K_URL,BuildConfig.MAGEN_SERVER_URL); return normalizeUrl(u); }
    public static void setBaseUrl(Context c,String u){
        String next=normalizeUrl(u), old=baseUrl(c);
        SharedPreferences.Editor e=p(c).edit().putString(K_URL,next);
        if(!next.equals(old)){ e.putBoolean(K_ENROLLED,false); ServerVerdictCache.clear(c); ServerRuleCache.clear(c); }
        e.apply();
    }
    public static boolean isEnrolled(Context c){ return p(c).getBoolean(K_ENROLLED,false); }
    public static void setEnrolled(Context c,boolean v){ p(c).edit().putBoolean(K_ENROLLED,v).apply(); }
    public static boolean strictUnknown(Context c){ return p(c).getBoolean(K_STRICT,true); }
    public static int policyVersion(Context c){ return p(c).getInt(K_POLICY_VER,0); }
    public static void applyPolicy(Context c,boolean strict,int version){
        int current=policyVersion(c);
        if(version<current) return; // signed responses must still be monotonic on-device
        p(c).edit().putBoolean(K_STRICT,strict).putInt(K_POLICY_VER,version).apply();
    }
    public static boolean ready(Context c){ return isEnabled(c)&&isEnrolled(c)&&baseUrl(c).startsWith("https://"); }
    public static String normalizeUrl(String u){
        if(u==null) return BuildConfig.MAGEN_SERVER_URL;
        u=u.trim(); if(u.endsWith("/")) u=u.substring(0,u.length()-1);
        return u.isEmpty()?BuildConfig.MAGEN_SERVER_URL:u;
    }
}
