package com.magen.family.server;

import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.magen.family.filter.HostUtil;
import com.magen.family.visual.NsfwResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reliable privacy-minimized Content Intelligence incident delivery.
 *
 * Never sends screenshot/image bytes or raw visible text. Domain incidents contain the host;
 * text incidents contain only a one-way SHA-256 digest; visual incidents contain package name
 * and numeric classifier metadata.
 */
public final class ContentIncidentReporter {
    private static final String TAG="MagenIncidents";
    private static final String PREFS="magen_incident_queue";
    private static final String KEY="pending_v1";
    private static final int MAX_PENDING=150;
    private static final Object LOCK=new Object();
    private static final Map<String,Long> LAST_SENT=new ConcurrentHashMap<>();
    private static final ExecutorService EXEC=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"MagenIncidents"); t.setDaemon(true); return t;
    });

    private ContentIncidentReporter() {}

    public static void reportDomainBlock(Context c,String host,String source,String category,double confidence){
        String h=HostUtil.normalizeHost(host);
        if(h.isEmpty())return;
        JSONObject d=new JSONObject();
        report(c,"DOMAIN",sha256(h),h,"","BLOCK",safeToken(category,"POLICY_BLOCK"),confidence,
            "BLOCK",safeToken(source,"LOCAL_PIPELINE"),d,10*60_000L);
    }

    public static void reportTextBlock(Context c,String textDigest,String packageName,String source,String category,double confidence){
        String digest=(textDigest==null?"":textDigest.trim().toLowerCase(Locale.ROOT));
        if(!digest.matches("[0-9a-f]{16,64}"))return;
        report(c,"TEXT",digest,"",safePackage(packageName),"BLOCK",safeToken(category,"ADULT_EXPLICIT"),confidence,
            "BLOCK",safeToken(source,"SERVER_AI"),new JSONObject(),30_000L);
    }

    /** HTTPS inspection block. Host is retained because domain incidents already use it; no path/query/header/body is sent. */
    public static void reportMitmBlock(Context c,String host,String source){
        String h=HostUtil.normalizeHost(host);
        if(h.isEmpty())return;
        JSONObject d=new JSONObject();
        report(c,"DOMAIN",sha256(h),h,"","BLOCK","HTTPS_INSPECTION",1.0,
            "BLOCK",safeToken(source,"HTTPS_INSPECTION"),d,60_000L);
    }

    /**
     * TLS pinning/protocol incompatibility fallback. The hostname itself is intentionally NOT sent;
     * only a one-way subject digest and a bounded machine token are reported.
     */
    public static void reportMitmFallback(Context c,String host,String reason){
        String h=HostUtil.normalizeHost(host);
        if(h.isEmpty())return;
        JSONObject d=new JSONObject();
        try{d.put("reason",safeToken(reason,"TLS_INCOMPATIBLE"));}catch(Exception ignored){}
        report(c,"TLS_COMPAT",sha256(h),"","","ALLOW","TLS_PINNING_OR_INCOMPATIBLE",0.0,
            "FALLBACK","HTTPS_INSPECTION",d,6L*60L*60L*1000L);
    }

    public static void reportVisualBlock(Context c,String packageName,NsfwResult r){
        if(r==null)return;
        JSONObject d=new JSONObject();
        try{
            d.put("label",safeToken(r.label,"UNKNOWN"));
            d.put("top_score",clamp(r.topScore));
            d.put("unsafe_sum",clamp(r.unsafeSum));
            d.put("porn",clamp(r.porn));
            d.put("hentai",clamp(r.hentai));
            d.put("sexy",clamp(r.sexy));
            d.put("tile_index",r.tileIndex);
        }catch(Exception ignored){}
        String pkg=safePackage(packageName);
        report(c,"VISUAL",sha256(pkg+"|"+safeToken(r.label,"UNKNOWN")),"",pkg,"BLOCK","NSFW_VISUAL",
            clamp(r.unsafeSum),"BLOCK","ON_DEVICE_LITERT",d,10_000L);
    }

    private static void report(Context c,String kind,String subjectHash,String host,String pkg,String verdict,
                               String category,double confidence,String action,String source,JSONObject details,long dedupeMs){
        if(c==null)return;
        final Context app=c.getApplicationContext();
        final String dedupe=kind+"|"+subjectHash;
        long now=SystemClock.elapsedRealtime(); Long previous=LAST_SENT.get(dedupe);
        if(previous!=null && now-previous<dedupeMs)return;
        LAST_SENT.put(dedupe,now);
        final JSONObject wire=new JSONObject();
        try{
            wire.put("client_incident_id",UUID.randomUUID().toString());
            wire.put("kind",kind).put("subject_hash",subjectHash).put("host",host)
                .put("package_name",pkg).put("verdict",verdict).put("category",category)
                .put("confidence",clamp(confidence)).put("action",action).put("source",source)
                .put("details",details==null?new JSONObject():details);
        }catch(Exception e){return;}
        EXEC.execute(()->{ synchronized(LOCK){ flushLocked(app); if(!send(app,wire)) enqueueLocked(app,wire); } });
    }

    public static void flushPendingAsync(Context c){
        if(c==null)return; final Context app=c.getApplicationContext();
        EXEC.execute(()->{ synchronized(LOCK){ flushLocked(app); } });
    }

    public static int pendingCount(Context c){
        if(c==null)return 0;
        synchronized(LOCK){ return load(c.getApplicationContext()).length(); }
    }

    private static boolean send(Context app,JSONObject wire){
        if(!ServerConfig.ready(app))return false;
        try{ MagenApiClient.signedPost(app,"/v1/incidents",wire,false); return true; }
        catch(Exception e){ Log.w(TAG,"incident delivery failed: "+e.getClass().getSimpleName()); return false; }
    }

    private static void flushLocked(Context app){
        if(!ServerConfig.ready(app))return;
        JSONArray pending=load(app); if(pending.length()==0)return;
        JSONArray remaining=new JSONArray(); boolean failed=false;
        for(int i=0;i<pending.length();i++){
            JSONObject e=pending.optJSONObject(i); if(e==null)continue;
            if(!failed && send(app,e))continue;
            failed=true; remaining.put(e);
        }
        save(app,remaining);
    }

    private static void enqueueLocked(Context app,JSONObject incident){
        JSONArray old=load(app), next=new JSONArray();
        int start=Math.max(0,old.length()-(MAX_PENDING-1));
        for(int i=start;i<old.length();i++){ JSONObject e=old.optJSONObject(i); if(e!=null)next.put(e); }
        next.put(incident); save(app,next);
    }

    private static JSONArray load(Context app){
        try{
            SharedPreferences p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            JSONArray a=new JSONArray(p.getString(KEY,"[]"));
            if(a.length()<=MAX_PENDING)return a;
            JSONArray trimmed=new JSONArray();
            for(int i=a.length()-MAX_PENDING;i<a.length();i++){ JSONObject e=a.optJSONObject(i); if(e!=null)trimmed.put(e); }
            return trimmed;
        }catch(Exception e){return new JSONArray();}
    }

    private static void save(Context app,JSONArray a){ app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply(); }

    public static String sha256(String value){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest((value==null?"":value).getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(64); for(byte x:d)b.append(String.format(Locale.US,"%02x",x&0xff)); return b.toString();
        }catch(Exception e){return "0000000000000000";}
    }
    private static String safePackage(String v){ if(v==null)return ""; v=v.trim(); return v.substring(0,Math.min(253,v.length())); }
    private static String safeToken(String v,String fallback){
        String x=(v==null?"":v.trim().toUpperCase(Locale.ROOT)).replaceAll("[^A-Z0-9_\\-\\.:]","_");
        if(x.isEmpty())x=fallback; return x.substring(0,Math.min(48,x.length()));
    }
    private static double clamp(double v){ if(Double.isNaN(v)||Double.isInfinite(v))return 0.0; return Math.max(0.0,Math.min(1.0,v)); }
}
