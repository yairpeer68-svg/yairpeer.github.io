package com.magen.family.util;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.magen.family.service.MagenAccessibilityService;

import java.util.List;

/**
 * Single source of truth for checking whether Magen's accessibility service is enabled.
 *
 * Package-name substring checks are intentionally avoided: they can return a false positive
 * when another service/component contains a similar package string. We compare the exact
 * package + service class and keep an exact ComponentName parser as a fallback for OEMs.
 */
public final class AccessibilityState {
    private AccessibilityState() {}

    public static boolean isMagenEnabled(Context context) {
        if (context == null) return false;

        try {
            AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null) {
                List<AccessibilityServiceInfo> enabled = manager
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (enabled != null) {
                    String packageName = context.getPackageName();
                    String serviceName = MagenAccessibilityService.class.getName();
                    for (AccessibilityServiceInfo info : enabled) {
                        if (info == null || info.getResolveInfo() == null
                                || info.getResolveInfo().serviceInfo == null) continue;
                        android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
                        if (packageName.equals(service.packageName)
                                && serviceName.equals(service.name)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the Secure-setting parser below.
        }

        try {
            String raw = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (raw == null || raw.trim().isEmpty()) return false;

            ComponentName expected = new ComponentName(
                context.getPackageName(), MagenAccessibilityService.class.getName());
            for (String flattened : raw.split(":")) {
                ComponentName current = ComponentName.unflattenFromString(flattened);
                if (expected.equals(current)) return true;
            }
        } catch (Exception ignored) {}

        return false;
    }
}
