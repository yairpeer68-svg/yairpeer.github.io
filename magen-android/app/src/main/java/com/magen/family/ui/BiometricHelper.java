package com.magen.family.ui;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import java.util.concurrent.Executor;

/**
 * כניסה עם טביעת אצבע / Face ID להורה
 */
public class BiometricHelper {

    public interface BiometricCallback {
        void onSuccess();
        void onFailed();
        void onNotAvailable();
    }

    /**
     * בדוק אם טביעת אצבע זמינה
     */
    public static boolean isAvailable(FragmentActivity activity) {
        BiometricManager manager = BiometricManager.from(activity);
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * הצג דיאלוג טביעת אצבע
     */
    public static void authenticate(FragmentActivity activity, BiometricCallback callback) {
        if (!isAvailable(activity)) {
            callback.onNotAvailable();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    callback.onSuccess();
                }

                @Override
                public void onAuthenticationFailed() {
                    callback.onFailed();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    callback.onFailed();
                }
            });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("אימות הורה 👆")
            .setSubtitle("השתמש בטביעת האצבע שלך")
            .setNegativeButtonText("השתמש בקוד")
            .build();

        prompt.authenticate(info);
    }
}
