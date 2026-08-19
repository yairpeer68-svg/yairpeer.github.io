package com.magen.family.mitm;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import com.magen.family.server.MagenApiClient;
import com.magen.family.server.DeviceIdentity;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Requests short-lived exact-host leaf certificates from the enrolled VPS.
 * The EC P-256 private key is per-host/process-ephemeral and never leaves the phone.
 */
public final class MitmCertificateClient {
    public static final class Material {
        public final SSLContext sslContext; public final X509Certificate leaf; public final X509Certificate ca;
        Material(SSLContext s,X509Certificate l,X509Certificate c){sslContext=s;leaf=l;ca=c;}
    }
    private static final int MAX_CACHE=512;
    private static final Object LOCK=new Object();
    private static final Map<String,Entry> CACHE=new LinkedHashMap<String,Entry>(MAX_CACHE,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,MitmCertificateClient.Entry> e){return size()>MAX_CACHE;}};
    private static final class Entry{final Material m;final long expiresElapsed;final String caSha;Entry(Material m,long e,String s){this.m=m;this.expiresElapsed=e;this.caSha=s;}}
    private MitmCertificateClient(){}

    public static Material materialFor(Context c,String host) throws Exception {
        String h=host.toLowerCase(Locale.ROOT).trim(); long now=SystemClock.elapsedRealtime();
        String currentCa=MitmCaManager.fingerprint(c);
        synchronized(LOCK){Entry e=CACHE.get(h);if(e!=null&&e.expiresElapsed>now+60_000L&&e.caSha.equalsIgnoreCase(currentCa))return e.m;}
        KeyPair kp=generateKeyPair();
        String spki=Base64.encodeToString(kp.getPublic().getEncoded(),Base64.NO_WRAP);
        JSONObject body=new JSONObject().put("host",h).put("public_key_spki_b64",spki);
        JSONObject p=MagenApiClient.signedPost(c,"/v1/mitm/leaf",body,true);
        if(!"DEVICE_BOUND".equals(p.optString("scope","")))throw new SecurityException("leaf is not device-bound");
        if(!DeviceIdentity.deviceId(c).equals(p.optString("device_id","")))throw new SecurityException("leaf device mismatch");
        if(!h.equalsIgnoreCase(p.optString("host","")))throw new SecurityException("leaf host mismatch");
        byte[] leafDer=Base64.decode(p.optString("leaf_der_b64",""),Base64.DEFAULT);
        byte[] caDer=Base64.decode(p.optString("ca_der_b64",""),Base64.DEFAULT);
        if(leafDer.length<200||leafDer.length>8192||caDer.length<200||caDer.length>8192)throw new SecurityException("invalid leaf payload size");
        if(!sha256(caDer).equalsIgnoreCase(p.optString("ca_sha256","")))throw new SecurityException("leaf CA fingerprint mismatch");
        byte[] cached=MitmCaManager.cachedCa(c);
        if(cached==null||!MessageDigest.isEqual(cached,caDer)){
            // Signed server response indicates CA rotation. Managed devices can reinstall the new
            // anchor automatically; unmanaged devices are forced back to manual-confirmed=false.
            MitmCaManager.forceRefreshAsync(c);
            throw new SecurityException("leaf signed by unexpected CA");
        }
        CertificateFactory cf=CertificateFactory.getInstance("X.509");
        X509Certificate leaf=(X509Certificate)cf.generateCertificate(new ByteArrayInputStream(leafDer));
        X509Certificate ca=(X509Certificate)cf.generateCertificate(new ByteArrayInputStream(caDer));
        if(!MessageDigest.isEqual(leaf.getPublicKey().getEncoded(),kp.getPublic().getEncoded()))throw new SecurityException("leaf public key mismatch");
        leaf.verify(ca.getPublicKey()); leaf.checkValidity();
        if(leaf.getBasicConstraints()!=-1)throw new SecurityException("leaf certificate cannot be a CA");
        if(!leaf.getIssuerX500Principal().equals(ca.getSubjectX500Principal()))throw new SecurityException("leaf issuer mismatch");
        boolean[] leafKu=leaf.getKeyUsage(); if(leafKu!=null&&(leafKu.length<1||!leafKu[0]))throw new SecurityException("leaf missing digitalSignature");
        java.util.List<String> eku=leaf.getExtendedKeyUsage();
        if(eku==null||!eku.contains("1.3.6.1.5.5.7.3.1"))throw new SecurityException("leaf missing serverAuth");
        if(!sanContains(leaf,h))throw new SecurityException("leaf SAN mismatch");
        char[] pass=randomPassword(); KeyStore ks=KeyStore.getInstance("PKCS12");ks.load(null);
        ks.setKeyEntry("magen-leaf",kp.getPrivate(),pass,new Certificate[]{leaf,ca});
        KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,pass);
        SSLContext sc=SSLContext.getInstance("TLS");sc.init(kmf.getKeyManagers(),null,new SecureRandom());
        Material m=new Material(sc,leaf,ca); long ttl=Math.max(300L,Math.min(72L*3600L,p.optLong("ttl_seconds",3600L)));
        synchronized(LOCK){CACHE.put(h,new Entry(m,now+ttl*1000L,p.optString("ca_sha256","")));}
        MitmRuntimeState.certIssue(); return m;
    }

    public static void clearCache(){synchronized(LOCK){CACHE.clear();}}

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"),new SecureRandom());
        return g.generateKeyPair();
    }
    private static boolean sanContains(X509Certificate cert,String host) throws Exception {
        Collection<List<?>> sans=cert.getSubjectAlternativeNames();if(sans==null)return false;
        for(List<?> e:sans)if(e!=null&&e.size()>=2&&Integer.valueOf(2).equals(e.get(0))&&host.equalsIgnoreCase(String.valueOf(e.get(1))))return true;
        return false;
    }
    private static char[] randomPassword(){byte[] b=new byte[18];new SecureRandom().nextBytes(b);return Base64.encodeToString(b,Base64.NO_WRAP).toCharArray();}
    private static String sha256(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}
}
