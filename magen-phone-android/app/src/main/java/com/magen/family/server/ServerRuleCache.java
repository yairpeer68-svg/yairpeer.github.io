package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Signed manual VPS rules delivered with policy. Local parent allowlist still has top precedence. */
public final class ServerRuleCache {
    public static final int NONE=0, ALLOW=1, BLOCK=2;
    private static final String PREFS="magen_server_rules", K_ALLOW="allow", K_BLOCK="block";
    private ServerRuleCache(){}
    private static SharedPreferences p(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static void apply(Context c,JSONObject policy){
        Set<String> allow=read(policy.optJSONArray("server_allow_domains"));
        Set<String> block=read(policy.optJSONArray("server_block_domains"));
        p(c).edit().putStringSet(K_ALLOW,allow).putStringSet(K_BLOCK,block).commit();
    }
    private static Set<String> read(JSONArray a){
        Set<String> out=new HashSet<>(); if(a==null)return out;
        for(int i=0;i<a.length()&&i<2000;i++){
            String h=com.magen.family.filter.HostUtil.normalizeHost(a.optString(i,"")); if(!h.isEmpty())out.add(h);
        }
        return out;
    }
    public static int get(Context c,String host){
        String h=com.magen.family.filter.HostUtil.normalizeHost(host); if(h.isEmpty())return NONE;
        Set<String> allow=p(c).getStringSet(K_ALLOW,java.util.Collections.emptySet());
        Set<String> block=p(c).getStringSet(K_BLOCK,java.util.Collections.emptySet());
        String cur=h;
        while(true){
            if(allow.contains(cur))return ALLOW;
            if(block.contains(cur))return BLOCK;
            int dot=cur.indexOf('.'); if(dot<0)break; cur=cur.substring(dot+1);
        }
        return NONE;
    }
    public static void clear(Context c){p(c).edit().clear().commit();}
}
