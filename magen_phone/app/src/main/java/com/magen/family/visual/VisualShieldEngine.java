package com.magen.family.visual;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.magen.family.server.ServerEventReporter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local visual shield: screenshot -> local AI -> full-screen + tiled decision -> temporal guard.
 * Privacy invariant: no Bitmap, HardwareBuffer or encoded image is ever passed to network code.
 */
public final class VisualShieldEngine implements AutoCloseable {
    private static final String TAG = "MagenVisualShield";
    private static final long ERROR_REPORT_COOLDOWN_MS = 60_000L;
    private static final long DUPLICATE_WINDOW_MS = 3_000L;

    public interface Callback { void onBlocked(String packageName, NsfwResult result); }

    private final AccessibilityService service;
    private final Callback callback;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MagenVisualAI"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean pendingScheduled = new AtomicBoolean(false);
    private final VisualTemporalGuard temporal = new VisualTemporalGuard();

    private volatile boolean closed;
    private volatile long lastScanAt;
    private volatile long lastErrorReportAt;
    private volatile long nextAllowedAfter;
    private volatile String pendingPackage = "";
    private volatile int pendingEventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    private volatile String lastHashPackage = "";
    private volatile long lastHash;
    private volatile long lastHashAt;
    private OnDeviceNsfwClassifier classifier;

    public VisualShieldEngine(AccessibilityService service, Callback callback) {
        this.service = service;
        this.callback = callback;
        worker.execute(() -> {
            try {
                classifier = new OnDeviceNsfwClassifier(service.getApplicationContext());
                VisualRuntimeState.modelReady();
                ServerEventReporter.report(service, "VISUAL_MODEL_READY", "INFO",
                    "runtime=litert-interpreter local_only=true temporal=true dedupe=dhash");
            } catch (Exception e) {
                VisualRuntimeState.modelUnavailable();
                VisualRuntimeState.failed();
                Log.e(TAG, "Visual model unavailable", e);
                reportUnavailable("MODEL_INIT:" + safe(e.getMessage()));
            }
        });
    }

    public void maybeScan(String pkg, int eventType) {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (pkg == null || pkg.isEmpty() || pkg.equals(service.getPackageName())) return;
        if (MagenVisualCurtain.isShowing()) return;
        if (!isScanEvent(eventType)) return;

        VisualPolicy.Config cfg = VisualPolicy.get(service);
        if (!cfg.enabled || "OFF".equalsIgnoreCase(cfg.mode)) return;

        long now = System.currentTimeMillis();
        if (now < nextAllowedAfter) return;
        long interval = effectiveInterval(cfg, eventType);
        long due = lastScanAt + interval;
        if (now < due || inFlight.get()) {
            pendingPackage = pkg;
            pendingEventType = eventType;
            schedulePending(Math.max(50L, due - now));
            return;
        }
        startCapture(pkg, cfg);
    }

    private void startCapture(String pkg, VisualPolicy.Config cfg) {
        if (closed || !inFlight.compareAndSet(false, true)) return;
        lastScanAt = System.currentTimeMillis();
        pendingPackage = "";
        capture(pkg, cfg);
    }

    private void capture(String pkg, VisualPolicy.Config cfg) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                        Bitmap software = null;
                        HardwareBuffer hb = null;
                        try {
                            hb = shot.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                            if (hw == null) throw new IllegalStateException("wrapHardwareBuffer returned null");
                            software = hw.copy(Bitmap.Config.ARGB_8888, false);
                            if (software == null) throw new IllegalStateException("screenshot copy failed");
                        } catch (Exception e) {
                            fail("CAPTURE_COPY:" + safe(e.getMessage()), cfg);
                        } finally {
                            if (hb != null) try { hb.close(); } catch (Exception ignored) {}
                        }

                        try {
                            if (software == null || classifier == null || closed) return;
                            long now = System.currentTimeMillis();
                            long hash = VisualFrameFingerprint.dHash(software);
                            if (pkg.equals(lastHashPackage) && lastHashAt > 0 &&
                                now - lastHashAt <= DUPLICATE_WINDOW_MS &&
                                VisualFrameFingerprint.hamming(hash, lastHash) <= cfg.duplicateHammingThreshold) {
                                VisualRuntimeState.duplicateSkipped();
                                return;
                            }
                            lastHashPackage = pkg;
                            lastHash = hash;
                            lastHashAt = now;

                            NsfwResult result = classifyScreen(software, cfg);
                            VisualRuntimeState.scanCompleted();
                            nextAllowedAfter = 0L;
                            if (result != null && temporal.observe(pkg, result, cfg, now)) {
                                VisualRuntimeState.blocked();
                                callback.onBlocked(pkg, result);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "classification failed", e);
                            fail("CLASSIFY:" + safe(e.getMessage()), cfg);
                        } finally {
                            if (software != null && !software.isRecycled()) software.recycle();
                            finishCapture();
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        fail("SCREENSHOT_ERROR:" + errorCode, cfg);
                        finishCapture();
                    }
                });
        } catch (Exception e) {
            fail("TAKE_SCREENSHOT:" + safe(e.getMessage()), cfg);
            finishCapture();
        }
    }

    /** Full screen first, then a 3x2 tiled scan of the content area. */
    private NsfwResult classifyScreen(Bitmap screen, VisualPolicy.Config cfg) {
        NsfwResult best = classifier.classify(screen, 0);
        if (best != null && VisualDecision.isImmediateBlock(best, cfg)) return best;

        int width = screen.getWidth(), height = screen.getHeight();
        if (width < 120 || height < 160) return best;
        int top = Math.max(0, Math.round(height * 0.08f));
        int bottom = Math.min(height, Math.round(height * 0.94f));
        int contentH = Math.max(1, bottom - top);
        int cols = 3, rows = 2;
        int limit = Math.min(cfg.maxTiles, cols * rows);
        int idx = 1;
        for (int r = 0; r < rows && idx <= limit; r++) {
            for (int c = 0; c < cols && idx <= limit; c++, idx++) {
                int x0 = c * width / cols;
                int x1 = (c + 1) * width / cols;
                int y0 = top + r * contentH / rows;
                int y1 = top + (r + 1) * contentH / rows;
                Bitmap tile = null;
                try {
                    tile = Bitmap.createBitmap(screen, x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
                    NsfwResult cur = classifier.classify(tile, idx);
                    if (cur != null && (best == null || VisualDecision.risk(cur) > VisualDecision.risk(best))) best = cur;
                    if (cur != null && VisualDecision.isImmediateBlock(cur, cfg)) return cur;
                } finally {
                    if (tile != null && !tile.isRecycled()) tile.recycle();
                }
            }
        }
        return best;
    }

    private void finishCapture() {
        inFlight.set(false);
        if (closed) return;
        String pkg = pendingPackage;
        if (pkg != null && !pkg.isEmpty()) schedulePending(80L);
    }

    private void schedulePending(long delayMs) {
        if (closed || !pendingScheduled.compareAndSet(false, true)) return;
        worker.schedule(() -> {
            pendingScheduled.set(false);
            if (closed || inFlight.get()) {
                if (!closed && pendingPackage != null && !pendingPackage.isEmpty()) schedulePending(120L);
                return;
            }
            String pkg = pendingPackage;
            int eventType = pendingEventType;
            pendingPackage = "";
            if (pkg == null || pkg.isEmpty()) return;
            VisualPolicy.Config cfg = VisualPolicy.get(service);
            if (!cfg.enabled || "OFF".equalsIgnoreCase(cfg.mode)) return;
            long now = System.currentTimeMillis();
            if (now < nextAllowedAfter) return;
            long wait = (lastScanAt + effectiveInterval(cfg, eventType)) - now;
            if (wait > 0) {
                pendingPackage = pkg;
                pendingEventType = eventType;
                schedulePending(wait);
                return;
            }
            startCapture(pkg, cfg);
        }, Math.max(20L, delayMs), TimeUnit.MILLISECONDS);
    }

    private long effectiveInterval(VisualPolicy.Config cfg, int eventType) {
        long interval = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ? cfg.burstIntervalMs : cfg.intervalMs;
        try {
            PowerManager pm = (PowerManager) service.getSystemService(AccessibilityService.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) interval = Math.max(interval, cfg.intervalMs * 2L);
        } catch (Exception ignored) {}
        return interval;
    }

    private static boolean isScanEvent(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED;
    }

    private void fail(String detail, VisualPolicy.Config cfg) {
        VisualRuntimeState.failed();
        int failures = VisualRuntimeState.consecutiveFailures();
        if (failures >= cfg.maxConsecutiveFailures) {
            int exponent = Math.min(6, failures - cfg.maxConsecutiveFailures);
            long backoff = Math.min(60_000L, 2_000L << exponent);
            nextAllowedAfter = System.currentTimeMillis() + backoff;
        }
        reportUnavailable(detail + " failures=" + failures);
    }

    private void reportUnavailable(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastErrorReportAt < ERROR_REPORT_COOLDOWN_MS) return;
        lastErrorReportAt = now;
        ServerEventReporter.report(service, "VISUAL_CAPTURE_UNAVAILABLE", "MEDIUM", detail);
    }

    private static String safe(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.substring(0, Math.min(160, s.length()));
    }

    @Override public void close() {
        closed = true;
        temporal.reset();
        worker.execute(() -> { if (classifier != null) classifier.close(); });
        worker.shutdown();
    }
}
