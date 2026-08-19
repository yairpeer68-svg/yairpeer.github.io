package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent local cache of VPS verdicts so protection keeps working offline. */
public final class ServerVerdictCache {
    public static final int NONE=-1, UNKNOWN=0, SAFE=1, BLOCK=2;
    private static final String PREFS="magen_server_verdicts";
    private static final int MAX_ENTRIES=2500;
    private static final AtomicInteger WRITES=new AtomicInteger();
    private ServerVerdictCache() {}
    private static SharedPreferences p(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static int get(Context c,String host){
        try{
            String raw=p(c).getString("v:"+host,null); if(raw==null)return NONE;
            JSONObject o=new JSONObject(raw); long exp=o.optLong("exp",0);
            if(exp<System.currentTimeMillis()){p(c).edit().remove("v:"+host).apply();return NONE;}
            IntelligenceRuntimeState.cacheHit();
            String v=o.optString("verdict","UNKNOWN"); if("ADULT".equals(v))return BLOCK; if("SAFE".equals(v))return SAFE; return UNKNOWN;
        }catch(Exception e){return NONE;}
    }
    public static void put(Context c,String host,JSONObject payload){
        try{
            long ttl=Math.max(30,Math.min(30L*24*3600,payload.optLong("ttl_seconds",900)));
            long now=System.currentTimeMillis();
            JSONObject o=new JSONObject().put("verdict",payload.optString("verdict","UNKNOWN"))
                .put("category",payload.optString("category","UNKNOWN"))
                .put("confidence",payload.optDouble("confidence",0.0)).put("saved",now).put("exp",now+ttl*1000L);
            p(c).edit().putString("v:"+host,o.toString()).apply();
            if((WRITES.incrementAndGet() & 63)==0) prune(c);
        }catch(Exception ignored){}
    }

    /** Runs only on the background intelligence path; removes expired and oldest entries. */
    private static void prune(Context c){
        try{
            SharedPreferences prefs=p(c); Map<String,?> all=prefs.getAll(); long now=System.currentTimeMillis();
            SharedPreferences.Editor ed=prefs.edit();
            List<CacheEntry> live=new ArrayList<>();
            for(Map.Entry<String,?> e:all.entrySet()){
                if(!e.getKey().startsWith("v:") || !(e.getValue() instanceof String))continue;
                try{
                    JSONObject o=new JSONObject((String)e.getValue()); long exp=o.optLong("exp",0);
                    if(exp<=now){ ed.remove(e.getKey()); continue; }
                    live.add(new CacheEntry(e.getKey(),o.optLong("saved",exp)));
                }catch(Exception bad){ ed.remove(e.getKey()); }
            }
            if(live.size()>MAX_ENTRIES){
                live.sort(Comparator.comparingLong(x->x.saved));
                for(int i=0;i<live.size()-MAX_ENTRIES;i++)ed.remove(live.get(i).key);
            }
            ed.apply();
        }catch(Exception ignored){}
    }
    private static final class CacheEntry{ final String key; final long saved; CacheEntry(String k,long s){key=k;saved=s;} }
    public static void clear(Context c){p(c).edit().clear().apply();}
}
