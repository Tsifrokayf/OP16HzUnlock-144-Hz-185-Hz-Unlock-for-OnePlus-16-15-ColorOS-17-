package com.op16.hzunlock;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RefreshRateUnlockHook implements IXposedHookLoadPackage {
    private static final String TAG = "[OP16HzUnlock] ";

    private static final String KEY_REFRESH_MODE = "oplus_customize_screen_refresh_rate";
    private static final String KEY_CHOICE_RATE = "choice_mode_rate";

    private static boolean isUnlockedHighMode(int mode) {
        return mode == 4 || mode == 7 || mode == 8;
    }

    private static boolean isHighRefreshMode(int mode) {
        return mode == 3 || mode == 4 || mode == 7 || mode == 8;
    }

    private static int hzToRateIndex(int hz) {
        switch (hz) {
            case 60: return 2;
            case 90: return 1;
            case 120: return 3;
            case 144: return 4;
            case 165: return 7;
            case 185: return 8;
            default: return 3;
        }
    }

    private static float rateIndexToHz(int mode) {
        switch (mode) {
            case 1: return 90.0f;
            case 2: return 60.0f;
            case 3: return 120.0f;
            case 4: return 144.0f;
            case 7: return 165.0f;
            case 8: return 185.0f;
            default: return 0.0f;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            hookSystemServer(lpparam.classLoader);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            hookSettings(lpparam.classLoader);
        }
    }

    private void hookSystemServer(ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking system_server refresh rate policy...");

        // 1. OplusRefreshRateConfigs.getDefaultRateId(int pickMode)
        // Map 4->4 (144Hz), 7->7 (165Hz), 8->8 (185Hz) instead of stock 3 (120Hz)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRateConfigs", cl,
                "getDefaultRateId", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int pickMode = (Integer) param.args[0];
                        if (isUnlockedHighMode(pickMode)) {
                            param.setResult(pickMode);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook getDefaultRateId failed: " + t);
        }

        // 2. OplusRefreshRatePolicyImpl.forceMaxRefreshRateSettings(int refreshId)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl,
                "forceMaxRefreshRateSettings", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int refreshId = (Integer) param.args[0];
                        if (isUnlockedHighMode(refreshId)) {
                            param.setResult(refreshId);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook forceMaxRefreshRateSettings failed: " + t);
        }

        // 3a. OplusRefreshRatePolicyImpl$PickRefreshRateData.getPickPreferredId()
        // Hook inner class directly (never inlined into outer class)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl$PickRefreshRateData", cl,
                "getPickPreferredId",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object outer = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            if (outer == null) return;
                            int settingMode = (Integer) XposedHelpers.callMethod(outer, "getCurrentSettingMode");
                            if (!isUnlockedHighMode(settingMode)) {
                                return;
                            }
                            int orig = (Integer) param.getResult();
                            if (orig == 6) {
                                return;
                            }
                            try {
                                Object cam = XposedHelpers.callMethod(outer, "isCameraOpening");
                                if (Boolean.TRUE.equals(cam)) {
                                    return;
                                }
                            } catch (Throwable ignored) {
                            }
                            param.setResult(settingMode);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "getPickPreferredId error: " + t);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook getPickPreferredId failed: " + t);
        }

        // 3b. OplusRefreshRatePolicyImpl.getWindowPreferredModeId(int, DisplayInfo)
        try {
            Class<?> displayInfoClass = XposedHelpers.findClass("android.view.DisplayInfo", cl);
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl,
                "getWindowPreferredModeId", int.class, displayInfoClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int settingMode = (Integer) XposedHelpers.callMethod(param.thisObject, "getCurrentSettingMode");
                            if (!isUnlockedHighMode(settingMode)) {
                                return;
                            }
                            int orig = (Integer) param.args[0];
                            if (orig == 6) {
                                return;
                            }
                            try {
                                Object cam = XposedHelpers.callMethod(param.thisObject, "isCameraOpening");
                                if (Boolean.TRUE.equals(cam)) {
                                    return;
                                }
                            } catch (Throwable ignored) {
                            }
                            param.args[0] = settingMode;
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "getWindowPreferredModeId error: " + t);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook getWindowPreferredModeId failed: " + t);
        }

        // 4. OplusRefreshRatePolicyImpl.adjustRefreshForDefaultMax(int result, String reason)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl,
                "adjustRefreshForDefaultMax", int.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int settingMode = (Integer) XposedHelpers.callMethod(param.thisObject, "getCurrentSettingMode");
                            if (isUnlockedHighMode(settingMode)) {
                                param.setResult(param.args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook adjustRefreshForDefaultMax failed: " + t);
        }

        // 5. OplusRefreshRatePolicyImpl.getMaxRateIdInLimit144()
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl,
                "getMaxRateIdInLimit144",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int settingMode = (Integer) XposedHelpers.callMethod(param.thisObject, "getCurrentSettingMode");
                            if (isUnlockedHighMode(settingMode)) {
                                param.setResult(settingMode);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook getMaxRateIdInLimit144 failed: " + t);
        }

        // 6. OplusRefreshRatePolicyImpl$SettingsObserver.updatePeakRefreshRate()
        // Keep peak_refresh_rate and min_refresh_rate synced to the exact chosen High Hz (120/144/165/185)
        // so DisplayModeDirector does not clamp primaryRanges.render to 60 Hz via PRIORITY_DEFAULT_REFRESH_RATE.
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl$SettingsObserver", cl,
                "updatePeakRefreshRate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int pickMode = XposedHelpers.getIntField(param.thisObject, "mCurrentPickMode");
                            Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (ctx == null) {
                                return;
                            }
                            ContentResolver cr = ctx.getContentResolver();
                            if (isHighRefreshMode(pickMode)) {
                                float targetHz = rateIndexToHz(pickMode);
                                if (targetHz > 0.0f) {
                                    XposedHelpers.setFloatField(param.thisObject, "mOverridePeakRefreshRate", targetHz);
                                    Settings.System.putFloat(cr, "peak_refresh_rate", targetHz);
                                    Settings.System.putFloat(cr, "min_refresh_rate", targetHz);
                                    param.setResult(null);
                                }
                            } else if (pickMode == 2) {
                                // Standard 60 Hz: clear sticky override so stock drops back to 60 Hz
                                XposedHelpers.setFloatField(param.thisObject, "mOverridePeakRefreshRate", 0.0f);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "updatePeakRefreshRate error: " + t);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook updatePeakRefreshRate failed: " + t);
        }

        // 7. OplusRefreshRatePolicyImpl$SettingsObserver.resetMinRefreshRate()
        // Prevent ColorOS from resetting min_refresh_rate back to 0.0f when a High Hz mode is selected.
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.OplusRefreshRatePolicyImpl$SettingsObserver", cl,
                "resetMinRefreshRate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int pickMode = XposedHelpers.getIntField(param.thisObject, "mCurrentPickMode");
                            if (!isHighRefreshMode(pickMode)) {
                                return;
                            }
                            float targetHz = rateIndexToHz(pickMode);
                            Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (ctx != null && targetHz > 0.0f) {
                                ContentResolver cr = ctx.getContentResolver();
                                float cur = Settings.System.getFloat(cr, "min_refresh_rate", 0.0f);
                                if (Math.abs(cur - targetHz) > 0.5f) {
                                    Settings.System.putFloat(cr, "min_refresh_rate", targetHz);
                                }
                            }
                            param.setResult(null);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "resetMinRefreshRate error: " + t);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook resetMinRefreshRate failed: " + t);
        }

        XposedBridge.log(TAG + "system_server hooks installed");
    }

    private void hookSettings(final ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking com.android.settings refresh rate UI...");

        final String utilsName = "com.oplus.settings.feature.display.ScreenRefreshUtils";
        final String fragName = "com.oplus.settings.feature.display.ScreenRefreshRateFragment";

        // 1. Report 185 Hz as extreme refresh level rate
        try {
            XposedHelpers.findAndHookMethod(utilsName, cl, "getExtremeRefreshLevelRate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(185);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook getExtremeRefreshLevelRate failed: " + t);
        }

        // 2. Preserve selected 120/144/165/185 rate when tapping High refresh rate
        try {
            XposedHelpers.findAndHookMethod(utilsName, cl, "normalizeHighRefreshGear", Context.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.args[0];
                        int mode = (Integer) param.args[1];
                        if (mode >= 3 && ctx != null) {
                            int choice = Settings.Secure.getInt(ctx.getContentResolver(), KEY_CHOICE_RATE, 165);
                            if (choice != 120 && choice != 144 && choice != 165 && choice != 185) {
                                choice = 165;
                            }
                            param.setResult(hzToRateIndex(choice));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "normalizeHighRefreshGear error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook normalizeHighRefreshGear failed: " + t);
        }

        // 3. setChoiceModeRate(Context, int hz) -> write choice_mode_rate, oplus_customize_screen_refresh_rate, and sync min/peak_refresh_rate
        try {
            XposedHelpers.findAndHookMethod(utilsName, cl, "setChoiceModeRate", Context.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.args[0];
                        int hz = (Integer) param.args[1];
                        int modeIdx = hzToRateIndex(hz);
                        ContentResolver cr = ctx.getContentResolver();
                        Settings.Secure.putInt(cr, KEY_CHOICE_RATE, hz);
                        Settings.Secure.putInt(cr, KEY_REFRESH_MODE, modeIdx);
                        try {
                            Settings.System.putFloat(cr, "peak_refresh_rate", (float) hz);
                            Settings.System.putFloat(cr, "min_refresh_rate", (float) hz);
                        } catch (Throwable ignored) {
                        }
                        param.setResult(modeIdx);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "setChoiceModeRate error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook setChoiceModeRate failed: " + t);
        }

        // 4. setRateMode(Context, int) -> sync min/peak_refresh_rate when switching between Auto / 60Hz / High
        try {
            XposedHelpers.findAndHookMethod(utilsName, cl, "setRateMode", Context.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.args[0];
                        int appliedMode = (Integer) param.getResult();
                        ContentResolver cr = ctx.getContentResolver();
                        if (appliedMode == 0) {
                            // Auto
                            Settings.System.putFloat(cr, "min_refresh_rate", 0.0f);
                            Settings.System.putFloat(cr, "peak_refresh_rate", 185.0f);
                        } else if (appliedMode == 2) {
                            // Standard 60Hz
                            Settings.System.putFloat(cr, "min_refresh_rate", 0.0f);
                            Settings.System.putFloat(cr, "peak_refresh_rate", 60.0f);
                        } else {
                            float hz = rateIndexToHz(appliedMode);
                            if (hz > 0.0f) {
                                Settings.System.putFloat(cr, "peak_refresh_rate", hz);
                                Settings.System.putFloat(cr, "min_refresh_rate", hz);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook setRateMode failed: " + t);
        }

        // 5. setupRefreshRateSettingEntries(int) -> populate 120 / 144 / 165 / 185 Hz in ColorOS menu
        try {
            XposedHelpers.findAndHookMethod(fragName, cl, "setupRefreshRateSettingEntries", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    try {
                        Object pref = XposedHelpers.getObjectField(param.thisObject, "mRefreshRateSettingPreference");
                        if (pref == null) {
                            return;
                        }
                        String[] entries = new String[]{
                            "120 Гц",
                            "144 Гц",
                            "165 Гц",
                            "185 Гц"
                        };
                        String[] values = new String[]{"120", "144", "165", "185"};
                        XposedHelpers.callMethod(pref, "setEntries", (Object) entries);
                        XposedHelpers.callMethod(pref, "setEntryValues", (Object) values);

                        Class<?> listenerIface = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", cl);
                        Object listener = Proxy.newProxyInstance(
                            cl,
                            new Class<?>[]{listenerIface},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) {
                                    if ("onPreferenceChange".equals(method.getName()) && args != null && args.length >= 2) {
                                        try {
                                            int hz = Integer.parseInt(String.valueOf(args[1]));
                                            XposedHelpers.callMethod(param.thisObject, "applyRefreshRateSetting", hz);
                                            return Boolean.TRUE;
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + "onPreferenceChange error: " + t);
                                            return Boolean.FALSE;
                                        }
                                    }
                                    return null;
                                }
                            }
                        );
                        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", listener);
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "setupRefreshRateSettingEntries error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook setupRefreshRateSettingEntries failed: " + t);
        }

        // 6. refreshRateSettingSummary(int extremeRate, int choiceModeRate) -> show exact selected Hz
        try {
            XposedHelpers.findAndHookMethod(fragName, cl, "refreshRateSettingSummary", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object pref = XposedHelpers.getObjectField(param.thisObject, "mRefreshRateSettingPreference");
                        if (pref == null) {
                            return;
                        }
                        int choice = (Integer) param.args[1];
                        if (choice != 120 && choice != 144 && choice != 165 && choice != 185) {
                            choice = 165;
                        }
                        XposedHelpers.callMethod(param.thisObject, "setupRefreshRateSettingEntries", 185);
                        XposedHelpers.callMethod(pref, "setAssignment", choice + " Гц");
                        XposedHelpers.callMethod(pref, "setValue", String.valueOf(choice));
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "refreshRateSettingSummary error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Hook refreshRateSettingSummary failed: " + t);
        }

        XposedBridge.log(TAG + "com.android.settings hooks installed");
    }
}
