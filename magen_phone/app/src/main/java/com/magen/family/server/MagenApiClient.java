package com.magen.family.server;

import android.content.Context;
import android.util.Base64;
import com.magen.family.R;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
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

/** Minimal HTTPS client pinned to the private CA shipped with this Magen/VPS pair. */
public final class MagenApiClient {
    private static final int CONNECT_MS=6000;
    private static final int READ_MS=25000;
    private static volatile javax.net.ssl.SSLSocketFactory sslFactory;
    private MagenApiClient() {}

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
        if(path==null || !path.startsWith("/") || path.startsWith("//"))
            throw new SecurityException("invalid Magen server path");
        String base=ServerConfig.baseUrl(c);
        if(!base.startsWith("https://")) throw new SecurityException("Magen server requires HTTPS");
        HttpsURLConnection h=(HttpsURLConnection)new URL(base+path).openConnection();
        h.setSSLSocketFactory(ssl(c)); h.setConnectTimeout(CONNECT_MS); h.setReadTimeout(READ_MS);
        h.setUseCaches(false); h.setRequestProperty("Accept","application/json"); h.setRequestProperty("Content-Type","application/json; charset=utf-8");
        return h;
    }

    public static JSONObject enroll(Context c,String code,String name) throws Exception {
        DeviceIdentity.ensure();
        JSONObject body=new JSONObject().put("device_id",DeviceIdentity.deviceId(c)).put("name",name==null?"Android":name)
            .put("public_key_pem",DeviceIdentity.publicKeyPem()).put("enrollment_code",code==null?"":code.trim());
        return request(c,"POST","/v1/enroll",body,false,false);
    }

    public static JSONObject signedGet(Context c,String path,boolean verifyEnvelope) throws Exception { return request(c,"GET",path,null,true,verifyEnvelope); }
    public static JSONObject signedPost(Context c,String path,JSONObject body,boolean verifyEnvelope) throws Exception { return request(c,"POST",path,body,true,verifyEnvelope); }

    /** Authenticated raw download, used for the signed blocklist payload. */
    public static byte[] signedGetBytes(Context c,String path,int maxBytes,String accept) throws Exception {
        if(maxBytes<1) throw new IllegalArgumentException("maxBytes");
        HttpsURLConnection h=open(c,path); h.setRequestMethod("GET");
        long ts=System.currentTimeMillis()/1000L; String nonce=randomNonce();
        byte[] bodyBytes=new byte[0];
        String canonical="GET\n"+path+"\n"+ts+"\n"+nonce+"\n"+sha256Hex(bodyBytes);
        h.setRequestProperty("X-Magen-Device",DeviceIdentity.deviceId(c));
        h.setRequestProperty("X-Magen-Timestamp",Long.toString(ts));
        h.setRequestProperty("X-Magen-Nonce",nonce);
        h.setRequestProperty("X-Magen-Signature",DeviceIdentity.signBase64(canonical.getBytes(StandardCharsets.UTF_8)));
        if(accept!=null&&!accept.isEmpty()) h.setRequestProperty("Accept",accept);
        int code=h.getResponseCode(); InputStream in=code>=400?h.getErrorStream():h.getInputStream();
        byte[] out=readLimitedBytes(in,maxBytes); h.disconnect();
        if(code<200||code>=300) throw new java.io.IOException("Magen server HTTP "+code);
        return out;
    }

    /** Static server resources still use the pinned private CA; callers verify application signatures as needed. */
    public static JSONObject unsignedGetJson(Context c,String path,int maxBytes) throws Exception {
        byte[] raw=unsignedGetBytes(c,path,maxBytes,"application/json");
        return new JSONObject(new String(raw,StandardCharsets.UTF_8));
    }

    /** Download a static resource through the pinned TLS channel, with a hard byte limit. */
    public static byte[] unsignedGetBytes(Context c,String path,int maxBytes,String accept) throws Exception {
        if(maxBytes<1) throw new IllegalArgumentException("maxBytes");
        HttpsURLConnection h=open(c,path); h.setRequestMethod("GET");
        if(accept!=null&&!accept.isEmpty()) h.setRequestProperty("Accept",accept);
        int code=h.getResponseCode();
        InputStream in=code>=400?h.getErrorStream():h.getInputStream();
        byte[] out=readLimitedBytes(in,maxBytes); h.disconnect();
        if(code<200||code>=300) throw new java.io.IOException("Magen server HTTP "+code);
        return out;
    }

    private static JSONObject request(Context c,String method,String path,JSONObject body,boolean signed,boolean verifyEnvelope) throws Exception {
        byte[] bodyBytes=(body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8));
        HttpsURLConnection h=open(c,path); h.setRequestMethod(method);
        if(signed){
            long ts=System.currentTimeMillis()/1000L; String nonce=randomNonce();
            String canonical=method+"\n"+path+"\n"+ts+"\n"+nonce+"\n"+sha256Hex(bodyBytes);
            h.setRequestProperty("X-Magen-Device",DeviceIdentity.deviceId(c)); h.setRequestProperty("X-Magen-Timestamp",Long.toString(ts));
            h.setRequestProperty("X-Magen-Nonce",nonce); h.setRequestProperty("X-Magen-Signature",DeviceIdentity.signBase64(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        if(bodyBytes.length>0){ h.setDoOutput(true); h.setFixedLengthStreamingMode(bodyBytes.length); try(OutputStream o=h.getOutputStream()){o.write(bodyBytes);} }
        int code=h.getResponseCode(); InputStream in=code>=400?h.getErrorStream():h.getInputStream(); byte[] raw=readLimitedBytes(in,1024*1024);
        h.disconnect(); String text=new String(raw,StandardCharsets.UTF_8);
        if(code<200||code>=300) throw new java.io.IOException("Magen server HTTP "+code+": "+text);
        JSONObject out=new JSONObject(text); return verifyEnvelope?ServerResponseVerifier.verifyEnvelope(out):out;
    }

    private static String randomNonce(){ byte[] b=new byte[18]; new SecureRandom().nextBytes(b); return Base64.encodeToString(b,Base64.NO_WRAP|Base64.URL_SAFE); }
    public static String sha256Hex(byte[] b) throws Exception { byte[] d=MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s=new StringBuilder(); for(byte x:d)s.append(String.format("%02x",x)); return s.toString(); }
    private static byte[] readLimitedBytes(InputStream in,int max) throws Exception {
        if(in==null)return new byte[0]; ByteArrayOutputStream b=new ByteArrayOutputStream(Math.min(max,64*1024)); byte[] buf=new byte[8192]; int total=0,n;
        while((n=in.read(buf))!=-1){ total+=n; if(total>max)throw new java.io.IOException("response too large"); b.write(buf,0,n);} return b.toByteArray();
    }
}
