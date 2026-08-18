package com.magen.family.server;

import android.util.Base64;
import com.magen.family.BuildConfig;
import org.json.JSONObject;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Verifies the VPS application-level signature, independent of TLS. */
public final class ServerResponseVerifier {
    private ServerResponseVerifier() {}
    public static JSONObject verifyEnvelope(JSONObject envelope) throws Exception {
        if(!"ECDSA_P256_SHA256".equals(envelope.optString("alg"))) throw new SecurityException("unexpected signature algorithm");
        byte[] raw=Base64.decode(envelope.getString("payload_b64"),Base64.DEFAULT);
        byte[] sig=Base64.decode(envelope.getString("signature"),Base64.DEFAULT);
        byte[] der=Base64.decode(BuildConfig.MAGEN_SERVER_SIGNING_PUB_B64,Base64.DEFAULT);
        PublicKey pub=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        Signature v=Signature.getInstance("SHA256withECDSA"); v.initVerify(pub); v.update(raw);
        if(!v.verify(sig)) throw new SecurityException("server signature invalid");
        JSONObject signedPayload=new JSONObject(new String(raw,"UTF-8"));
        // payload_b64 is authoritative. The duplicated payload field is only for human/API readability.
        return signedPayload;
    }
}
