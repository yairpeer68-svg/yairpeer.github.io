package com.magen.family.server;

import android.content.Context;
import android.util.Base64;
import com.magen.family.BuildConfig;
import com.magen.family.R;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Minimal HTTPS client pinned to the private CA shipped with this Magen/VPS pair.
 * Redirects are disabled, responses are size-bounded, and only idempotent/operational
 * calls receive a small retry. Every retry gets a new signed nonce.
 */
public final class MagenApiClient {
    private static final int CONNECT_MS=6000;
    private static final int READ_MS=25000;
    private static final int MAX_JSON_RESPONSE=1024*1024;
    private static volatile javax.net.ssl.SSLSocketFactory sslFactory;
    private static final SecureRandom RNG=new SecureRandom();

    private MagenApiClient() {}

    private static final class HttpStatusException extends IOException {
        final int code;
        HttpStatusException(int code){ super("Magen server HTTP "+code); this.code=code; }
        boolean retryable(){ return code==408 || code==425 || code==429 || code==502 || code==503 || code==504; }
    }

    private static javax.net.ssl.SSLSocketFactory ssl(Context ctx) throws Exception {
        javax.net.ssl.SSLSocketFactory f=sslFactory; if(f!=null) return f;
        synchronized(MagenApiClient.class){
            if(sslFactory!=null) return sslFactory;
            CertificateFactory cf=CertificateFactory.getInstance("X.509");
            Certificate ca;
            try(InputStream in=ctx.getResources().openRawResource(R.raw.magen_server_ca)){ ca=cf.generateCertificate(in); }
            KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null); ks.setCertificateEntry("magen-ca",ca);
            TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(ks);
            SSLContext sc=SSLContext.getInstance("TLS"); sc.init(null,tmf.getTrustManagers(),new SecureRandom());
            sslFactory=sc.getSocketFactory(); return sslFactory;
        }
    }

    private static HttpsURLConnection open(Context c,String path) throws Exception {
        if(path==null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\')>=0)
            throw new SecurityException("invalid Magen server path");
        String base=ServerConfig.baseUrl(c);
        if(!base.startsWith("https://")) throw new SecurityException("Magen server requires HTTPS");
        URL target=new URL(base+path);
        HttpsURLConnection h=(HttpsURLConnection)target.openConnection();
        h.setSSLSocketFactory(ssl(c));
        h.setConnectTimeout(CONNECT_MS); h.setReadTimeout(READ_MS);
        h.setUseCaches(false); h.setInstanceFollowRedirects(false);
        h.setRequestProperty("Accept","application/json");
        h.setRequestProperty("Content-Type","application/json; charset=utf-8");
        h.setRequestProperty("User-Agent","MagenAndroid/"+BuildConfig.VERSION_NAME);
        h.setRequestProperty("Cache-Control","no-store");
        return h;
    }

    public static JSONObject enroll(Context c,String code,String name) throws Exception {
        DeviceIdentity.ensure();
        JSONObject body=new JSONObject().put("device_id",DeviceIdentity.deviceId(c)).put("name",name==null?"Android":name)
            .put("public_key_pem",DeviceIdentity.publicKeyPem()).put("enrollment_code",code==null?"":code.trim());
        return requestWithRetry(c,"POST","/v1/enroll",body,false,false,1);
    }

    public static JSONObject signedGet(Context c,String path,boolean verifyEnvelope) throws Exception {
        return requestWithRetry(c,"GET",path,null,true,verifyEnvelope,2);
    }

    public static JSONObject signedPost(Context c,String path,JSONObject body,boolean verifyEnvelope) throws Exception {
        int attempts=isOperationalIdempotentPost(path)?2:1;
        return requestWithRetry(c,"POST",path,body,true,verifyEnvelope,attempts);
    }

    private static boolean isOperationalIdempotentPost(String path){
        return "/v1/heartbeat".equals(path) || "/v1/events".equals(path) || "/v1/incidents".equals(path) || "/v1/recover".equals(path);
    }

    /** Authenticated raw download, used for the signed blocklist payload. */
    public static byte[] signedGetBytes(Context c,String path,int maxBytes,String accept) throws Exception {
        return getBytesWithRetry(c,path,maxBytes,accept,true,2);
    }

    /** Static server resources still use the pinned private CA; callers verify application signatures as needed. */
    public static JSONObject unsignedGetJson(Context c,String path,int maxBytes) throws Exception {
        byte[] raw=unsignedGetBytes(c,path,maxBytes,"application/json");
        return new JSONObject(new String(raw,StandardCharsets.UTF_8));
    }

    /** Download a static resource through the pinned TLS channel, with a hard byte limit. */
    public static byte[] unsignedGetBytes(Context c,String path,int maxBytes,String accept) throws Exception {
        return getBytesWithRetry(c,path,maxBytes,accept,false,2);
    }

    private static byte[] getBytesWithRetry(Context c,String path,int maxBytes,String accept,boolean signed,int attempts) throws Exception {
        Exception last=null;
        for(int attempt=1;attempt<=attempts;attempt++){
            HttpsURLConnection h=null;
            try{
                if(maxBytes<1) throw new IllegalArgumentException("maxBytes");
                h=open(c,path); h.setRequestMethod("GET");
                if(signed) applySignature(c,h,"GET",path,new byte[0]);
                if(accept!=null&&!accept.isEmpty()) h.setRequestProperty("Accept",accept);
                int code=h.getResponseCode(); InputStream in=code>=400?h.getErrorStream():h.getInputStream();
                byte[] out=readLimitedBytes(in,maxBytes);
                if(code<200||code>=300) throw new HttpStatusException(code);
                RuntimeHealthState.serverSuccess();
                return out;
            }catch(Exception e){
                last=e; RuntimeHealthState.serverFailure();
                if(attempt>=attempts || !isRetryable(e)) throw e;
                sleepBackoff(attempt);
            }finally{ if(h!=null) h.disconnect(); }
        }
        throw last==null?new IOException("Magen request failed"):last;
    }

    private static JSONObject requestWithRetry(Context c,String method,String path,JSONObject body,boolean signed,boolean verifyEnvelope,int attempts) throws Exception {
        Exception last=null;
        for(int attempt=1;attempt<=attempts;attempt++){
            try{
                JSONObject out=requestOnce(c,method,path,body,signed,verifyEnvelope);
                RuntimeHealthState.serverSuccess();
                return out;
            }catch(Exception e){
                last=e; RuntimeHealthState.serverFailure();
                if(attempt>=attempts || !isRetryable(e)) throw e;
                sleepBackoff(attempt);
            }
        }
        throw last==null?new IOException("Magen request failed"):last;
    }

    private static JSONObject requestOnce(Context c,String method,String path,JSONObject body,boolean signed,boolean verifyEnvelope) throws Exception {
        byte[] bodyBytes=(body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8));
        HttpsURLConnection h=null;
        try{
            h=open(c,path); h.setRequestMethod(method);
            if(signed) applySignature(c,h,method,path,bodyBytes);
            if(bodyBytes.length>0){
                h.setDoOutput(true); h.setFixedLengthStreamingMode(bodyBytes.length);
                try(OutputStream o=h.getOutputStream()){o.write(bodyBytes);}
            }
            int code=h.getResponseCode(); InputStream in=code>=400?h.getErrorStream():h.getInputStream();
            byte[] raw=readLimitedBytes(in,MAX_JSON_RESPONSE);
            if(code<200||code>=300) throw new HttpStatusException(code);
            JSONObject out=new JSONObject(new String(raw,StandardCharsets.UTF_8));
            return verifyEnvelope?ServerResponseVerifier.verifyEnvelope(out):out;
        } finally {
            if(h!=null) h.disconnect();
        }
    }

    private static void applySignature(Context c,HttpsURLConnection h,String method,String path,byte[] bodyBytes) throws Exception {
        long ts=System.currentTimeMillis()/1000L; String nonce=randomNonce();
        String canonical=method+"\n"+path+"\n"+ts+"\n"+nonce+"\n"+sha256Hex(bodyBytes);
        h.setRequestProperty("X-Magen-Device",DeviceIdentity.deviceId(c));
        h.setRequestProperty("X-Magen-Timestamp",Long.toString(ts));
        h.setRequestProperty("X-Magen-Nonce",nonce);
        h.setRequestProperty("X-Magen-Signature",DeviceIdentity.signBase64(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isRetryable(Exception e){
        if(e instanceof HttpStatusException) return ((HttpStatusException)e).retryable();
        return e instanceof IOException;
    }

    private static void sleepBackoff(int attempt){
        try{ Thread.sleep(Math.min(1200L,200L*(1L<<Math.min(3,attempt-1))) + RNG.nextInt(120)); }
        catch(InterruptedException ie){ Thread.currentThread().interrupt(); }
    }

    private static String randomNonce(){ byte[] b=new byte[18]; RNG.nextBytes(b); return Base64.encodeToString(b,Base64.NO_WRAP|Base64.URL_SAFE); }
    public static String sha256Hex(byte[] b) throws Exception { byte[] d=MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s=new StringBuilder(); for(byte x:d)s.append(String.format("%02x",x)); return s.toString(); }
    private static byte[] readLimitedBytes(InputStream in,int max) throws Exception {
        if(in==null)return new byte[0];
        try(InputStream closeable=in){
            ByteArrayOutputStream b=new ByteArrayOutputStream(Math.min(max,64*1024)); byte[] buf=new byte[8192]; int total=0,n;
            while((n=closeable.read(buf))!=-1){ total+=n; if(total>max)throw new IOException("response too large"); b.write(buf,0,n); }
            return b.toByteArray();
        }
    }
}
