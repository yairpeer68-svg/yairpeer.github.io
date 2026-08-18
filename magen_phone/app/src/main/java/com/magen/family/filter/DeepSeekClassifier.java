package com.magen.family.filter;

import android.content.Context;
import com.magen.family.server.RemoteIntelligenceClient;
import com.magen.family.server.ServerConfig;

/**
 * Compatibility facade. DeepSeek is no longer contacted from Android and no API key is
 * stored in the APK/preferences. Contextual classification is proxied through the private
 * Magen VPS, where the DeepSeek key lives.
 */
public final class DeepSeekClassifier {
    private DeepSeekClassifier() {}
    public static boolean isEnabled(Context ctx) { return ServerConfig.ready(ctx); }
    public static Boolean classifyBlocking(Context ctx,String text) { return RemoteIntelligenceClient.classifyTextBlocking(ctx,text); }
    /** @deprecated DeepSeek keys belong on the VPS only. */
    @Deprecated public static String getKey(Context ctx) { return ""; }
    /** @deprecated Kept for source compatibility; only toggles VPS intelligence. */
    @Deprecated public static void save(Context ctx,String ignored,boolean enabled) { ServerConfig.setEnabled(ctx,enabled); }
    @Deprecated public static void clear(Context ctx) { ServerConfig.setEnabled(ctx,false); }
    @Deprecated public static boolean validate(String ignored) { return false; }
}
