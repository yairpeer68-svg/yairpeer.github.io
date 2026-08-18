package com.magen.family.server;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

/** Applies only signature-verified, monotonic server policy versions. */
public final class PolicySyncManager {
    private static final String TAG="MagenPolicy";
    private PolicySyncManager(){}

    public static boolean syncBlocking(Context c){
        if(!ServerConfig.ready(c))return false;
        try{
            JSONObject p=MagenApiClient.signedGet(c,"/v1/policy",true);
            int incoming=p.optInt("version",0);
            int current=ServerConfig.policyVersion(c);
            if(incoming<=0){
                Log.w(TAG,"policy rejected: invalid version");
                return false;
            }
            if(incoming<current){
                Log.w(TAG,"policy rollback rejected: incoming="+incoming+" current="+current);
                return false;
            }
            ServerConfig.applyPolicy(c,p.optBoolean("strict_unknown",true),incoming);
            com.magen.family.visual.VisualPolicy.applySignedPolicy(c,p);
            ServerRuleCache.apply(c,p);
            com.magen.family.filter.DomainVerdict.clearCache();
            return true;
        }catch(Exception e){Log.w(TAG,"policy sync failed: "+e.getMessage());return false;}
    }
}
