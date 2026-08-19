package com.magen.family.mitm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.magen.family.server.ContentIncidentReporter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Policy for managed HTTPS inspection.
 *
 * The proxy deliberately bypasses high-sensitivity identity/payment/health endpoints and never
 * attempts to defeat certificate pinning. A failed TLS interception is remembered only as a
 * one-way hash for a few hours, then the host is tunneled unmodified on the next connection.
 */
public final class MitmPolicy {
    private static final String PREFS="magen_https_inspection";
    private static final String KEY_ENABLED="enabled";
    private static final String FB_PREFIX="fb_";
    private static final long FALLBACK_MS=6L*60L*60L*1000L;
    private static final int MAX_FALLBACKS=512;

    // Privacy bypasses are intentionally conservative. They are TLS-tunneled, not decrypted.
    private static final Set<String> EXACT_BYPASS=new HashSet<>(Arrays.asList(
        "accounts.google.com","myaccount.google.com","appleid.apple.com",
        "login.microsoftonline.com","login.live.com","account.live.com",
        "paypal.com","www.paypal.com","stripe.com","wise.com","payoneer.com",
        "bankhapoalim.co.il","leumi.co.il","discountbank.co.il","mizrahi-tefahot.co.il","fibi.co.il",
        "clalit.co.il","maccabi4u.co.il","meuhedet.co.il","leumit.co.il",
        "vault.bitwarden.com","my.1password.com","lastpass.com","dashlane.com"
    ));
    private static final String[] SUFFIX_BYPASS={
        ".paypal.com",".paypalobjects.com",".stripe.com",".wise.com",".payoneer.com",
        ".bankhapoalim.co.il",".leumi.co.il",".discountbank.co.il",".mizrahi-tefahot.co.il",".fibi.co.il",
        ".clalit.co.il",".maccabi4u.co.il",".meuhedet.co.il",".leumit.co.il",
        ".bitwarden.com",".1password.com",".lastpass.com",".dashlane.com"
    };

    private MitmPolicy(){}
    private static SharedPreferences p(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return p(c).getBoolean(KEY_ENABLED,true);}
    public static void setEnabled(Context c,boolean v){p(c).edit().putBoolean(KEY_ENABLED,v).apply();}

    public static boolean shouldBypass(String host){
        String h=normalize(host); if(h.isEmpty())return true;
        if(EXACT_BYPASS.contains(h))return true;
        for(String s:SUFFIX_BYPASS)if(h.endsWith(s))return true;
        // Generic privacy guard for explicit sign-in/payment host labels. Avoid broad substring
        // matching in paths; this applies only to DNS labels and errs toward tunneling.
        String first=h.contains(".")?h.substring(0,h.indexOf('.')):h;
        return first.equals("login")||first.equals("signin")||first.equals("auth")||first.equals("oauth")
            ||first.equals("payments")||first.equals("payment")||first.equals("wallet")||first.equals("banking");
    }

    public static boolean shouldIntercept(Context c,String host){
        return enabled(c)&&MitmCaManager.isTrustedForInspection(c)&&!shouldBypass(host)&&!isFallback(c,host);
    }

    public static void markFallback(Context c,String host,String reason){
        String h=normalize(host); if(h.isEmpty())return;
        long until=System.currentTimeMillis()+FALLBACK_MS;
        SharedPreferences sp=p(c);
        sp.edit().putLong(FB_PREFIX+hash(h),until).apply();
        pruneFallbacks(sp);
        MitmRuntimeState.fallback();
        try{ContentIncidentReporter.reportMitmFallback(c,h,reason);}catch(Exception ignored){}
    }

    public static boolean isFallback(Context c,String host){
        String h=normalize(host); if(h.isEmpty())return true;
        String key=FB_PREFIX+hash(h); long until=p(c).getLong(key,0L);
        if(until<=System.currentTimeMillis()){
            if(until!=0L)p(c).edit().remove(key).apply();
            return false;
        }
        return true;
    }

    public static void clearFallbacks(Context c){
        SharedPreferences sp=p(c); SharedPreferences.Editor e=sp.edit();
        for(String k:sp.getAll().keySet())if(k.startsWith(FB_PREFIX))e.remove(k);
        e.apply();
    }

    private static void pruneFallbacks(SharedPreferences sp){
        long now=System.currentTimeMillis();
        java.util.ArrayList<java.util.Map.Entry<String,Long>> live=new java.util.ArrayList<>();
        SharedPreferences.Editor edit=sp.edit();
        for(java.util.Map.Entry<String,?> entry:sp.getAll().entrySet()){
            if(!entry.getKey().startsWith(FB_PREFIX)||!(entry.getValue() instanceof Long))continue;
            long until=(Long)entry.getValue();
            if(until<=now)edit.remove(entry.getKey());
            else live.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(),until));
        }
        if(live.size()>MAX_FALLBACKS){
            java.util.Collections.sort(live,(a,b)->Long.compare(a.getValue(),b.getValue()));
            for(int i=0;i<live.size()-MAX_FALLBACKS;i++)edit.remove(live.get(i).getKey());
        }
        edit.apply();
    }

    private static String normalize(String h){
        if(h==null)return ""; h=h.trim().toLowerCase(Locale.ROOT); while(h.endsWith("."))h=h.substring(0,h.length()-1); return h;
    }
    private static String hash(String s){
        try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(int i=0;i<12;i++)b.append(String.format(Locale.US,"%02x",d[i]&0xff));return b.toString();}
        catch(Exception e){return Integer.toHexString(s.hashCode());}
    }
}
