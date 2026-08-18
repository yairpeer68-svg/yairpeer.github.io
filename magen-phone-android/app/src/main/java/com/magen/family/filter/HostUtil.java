package com.magen.family.filter;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/** Host/URL normalization shared by DNS, accessibility and URL filtering paths. */
public final class HostUtil {
    private HostUtil() {}

    public static String extractHost(String value) {
        if (value == null) return "";
        String raw = value.trim();
        if (raw.isEmpty()) return "";

        try {
            URI uri = new URI(raw);
            String host = uri.getHost();
            if (host != null) return normalizeHost(host);
        } catch (Exception ignored) {}

        // URI without a scheme: //example.com:443/path
        try {
            URI uri = new URI("//" + raw);
            String host = uri.getHost();
            if (host != null) return normalizeHost(host);
        } catch (Exception ignored) {}

        return normalizeHost(raw);
    }

    public static String normalizeHost(String value) {
        if (value == null) return "";
        String h = value.trim();
        if (h.isEmpty()) return "";

        // If a full URL reached this method, extract it first without recursing.
        int scheme = h.indexOf("://");
        if (scheme > 0) {
            try {
                String host = new URI(h).getHost();
                if (host != null) h = host;
            } catch (Exception ignored) {}
        }

        // Strip path/query/fragment from schemeless host strings.
        int cut = h.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = h.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        h = h.substring(0, cut).trim();

        // Bracketed IPv6, optionally with a port: [2001:db8::1]:443
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 1) return h.substring(1, end).toLowerCase(Locale.ROOT);
            return "";
        }

        // host:port, but never split a bare IPv6 literal containing multiple colons.
        int firstColon = h.indexOf(':');
        int lastColon = h.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String maybePort = h.substring(lastColon + 1);
            if (maybePort.matches("\\d{1,5}")) h = h.substring(0, lastColon);
        }

        h = h.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.isEmpty()) return "";

        // Do not run IP literals through IDN.
        if (h.indexOf(':') >= 0 || h.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return h;

        try {
            h = IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
        return h;
    }
}
