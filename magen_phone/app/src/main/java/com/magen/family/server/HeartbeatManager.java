package com.magen.family.server;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.magen.family.BuildConfig;
import com.magen.family.R;
import com.magen.family.admin.MagenDeviceAdmin;
import com.magen.family.service.MagenVpnService;
import com.magen.family.visual.VisualPolicy;
import com.magen.family.visual.VisualRuntimeState;

import org.json.JSONObject;

public final class HeartbeatManager {
    private HeartbeatManager(){}

    public static boolean sendBlocking(Context c){
        if(!ServerConfig.ready(c))return false;
        try{
            DevicePolicyManager dpm=(DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            boolean admin=dpm!=null&&dpm.isAdminActive(new ComponentName(c,MagenDeviceAdmin.class));
            boolean accessibility=com.magen.family.util.AccessibilityState.isMagenEnabled(c);
            boolean overlay=Settings.canDrawOverlays(c);
            boolean powerSave=false;
            try{
                PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
                powerSave=pm!=null&&pm.isPowerSaveMode();
            }catch(Exception ignored){}
            VisualPolicy.Config visual=VisualPolicy.get(c);
            String buildId="";
            try{ buildId=c.getString(R.string.build_id); }catch(Exception ignored){}

            JSONObject b=new JSONObject()
                .put("app_version",BuildConfig.VERSION_NAME)
                .put("build_id",buildId)
                .put("sdk_int",Build.VERSION.SDK_INT)
                .put("vpn",MagenVpnService.isVpnRunning)
                .put("accessibility",accessibility)
                .put("device_admin",admin)
                .put("overlay",overlay)
                .put("power_save",powerSave)
                .put("policy_version",ServerConfig.policyVersion(c))
                .put("blocklist_version",0)
                .put("visual_enabled",visual.enabled)
                .put("visual_mode",visual.mode)
                .put("visual_model_ready",VisualRuntimeState.isModelReady())
                .put("visual_scans",VisualRuntimeState.scans())
                .put("visual_blocks",VisualRuntimeState.blocks())
                .put("visual_duplicate_skips",VisualRuntimeState.duplicateSkips())
                .put("visual_consecutive_failures",VisualRuntimeState.consecutiveFailures());
            MagenApiClient.signedPost(c,"/v1/heartbeat",b,false);
            ServerEventReporter.flushPendingAsync(c);
            return true;
        }catch(Exception e){return false;}
    }
}
