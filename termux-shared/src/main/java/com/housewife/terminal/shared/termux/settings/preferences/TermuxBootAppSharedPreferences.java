package com.housewife.terminal.shared.termux.settings.preferences;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.housewife.terminal.shared.logger.Logger;
import com.housewife.terminal.shared.android.PackageUtils;
import com.housewife.terminal.shared.settings.preferences.AppSharedPreferences;
import com.housewife.terminal.shared.settings.preferences.SharedPreferenceUtils;
import com.housewife.terminal.shared.termux.TermuxUtils;
import com.housewife.terminal.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_BOOT_APP;
import com.housewife.terminal.shared.termux.TermuxConstants;

public class TermuxBootAppSharedPreferences extends AppSharedPreferences {

    private static final String LOG_TAG = "TermuxBootAppSharedPreferences";

    private TermuxBootAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));
    }

    /**
     * Get {@link TermuxBootAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_BOOT_PACKAGE_NAME}.
     * @return Returns the {@link TermuxBootAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static TermuxBootAppSharedPreferences build(@NonNull final Context context) {
        Context termuxBootPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME);
        if (termuxBootPackageContext == null)
            return null;
        else
            return new TermuxBootAppSharedPreferences(termuxBootPackageContext);
    }

    /**
     * Get {@link TermuxBootAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_BOOT_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link TermuxBootAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static TermuxBootAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context termuxBootPackageContext = TermuxUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME, exitAppOnError);
        if (termuxBootPackageContext == null)
            return null;
        else
            return new TermuxBootAppSharedPreferences(termuxBootPackageContext);
    }



    public int getLogLevel(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
        else
            return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel, boolean commitToFile) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, logLevel, commitToFile);
    }

}
