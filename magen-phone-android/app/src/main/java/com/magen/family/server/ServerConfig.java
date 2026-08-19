package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import com.magen.family.BuildConfig;

import java.net.URI;
import java.net.URISyntaxException;

/** Runtime configuration for the private Magen VPS. No DeepSeek secret is stored on Android. */
public final class ServerConfig {
    private static final String PREFS="magen_server";
    private static final String K_ENABLED="enabled";
    private static final String K_URL="base_url";
    private static final String K_ENROLLED="enrolled";
    private static final String K_STRICT="strict_unknown";
    private static final String K_POLICY_VER="policy_version";
    public static final int REQUIRED_PUBLIC_PORT=8443;
    private ServerConfig() {}
    private static SharedPreferences p(Context c){ return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    public static boolean isEnabled(Context c){ return p(c).getBoolean(K_ENABLED,true); }
    public static void setEnabled(Context c,boolean v){ p(c).edit().putBoolean(K_ENABLED,v).apply(); }
    public static String baseUrl(Context c){
        SharedPreferences prefs=p(c);
        String raw=prefs.getString(K_URL,null);
        if(raw==null||raw.trim().isEmpty()) return normalizedBuildUrl();
        final String u;
        try{
            u=normalizeUrl(raw);
        }catch(IllegalArgumentException badStoredUrl){
            // Never crash the protection process because of a stale/corrupt preference.
            // Reset to the compiled 8443 endpoint and require enrollment again.
            String fallback=normalizedBuildUrl();
            prefs.edit().putString(K_URL,fallback).putBoolean(K_ENROLLED,false).putInt(K_POLICY_VER,0).apply();
            ServerVerdictCache.clear(c);
            ServerRuleCache.clear(c);
            return fallback;
        }
        // Paired migration: APK updates preserve SharedPreferences. If an old VPS
        // endpoint is still stored, move to the current coexistence endpoint and
        // force a fresh enrollment. Do not silently rewrite other valid 8443 hosts.
        if(isLegacyPairedUrl(u)){
            String fallback=normalizedBuildUrl();
            prefs.edit()
                    .putString(K_URL,fallback)
                    .putBoolean(K_ENROLLED,false)
                    .putInt(K_POLICY_VER,0)
                    .apply();
            ServerVerdictCache.clear(c);
            ServerRuleCache.clear(c);
            return fallback;
        }
        return u;
    }
    private static boolean isLegacyPairedUrl(String u){
        return "https://51.21.194.27:8443".equalsIgnoreCase(u);
    }
    public static void setBaseUrl(Context c,String u){
        String next=normalizeUrl(u), old=baseUrl(c);
        SharedPreferences.Editor e=p(c).edit().putString(K_URL,next);
        if(!next.equals(old)){ e.putBoolean(K_ENROLLED,false); e.putInt(K_POLICY_VER,0); ServerVerdictCache.clear(c); ServerRuleCache.clear(c); }
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
    public static boolean ready(Context c){
        if(!isEnabled(c)||!isEnrolled(c)) return false;
        try { normalizeUrl(baseUrl(c)); return true; }
        catch(IllegalArgumentException ignored){ return false; }
    }

    /**
     * Accept only the private Magen transport shape: HTTPS, explicit port 8443,
     * no user-info, query, fragment, or application path. Hostnames and IPs are
     * both supported so TLS hostname verification still happens normally.
     */
    public static String normalizeUrl(String value){
        String u=value==null?"":value.trim();
        if(u.isEmpty()) return normalizedBuildUrl();
        while(u.endsWith("/")) u=u.substring(0,u.length()-1);
        try{
            URI uri=new URI(u);
            if(!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Magen server must use HTTPS");
            if(uri.getHost()==null || uri.getHost().trim().isEmpty()) throw new IllegalArgumentException("Magen server host is missing");
            if(uri.getPort()!=REQUIRED_PUBLIC_PORT) throw new IllegalArgumentException("Magen server must use port 8443");
            if(uri.getRawUserInfo()!=null) throw new IllegalArgumentException("Credentials are not allowed in server URL");
            if(uri.getRawQuery()!=null || uri.getRawFragment()!=null) throw new IllegalArgumentException("Query/fragment not allowed in server URL");
            String path=uri.getRawPath();
            if(path!=null && !path.isEmpty() && !"/".equals(path)) throw new IllegalArgumentException("Server URL must not contain a path");
            return new URI("https",null,uri.getHost(),REQUIRED_PUBLIC_PORT,null,null,null).toASCIIString();
        }catch(URISyntaxException e){
            throw new IllegalArgumentException("Invalid Magen server URL",e);
        }
    }

    private static String normalizedBuildUrl(){
        String configured=BuildConfig.MAGEN_SERVER_URL==null?"":BuildConfig.MAGEN_SERVER_URL.trim();
        if(configured.isEmpty()) throw new IllegalStateException("MAGEN_SERVER_URL is empty");
        // Avoid recursive fallback through normalizeUrl(null/empty).
        try{
            URI uri=new URI(configured);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getPort()!=REQUIRED_PUBLIC_PORT
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || (uri.getRawPath()!=null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                throw new IllegalStateException("Compiled Magen server URL must be HTTPS on 8443 without a path");
            }
            return new URI("https",null,uri.getHost(),REQUIRED_PUBLIC_PORT,null,null,null).toASCIIString();
        }catch(URISyntaxException e){
            throw new IllegalStateException("Compiled Magen server URL is invalid",e);
        }
    }
}
