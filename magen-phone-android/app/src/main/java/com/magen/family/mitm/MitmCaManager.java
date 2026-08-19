package com.magen.family.mitm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.security.KeyChain;
import android.util.Base64;
import android.util.Log;

import com.magen.family.admin.EnterpriseProtection;
import com.magen.family.admin.MagenDeviceAdmin;
import com.magen.family.server.MagenApiClient;
import com.magen.family.server.DeviceIdentity;
import com.magen.family.server.ServerConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Managed lifecycle for the dedicated HTTPS-inspection trust anchor. */
public final class MitmCaManager {
    private static final String TAG="MagenMitmCA";
    private static final String PREFS="magen_https_inspection";
    private static final String KEY_CA_B64="ca_der_b64";
    private static final String KEY_CA_SHA="ca_sha256";
    private static final String KEY_MANUAL="manual_trust_confirmed";
    private static final String KEY_LAST_REFRESH="ca_last_refresh_elapsed";
    private static final long REFRESH_INTERVAL_MS=60L*60L*1000L;
    private static final AtomicBoolean REFRESHING=new AtomicBoolean(false);
    private static final ExecutorService EXEC=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MagenMitmCA");t.setDaemon(true);return t;});
    private MitmCaManager(){}

    public static void ensureManagedCaAsync(Context c){ scheduleRefresh(c,false); }
    public static void forceRefreshAsync(Context c){ scheduleRefresh(c,true); }

    private static void scheduleRefresh(Context c,boolean force){
        Context app=c.getApplicationContext();
        if(!ServerConfig.ready(app))return;
        android.content.SharedPreferences sp=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=SystemClock.elapsedRealtime();
        long last=sp.getLong(KEY_LAST_REFRESH,0L);
        if(!force&&last>0L&&now-last<REFRESH_INTERVAL_MS)return;
        if(!REFRESHING.compareAndSet(false,true))return;
        EXEC.execute(()->{
            try{
                if(EnterpriseProtection.isManagedOwner(app))ensureManagedCaBlocking(app);
                else fetchCa(app); // refresh public metadata; a rotation resets manual trust confirmation
                sp.edit().putLong(KEY_LAST_REFRESH,SystemClock.elapsedRealtime()).apply();
            }catch(Exception e){Log.w(TAG,"CA refresh failed: "+e.getClass().getSimpleName());}
            finally{REFRESHING.set(false);}
        });
    }

    public static boolean ensureManagedCaBlocking(Context c) throws Exception {
        Context app=c.getApplicationContext();
        if(!EnterpriseProtection.isManagedOwner(app))return false;
        // Preserve the previous anchor BEFORE fetchCa() updates public metadata. Install the new
        // anchor first, verify it, and only then remove the old one so rotation has no trust gap.
        byte[] old=cachedCa(app);
        byte[] fresh=fetchCa(app);
        DevicePolicyManager dpm=(DevicePolicyManager)app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin=MagenDeviceAdmin.getComponentName(app);
        if(dpm==null)return false;
        if(!dpm.hasCaCertInstalled(admin,fresh)){
            if(!dpm.installCaCert(admin,fresh))return false;
        }
        if(!dpm.hasCaCertInstalled(admin,fresh))return false;
        if(old!=null&&!MessageDigest.isEqual(old,fresh)){
            try{if(dpm.hasCaCertInstalled(admin,old))dpm.uninstallCaCert(admin,old);}catch(Exception ignored){}
            MitmCertificateClient.clearCache();
            MitmPolicy.clearFallbacks(app);
        }
        cache(app,fresh);
        app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MANUAL,false)
            .putLong(KEY_LAST_REFRESH,SystemClock.elapsedRealtime()).apply();
        return true;
    }

    public static boolean isTrustedForInspection(Context c){
        Context app=c.getApplicationContext(); byte[] ca=cachedCa(app); if(ca==null)return false;
        try{
            DevicePolicyManager dpm=(DevicePolicyManager)app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin=MagenDeviceAdmin.getComponentName(app);
            if(dpm!=null&&EnterpriseProtection.isManagedOwner(app))return dpm.hasCaCertInstalled(admin,ca);
        }catch(Exception ignored){}
        return app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEY_MANUAL,false);
    }

    public static String statusText(Context c){
        if(!MitmPolicy.enabled(c))return "HTTPS Inspection כבוי";
        if(isTrustedForInspection(c))return EnterpriseProtection.isManagedOwner(c) ? "✓ CA מנוהל מותקן — HTTPS Inspection מוכן" : "✓ CA סומן כמותקן ידנית — HTTPS Inspection פעיל";
        if(EnterpriseProtection.isManagedOwner(c))return "CA עדיין לא הותקן — נסה רענון";
        return "נדרש Device Owner או התקנה ידנית של CA";
    }

    public static void setManualTrustConfirmed(Context c,boolean confirmed){
        c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEY_MANUAL,confirmed).apply();
    }

    public static byte[] cachedCa(Context c){
        try{String b=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_CA_B64,"");return b.isEmpty()?null:Base64.decode(b,Base64.DEFAULT);}catch(Exception e){return null;}
    }

    public static X509Certificate cachedCertificate(Context c){
        try{byte[] b=cachedCa(c);if(b==null)return null;return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(b));}catch(Exception e){return null;}
    }

    public static byte[] fetchCa(Context c) throws Exception {
        JSONObject p=MagenApiClient.signedGet(c,"/v1/mitm/ca",true);
        if(!"DEVICE_BOUND".equals(p.optString("scope","")))throw new SecurityException("inspection CA is not device-bound");
        if(!DeviceIdentity.deviceId(c).equals(p.optString("device_id","")))throw new SecurityException("inspection CA device mismatch");
        String b64=p.optString("ca_der_b64",""); String expected=p.optString("sha256","").toLowerCase(Locale.ROOT);
        byte[] der=Base64.decode(b64,Base64.DEFAULT);
        if(der.length<200||der.length>8192)throw new SecurityException("invalid CA size");
        String actual=sha256(der); if(!actual.equals(expected))throw new SecurityException("CA fingerprint mismatch");
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
        cert.checkValidity();
        if(cert.getBasicConstraints()!=0)throw new SecurityException("inspection certificate must be a pathlen=0 CA");
        if(!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()))throw new SecurityException("inspection CA must be self-signed");
        cert.verify(cert.getPublicKey());
        if(!(cert.getPublicKey() instanceof ECPublicKey)||((ECPublicKey)cert.getPublicKey()).getParams().getCurve().getField().getFieldSize()!=256)
            throw new SecurityException("inspection CA must use EC P-256");
        boolean[] ku=cert.getKeyUsage(); if(ku!=null&&(ku.length<6||!ku[5]))throw new SecurityException("inspection CA cannot sign certificates");
        cache(c,der); return der;
    }

    /**
     * Unmanaged devices: Android 10 and older can show the platform certificate installer.
     * Android 11+ requires the user to complete CA installation from system Settings.
     */
    public static boolean offerManualInstall(Context c) throws Exception {
        Context app=c.getApplicationContext(); byte[] der=cachedCa(app); if(der==null)der=fetchCa(app);
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.R){
            Intent i=KeyChain.createInstallIntent();
            i.putExtra(KeyChain.EXTRA_CERTIFICATE,der);
            i.putExtra(KeyChain.EXTRA_NAME,"Magen Managed HTTPS Inspection CA");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        }
        exportCaToDownloads(app);
        openSecuritySettings(app);
        return false;
    }

    /** Android 11+ manual path exports the public CA only; the private CA key never leaves the VPS. */
    public static Uri exportCaToDownloads(Context c) throws Exception {
        Context app=c.getApplicationContext(); byte[] der=cachedCa(app); if(der==null)der=fetchCa(app);
        String name="magen-managed-https-inspection-ca.cer";
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/x-x509-ca-cert");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new java.io.IOException("cannot create CA download");
            try(OutputStream out=app.getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException("cannot open CA download");out.write(der);}return uri;
        }
        File dir=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(dir==null)throw new java.io.IOException("external files unavailable");
        File f=new File(dir,name);try(OutputStream out=new FileOutputStream(f)){out.write(der);}return Uri.fromFile(f);
    }

    public static void openSecuritySettings(Context c){
        try{Intent i=new Intent(Settings.ACTION_SECURITY_SETTINGS);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}catch(Exception ignored){}
    }

    private static void cache(Context c,byte[] der){
        Context app=c.getApplicationContext(); android.content.SharedPreferences sp=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String next=sha256(der), prev=sp.getString(KEY_CA_SHA,"");
        android.content.SharedPreferences.Editor e=sp.edit()
            .putString(KEY_CA_B64,Base64.encodeToString(der,Base64.NO_WRAP)).putString(KEY_CA_SHA,next);
        if(!prev.isEmpty()&&!prev.equals(next))e.putBoolean(KEY_MANUAL,false);
        e.apply();
    }
    public static String fingerprint(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_CA_SHA,"");}
    private static String sha256(byte[] b){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}catch(Exception e){return "";}}
}
