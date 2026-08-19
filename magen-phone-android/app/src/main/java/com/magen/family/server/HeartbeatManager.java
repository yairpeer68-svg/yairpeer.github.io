package com.magen.family.server;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.magen.family.BuildConfig;
import com.magen.family.R;
import com.magen.family.admin.MagenDeviceAdmin;
import com.magen.family.service.MagenVpnService;
import com.magen.family.service.ServiceRevival;
import com.magen.family.mitm.HttpsInspectionProxy;
import com.magen.family.mitm.MitmCaManager;
import com.magen.family.mitm.MitmPolicy;
import com.magen.family.mitm.MitmRuntimeState;
import com.magen.family.visual.VisualPolicy;
import com.magen.family.visual.VisualRuntimeState;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

public final class HeartbeatManager {
    private static final AtomicLong SEQ = new AtomicLong(0L);
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
            boolean vpnRunning=MagenVpnService.isVpnRunning;
            if(!vpnRunning){
                // Safe self-heal: only asks Android to restart our already-authorized VPN.
                // If VPN consent is missing ServiceRevival leaves it untouched and heartbeat reports DOWN.
                ServiceRevival.reviveVpn(c);
            }
            String buildId="";
            try{ buildId=c.getString(R.string.build_id); }catch(Exception ignored){}

            JSONObject b=new JSONObject()
                .put("app_version",BuildConfig.VERSION_NAME)
                .put("build_id",buildId)
                .put("sdk_int",Build.VERSION.SDK_INT)
                .put("vpn",vpnRunning)
                .put("accessibility",accessibility)
                .put("device_admin",admin)
                .put("overlay",overlay)
                .put("power_save",powerSave)
                .put("policy_version",ServerConfig.policyVersion(c))
                .put("blocklist_version",com.magen.family.service.RemoteBlocklist.loadedVersion())
                .put("visual_enabled",visual.enabled)
                .put("visual_mode",visual.mode)
                .put("visual_model_ready",VisualRuntimeState.isModelReady())
                .put("visual_scans",VisualRuntimeState.scans())
                .put("visual_blocks",VisualRuntimeState.blocks())
                .put("visual_duplicate_skips",VisualRuntimeState.duplicateSkips())
                .put("visual_consecutive_failures",VisualRuntimeState.consecutiveFailures())
                .put("heartbeat_seq",SEQ.incrementAndGet())
                .put("process_uptime_ms",RealtimeHealthReporter.processUptimeMs())
                .put("memory_used_mb",memoryUsedMb())
                .put("memory_max_mb",memoryMaxMb())
                .put("battery_pct",batteryPct(c))
                .put("network_validated",networkValidated(c))
                .put("process_instance_id",RuntimeHealthState.processInstanceId())
                .put("server_failure_streak",RuntimeHealthState.serverFailureStreak())
                .put("last_server_success_age_ms",RuntimeHealthState.lastServerSuccessAgeMs())
                .put("vpn_restart_count",RuntimeHealthState.vpnRestartCount())
                .put("full_tunnel",com.magen.family.service.vpn.VpnPolicy.fullTunnel())
                .put("device_owner",com.magen.family.admin.EnterpriseProtection.isManagedOwner(c))
                .put("blocklist_loaded_domains",com.magen.family.service.RemoteBlocklist.loadedCount())
                .put("blocklist_source",com.magen.family.service.RemoteBlocklist.loadedSource())
                .put("intel_domain_requests",IntelligenceRuntimeState.domainRequests())
                .put("intel_text_requests",IntelligenceRuntimeState.textRequests())
                .put("intel_cache_hits",IntelligenceRuntimeState.cacheHits())
                .put("intel_blocks",IntelligenceRuntimeState.blocks())
                .put("intel_failures",IntelligenceRuntimeState.failures())
                .put("incident_queue_depth",ContentIncidentReporter.pendingCount(c))
                .put("mitm_enabled",MitmPolicy.enabled(c))
                .put("mitm_ca_trusted",MitmCaManager.isTrustedForInspection(c))
                .put("mitm_proxy_running",HttpsInspectionProxy.get(c).isRunning())
                .put("mitm_connections",MitmRuntimeState.proxyConnections())
                .put("mitm_intercepted",MitmRuntimeState.interceptions())
                .put("mitm_tunneled",MitmRuntimeState.tunnels())
                .put("mitm_blocks",MitmRuntimeState.blocks())
                .put("mitm_cert_issued",MitmRuntimeState.certsIssued())
                .put("mitm_fallback",MitmRuntimeState.fallbacks())
                .put("mitm_failures",MitmRuntimeState.failures());
            MagenApiClient.signedPost(c,"/v1/heartbeat",b,false);
            ServerEventReporter.flushPendingAsync(c);
            ContentIncidentReporter.flushPendingAsync(c);
            return true;
        }catch(Exception e){return false;}
    }
    private static int memoryUsedMb(){
        Runtime r=Runtime.getRuntime();
        return (int)Math.max(0L,(r.totalMemory()-r.freeMemory())/(1024L*1024L));
    }
    private static int memoryMaxMb(){ return (int)Math.max(0L,Runtime.getRuntime().maxMemory()/(1024L*1024L)); }
    private static int batteryPct(Context c){
        try{
            Intent i=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(i==null)return -1; int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            return level>=0&&scale>0?(int)Math.round(level*100.0/scale):-1;
        }catch(Exception e){return -1;}
    }
    private static boolean networkValidated(Context c){
        try{
            ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return false; NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc!=null&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }catch(Exception e){return false;}
    }

}
