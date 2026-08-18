package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Persistent local cache of VPS verdicts so protection keeps working offline. */
public final class ServerVerdictCache {
    public static final int NONE=-1, UNKNOWN=0, SAFE=1, BLOCK=2;
    private static final String PREFS="magen_server_verdicts";
    private ServerVerdictCache() {}
    private static SharedPreferences p(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static int get(Context c,String host){
        try{
            String raw=p(c).getString("v:"+host,null); if(raw==null)return NONE;
            JSONObject o=new JSONObject(raw); long exp=o.optLong("exp",0);
            if(exp<System.currentTimeMillis()){p(c).edit().remove("v:"+host).apply();return NONE;}
            String v=o.optString("verdict","UNKNOWN"); if("ADULT".equals(v))return BLOCK; if("SAFE".equals(v))return SAFE; return UNKNOWN;
        }catch(Exception e){return NONE;}
    }
    public static void put(Context c,String host,JSONObject payload){
        try{
            long ttl=Math.max(30,Math.min(30L*24*3600,payload.optLong("ttl_seconds",900)));
            JSONObject o=new JSONObject().put("verdict",payload.optString("verdict","UNKNOWN"))
                .put("category",payload.optString("category","UNKNOWN"))
                .put("confidence",payload.optDouble("confidence",0.0)).put("exp",System.currentTimeMillis()+ttl*1000L);
            p(c).edit().putString("v:"+host,o.toString()).apply();
        }catch(Exception ignored){}
    }
    public static void clear(Context c){p(c).edit().clear().apply();}
}
