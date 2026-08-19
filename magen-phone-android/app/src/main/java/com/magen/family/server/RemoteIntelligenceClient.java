package com.magen.family.server;

import android.content.Context;
import android.util.Log;
import com.magen.family.filter.DomainVerdict;
import com.magen.family.filter.HostUtil;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Async bridge from local filtering to the VPS intelligence engine. */
public final class RemoteIntelligenceClient {
    private static final String TAG="MagenIntel";
    private static final ExecutorService EXEC=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"MagenIntel");t.setDaemon(true);return t;});
    private static final ExecutorService TEXT_EXEC=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MagenTextIntel");t.setDaemon(true);return t;});
    private static final AtomicLong TEXT_SEQ=new AtomicLong();
    private static final Set<String> IN_FLIGHT=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String,Boolean> TEXT_CACHE=Collections.synchronizedMap(new LinkedHashMap<String,Boolean>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Boolean> e){return size()>128;}});
    private RemoteIntelligenceClient(){}

    public static void classifyAsync(Context ctx,String host){
        Context app=ctx.getApplicationContext(); String h=HostUtil.normalizeHost(host);
        if(h.isEmpty()||!ServerConfig.ready(app)||!IN_FLIGHT.add(h))return;
        EXEC.execute(()->{
            try{
                IntelligenceRuntimeState.domainRequest();
                JSONObject body=new JSONObject().put("host",h).put("url","https://"+h).put("force",false);
                JSONObject payload=MagenApiClient.signedPost(app,"/v1/intelligence/domain",body,true);
                ServerVerdictCache.put(app,h,payload);
                if("ADULT".equals(payload.optString("verdict","UNKNOWN"))){
                    IntelligenceRuntimeState.block();
                    ContentIncidentReporter.reportDomainBlock(app,h,payload.optString("source","SERVER_AI"),
                        payload.optString("category","ADULT_EXPLICIT"),payload.optDouble("confidence",0.0));
                }
            }catch(Exception e){IntelligenceRuntimeState.failure();Log.w(TAG,"classification failed for "+h+": "+e.getMessage());}
            finally{IN_FLIGHT.remove(h);DomainVerdict.clearCache();}
        });
    }

    public interface TextVerdictCallback { void onResult(Boolean block); }

    /** Non-blocking text classification for Accessibility events. */
    public static void classifyTextAsync(Context ctx, String text, TextVerdictCallback callback) {
        classifyTextAsync(ctx, text, "", callback);
    }

    public static void classifyTextAsync(Context ctx, String text, String packageName, TextVerdictCallback callback) {
        Context app = ctx.getApplicationContext();
        if (text == null || text.trim().isEmpty() || !ServerConfig.ready(app)) {
            if (callback != null) callback.onResult(null);
            return;
        }
        final String sample = text.substring(0, Math.min(1800, text.length()));
        final String pkg = packageName == null ? "" : packageName;
        final long seq = TEXT_SEQ.incrementAndGet();
        TEXT_EXEC.execute(() -> {
            // בזמן גלילה מגיעים אירועי Accessibility רבים. רק מצב המסך האחרון
            // מקבל קריאת רשת; משימות ביניים שנערמו בתור נזרקות לפני DeepSeek.
            if (seq != TEXT_SEQ.get()) return;
            Boolean result = classifyTextBlocking(app, sample, pkg);
            if (seq == TEXT_SEQ.get() && callback != null) callback.onResult(result);
        });
    }

    /** Blocking contextual check used only from an existing background thread. */
    public static Boolean classifyTextBlocking(Context ctx,String text){ return classifyTextBlocking(ctx,text,""); }
    public static Boolean classifyTextBlocking(Context ctx,String text,String packageName){
        if(text==null||text.trim().isEmpty()||!ServerConfig.ready(ctx))return null;
        String key=hash(text.trim().toLowerCase()); Boolean cached=TEXT_CACHE.get(key); if(cached!=null)return cached;
        try{
            IntelligenceRuntimeState.textRequest();
            JSONObject payload=MagenApiClient.signedPost(ctx,"/v1/intelligence/text",new JSONObject().put("text",text.substring(0,Math.min(1800,text.length()))),true);
            String v=payload.optString("verdict","UNKNOWN"); if("UNKNOWN".equals(v))return null;
            boolean block="ADULT".equals(v); TEXT_CACHE.put(key,block);
            if(block){
                IntelligenceRuntimeState.block();
                ContentIncidentReporter.reportTextBlock(ctx,key,packageName,payload.optString("source","SERVER_AI"),
                    payload.optString("category","ADULT_EXPLICIT"),payload.optDouble("confidence",0.0));
            }
            return block;
        }catch(Exception e){IntelligenceRuntimeState.failure();Log.w(TAG,"text classification failed: "+e.getMessage());return null;}
    }
    private static String hash(String s){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(int i=0;i<12;i++)b.append(String.format("%02x",d[i]));return b.toString();}catch(Exception e){return Integer.toHexString(s.hashCode());}}
}
