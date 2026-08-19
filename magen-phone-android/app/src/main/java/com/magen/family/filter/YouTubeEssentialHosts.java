package com.magen.family.filter;

/**
 * Mainstream YouTube infrastructure that must never be blocked by the generic
 * adult/ad/tracker blocklists. Content protection for YouTube is enforced by
 * Restricted Mode, visible-text checks, Visual Shield, explicit app blocking,
 * and explicit VPS/manual rules.
 *
 * Important: this is intentionally evaluated AFTER explicit parent/VPS rules,
 * so an explicit block of YouTube still wins.
 */
public final class YouTubeEssentialHosts {
    private YouTubeEssentialHosts() {}

    public static boolean isEssential(String host) {
        if (host == null) return false;
        String h = HostUtil.normalizeHost(host);
        if (h.isEmpty()) return false;
        return suffix(h, "youtube.com")
            || suffix(h, "youtube-nocookie.com")
            || suffix(h, "googlevideo.com")
            || suffix(h, "ytimg.com")
            || suffix(h, "ggpht.com")
            || h.equals("youtubei.googleapis.com")
            || h.equals("youtube.googleapis.com");
    }

    private static boolean suffix(String host, String root) {
        return host.equals(root) || host.endsWith("." + root);
    }
}
