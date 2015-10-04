/*
 * Copyright (C) 2015 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.lollipop.gravitybox;

import com.ceco.lollipop.gravitybox.ModStatusBar.StatusBarState;
import com.ceco.lollipop.gravitybox.ledcontrol.QuietHours;
import com.ceco.lollipop.gravitybox.ledcontrol.QuietHoursActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadata;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;

public class ModLockscreen {
    private static final String CLASS_PATH = "com.android.keyguard";
    private static final String TAG = "GB:ModLockscreen";
    public static final String PACKAGE_NAME = "com.android.systemui";

    private static final String CLASS_KG_PASSWORD_VIEW = CLASS_PATH + ".KeyguardPasswordView";
    private static final String CLASS_KG_PIN_VIEW = CLASS_PATH + ".KeyguardPINView";
    private static final String CLASS_KG_PASSWORD_TEXT_VIEW = CLASS_PATH + ".PasswordTextView";
    private static final String CLASS_KGVIEW_MEDIATOR = "com.android.systemui.keyguard.KeyguardViewMediator";
    private static final String CLASS_LOCK_PATTERN_VIEW = "com.android.internal.widget.LockPatternView";
    private static final String ENUM_DISPLAY_MODE = "com.android.internal.widget.LockPatternView.DisplayMode";
    private static final String CLASS_SB_WINDOW_MANAGER = "com.android.systemui.statusbar.phone.StatusBarWindowManager";
    private static final String CLASS_KG_VIEW_MANAGER = "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String CLASS_UNLOCK_METHOD_CACHE = "com.android.systemui.statusbar.phone.UnlockMethodCache";
    private static final String CLASS_KG_SHOW_CB = "com.android.internal.policy.IKeyguardShowCallback";
    private static final String CLASS_CARRIER_TEXT = CLASS_PATH + ".CarrierText";
    private static final String CLASS_ICC_STATE = "com.android.internal.telephony.IccCardConstants.State";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KIS = false;

    private static int MSG_DISMISS_KEYGUARD = 1;

    private static enum DirectUnlock { OFF, STANDARD, SEE_THROUGH };

    private static XSharedPreferences mPrefs;
    private static XSharedPreferences mQhPrefs;
    private static Context mContext;
    private static Context mGbContext;
    private static Bitmap mCustomBg;
    private static QuietHours mQuietHours;
    private static Object mPhoneStatusBar;
    private static DirectUnlock mDirectUnlock = DirectUnlock.OFF;
    private static LockscreenAppBar mAppBar;
    private static boolean mSmartUnlock;
    private static boolean mIsScreenOn;
    private static DismissKeyguardHandler mDismissKeyguardHandler;
    private static boolean mIsSecure;
    private static GestureDetector mGestureDetector;
    private static TextView mCarrierTextView;

    private static boolean mInStealthMode;
    private static Object mPatternDisplayMode; 

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED)
                 || action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED)) {
                mPrefs.reload();
                prepareCustomBackground();
                if (DEBUG) log("Settings reloaded");
            } else if (action.equals(KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED)) {
                if (DEBUG_KIS) log("ACTION_KEYGUARD_IMAGE_UPDATED received");
                setLastScreenBackground(true);
            } else if (action.equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
                mQhPrefs.reload();
                mQuietHours = new QuietHours(mQhPrefs);
                if (DEBUG) log("QuietHours settings reloaded");
            } else if (action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED)) {
                if (mAppBar != null) {
                    if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT)) {
                        mAppBar.updateAppSlot(intent.getIntExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT, 0),
                            intent.getStringExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_VALUE));
                    }
                    if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH)) {
                        mAppBar.setSafeLaunchEnabled(intent.getBooleanExtra(
                                GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH, false));
                    }
                }
            }
        }
    };

    public static String getUmcInsecureFieldName() {
        switch (Build.VERSION.SDK_INT) {
            case 21: return "mMethodInsecure";
            default: return "mCurrentlyInsecure";
        }
    }

    public static void initResources(final XSharedPreferences prefs, final InitPackageResourcesParam resparam) {
        try {
            // Lockscreen: disable menu key in lock screen
            Utils.TriState triState = Utils.TriState.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_MENU_KEY, "DEFAULT"));
            if (DEBUG) log(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MENU_KEY + ": " + triState);
            if (triState != Utils.TriState.DEFAULT) {
                resparam.res.setReplacement(PACKAGE_NAME, "bool", "config_disableMenuKeyInLockScreen",
                        triState == Utils.TriState.DISABLED);
                if (DEBUG) log("config_disableMenuKeyInLockScreen: " + (triState == Utils.TriState.DISABLED));
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            mPrefs = prefs;
            mQhPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "quiet_hours");
            mQuietHours = new QuietHours(mQhPrefs);

            final Class<?> kgPasswordViewClass = XposedHelpers.findClass(CLASS_KG_PASSWORD_VIEW, classLoader);
            final Class<?> kgPINViewClass = XposedHelpers.findClass(CLASS_KG_PIN_VIEW, classLoader);
            final Class<?> kgPasswordTextViewClass = XposedHelpers.findClass(CLASS_KG_PASSWORD_TEXT_VIEW, classLoader);
            final Class<?> kgViewMediatorClass = XposedHelpers.findClass(CLASS_KGVIEW_MEDIATOR, classLoader);
            final Class<?> lockPatternViewClass = XposedHelpers.findClass(CLASS_LOCK_PATTERN_VIEW, classLoader);
            final Class<? extends Enum> displayModeEnum = (Class<? extends Enum>) XposedHelpers.findClass(ENUM_DISPLAY_MODE, classLoader);
            final Class<?> sbWindowManagerClass = XposedHelpers.findClass(CLASS_SB_WINDOW_MANAGER, classLoader);
            final Class<?> unlockMethodCacheClass = XposedHelpers.findClass(CLASS_UNLOCK_METHOD_CACHE, classLoader);

            String setupMethodName = Build.VERSION.SDK_INT >= 22 ? "setupLocked" : "setup";
            XposedHelpers.findAndHookMethod(kgViewMediatorClass, setupMethodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    mGbContext = Utils.getGbContext(mContext);

                    prepareCustomBackground();
                    prepareGestureDetector();

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED);
                    intentFilter.addAction(KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED);
                    intentFilter.addAction(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED);
                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);
                    if (DEBUG) log("Keyguard mediator constructed");
                }
            });

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_PHONE_STATUSBAR, classLoader,
                    "updateMediaMetaData", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mPhoneStatusBar == null) {
                        mPhoneStatusBar = param.thisObject;
                    }

                    int state = XposedHelpers.getIntField(mPhoneStatusBar, "mState");
                    if (state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED) {
                        if (DEBUG) log("updateMediaMetaData: Invalid status bar state: " + state);
                        return;
                    }

                    View backDrop = (View) XposedHelpers.getObjectField(mPhoneStatusBar, "mBackdrop");
                    ImageView backDropBack = (ImageView) XposedHelpers.getObjectField(
                            mPhoneStatusBar, "mBackdropBack");
                    if (backDrop == null || backDropBack == null) {
                        if (DEBUG) log("updateMediaMetaData: called too early");
                        return;
                    }

                    boolean hasArtwork = false;
                    MediaMetadata mm = (MediaMetadata) XposedHelpers.getObjectField(
                            mPhoneStatusBar, "mMediaMetadata");
                    if (mm != null) {
                        hasArtwork = mm.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                                mm.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null;
                    }
                    if (DEBUG) log("updateMediaMetaData: hasArtwork=" + hasArtwork);

                    // custom background
                    if (!hasArtwork && mCustomBg != null) {
                        backDrop.animate().cancel();
                        backDropBack.animate().cancel();
                        backDropBack.setImageBitmap(mCustomBg);
                        if ((Boolean) XposedHelpers.getBooleanField(
                                mPhoneStatusBar, "mScrimSrcModeEnabled")) {
                            PorterDuffXfermode xferMode = (PorterDuffXfermode) XposedHelpers
                                    .getObjectField(mPhoneStatusBar, "mSrcXferMode");
                            XposedHelpers.callMethod(backDropBack.getDrawable().mutate(),
                                    "setXfermode", xferMode);
                        }
                        backDrop.setVisibility(View.VISIBLE);
                        backDrop.animate().alpha(1f);
                        if (DEBUG) log("updateMediaMetaData: showing custom background");
                    }

                    // opacity
                    if (hasArtwork || mCustomBg != null) {
                        backDropBack.getDrawable().clearColorFilter();
                        final int opacity = mPrefs.getInt(
                                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_OPACITY, 100);
                        if (opacity != 100) {
                            final int alpha = (int) ((1 - opacity / 100f) * 255);
                            final int overlayColor = Color.argb(alpha, 0, 0, 0);
                            backDropBack.getDrawable().mutate()
                                .setColorFilter(overlayColor, PorterDuff.Mode.SRC_OVER);
                            if (DEBUG) log("updateMediaMetaData: opacity set");
                        }
                    }
                }
            });

            final Utils.TriState triState = Utils.TriState.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_ROTATION, "DEFAULT"));
            if (triState != Utils.TriState.DEFAULT) {
                XposedHelpers.findAndHookMethod(sbWindowManagerClass, "shouldEnableKeyguardScreenRotation",
                        new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        if (DEBUG) log("shouldEnableKeyguardScreenRotation called");
                        try {
                            if (Utils.isMtkDevice()) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            } else {
                                return (triState == Utils.TriState.ENABLED);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    }
                });
            }

            XposedHelpers.findAndHookMethod(kgPasswordViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    final TextView passwordEntry = 
                            (TextView) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                    if (passwordEntry == null) return;

                    passwordEntry.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void afterTextChanged(Editable s) {
                            doQuickUnlock(param.thisObject, passwordEntry.getText().toString());
                        }
                        @Override
                        public void beforeTextChanged(CharSequence arg0,int arg1, int arg2, int arg3) { }
                        @Override
                        public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) { }
                    });
                }
            });

            XposedHelpers.findAndHookMethod(kgPINViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;
                    final View passwordEntry = 
                            (View) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                    if (passwordEntry != null) { 
                        XposedHelpers.setAdditionalInstanceField(passwordEntry, "gbPINView",
                                param.thisObject);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgPasswordTextViewClass, "append", char.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    Object pinView = XposedHelpers.getAdditionalInstanceField(param.thisObject, "gbPINView");
                    if (pinView != null) {
                        if (DEBUG) log("quickUnlock: PasswordText belongs to PIN view");
                        String entry = (String) XposedHelpers.getObjectField(param.thisObject, "mText");
                        doQuickUnlock(pinView, entry);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(lockPatternViewClass, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    beforeLockPatternDraw(displayModeEnum, param.thisObject);
                }

                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    afterLockPatternDraw(param.thisObject);
                }
            });

            XposedHelpers.findAndHookMethod(kgViewMediatorClass, "playSounds", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mQuietHours.isSystemSoundMuted(QuietHours.SystemSound.SCREEN_LOCK)) {
                        XposedHelpers.setBooleanField(param.thisObject, "mSuppressNextLockSound", false);
                        param.setResult(null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_VIEW_MANAGER, classLoader, "onScreenTurnedOff",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mIsScreenOn = false;
                    mIsSecure = (Boolean) XposedHelpers.callMethod(param.thisObject, "isSecure");
                    mDirectUnlock = DirectUnlock.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_DIRECT_UNLOCK, "OFF"));
                    mSmartUnlock = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_SMART_UNLOCK, false);
                    if (mSmartUnlock && mDismissKeyguardHandler == null) {
                        mDismissKeyguardHandler = new DismissKeyguardHandler(param.thisObject);
                    }
                    updateCarrierText();
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_VIEW_MANAGER, classLoader, "onScreenTurnedOn",
                    CLASS_KG_SHOW_CB, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mIsScreenOn = true;
                    if (!mIsSecure) {
                        if (DEBUG) log("onScreenTurnedOn: noop as keyguard is not secured");
                        return;
                    }

                    Object umCache = XposedHelpers.getStaticObjectField(unlockMethodCacheClass, "sInstance");
                    boolean trustManaged = XposedHelpers.getBooleanField(umCache, "mTrustManaged");
                    if (!trustManaged) {
                        if (mDirectUnlock != DirectUnlock.OFF) {
                            if (mDirectUnlock == DirectUnlock.SEE_THROUGH) {
                                XposedHelpers.callMethod(mPhoneStatusBar, "showBouncer");
                            } else {
                                XposedHelpers.callMethod(mPhoneStatusBar, "makeExpandedInvisible");
                            }
                        }
                    } else {
                        if (mSmartUnlock) {
                            boolean insecure = XposedHelpers.getBooleanField(umCache, getUmcInsecureFieldName());
                            if (insecure) {
                                // previous state is insecure so we rather wait a second as smart lock can still
                                // decide to make it secure after a while. Seems to be necessary only for
                                // on-body detection. Other smart lock methods seem to always start with secured state
                                if (DEBUG) log("onScreenTurnedOn: Scheduling Keyguard dismiss");
                                mDismissKeyguardHandler.sendEmptyMessageDelayed(MSG_DISMISS_KEYGUARD, 1000);
                            }
                        }
                    }
                }
            });

            XC_MethodHook umcNotifyListenersHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mIsScreenOn || !mIsSecure || !mSmartUnlock) return;

                    boolean trustManaged = XposedHelpers.getBooleanField(param.thisObject, "mTrustManaged");
                    boolean insecure = XposedHelpers.getBooleanField(param.thisObject, getUmcInsecureFieldName());
                    if (DEBUG) log("updateMethodSecure: trustManaged=" + trustManaged +
                            "; insecure=" + insecure);
                    if (trustManaged && insecure) {
                        // either let already queued message to be handled or handle new one immediately
                        if (!mDismissKeyguardHandler.hasMessages(MSG_DISMISS_KEYGUARD)) {
                            mDismissKeyguardHandler.sendEmptyMessage(MSG_DISMISS_KEYGUARD);
                        }
                    } else if (mDismissKeyguardHandler.hasMessages(MSG_DISMISS_KEYGUARD)) {
                        // smart lock decided to make it secure so remove any pending dismiss keyguard messages
                        mDismissKeyguardHandler.removeMessages(MSG_DISMISS_KEYGUARD);
                        if (DEBUG) log("updateMethodSecure: pending keyguard dismiss cancelled");
                    }
                }
            };
            if (Build.VERSION.SDK_INT < 22) {
                XposedHelpers.findAndHookMethod(unlockMethodCacheClass, "notifyListeners",
                    boolean.class, umcNotifyListenersHook);
            } else {
                XposedHelpers.findAndHookMethod(unlockMethodCacheClass, "notifyListeners",
                    umcNotifyListenersHook);
            }

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_PHONE_STATUSBAR, classLoader,
                    "makeStatusBarView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    ViewGroup kgStatusView = (ViewGroup) XposedHelpers.getObjectField(
                            param.thisObject, "mKeyguardStatusView");
                    int containerId = kgStatusView.getResources().getIdentifier("keyguard_clock_container",
                            "id", PACKAGE_NAME);
                    if (containerId != 0) {
                        ViewGroup container = (ViewGroup) kgStatusView.findViewById(containerId);
                        if (container != null) {
                            mAppBar = new LockscreenAppBar(mContext, mGbContext, container,
                                    param.thisObject, prefs);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_NOTIF_PANEL_VIEW, classLoader,
                    "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_D2TS, false) &&
                            mGestureDetector != null &&
                            (int) XposedHelpers.callMethod(
                                XposedHelpers.getObjectField(param.thisObject, "mStatusBar"),
                                "getBarState") == StatusBarState.KEYGUARD) {
                        mGestureDetector.onTouchEvent((MotionEvent) param.args[0]);
                    }
                }
            });

            if (!Utils.isXperiaDevice()) {
                XC_MethodHook carrierTextHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                        String text = mPrefs.getString(GravityBoxSettings.PREF_KEY_LOCKSCREEN_CARRIER_TEXT, "");
                        if (mCarrierTextView == null) {
                            mCarrierTextView = (TextView) param.thisObject;
                        } 
                        if (text.isEmpty()) {
                            return;
                        } else {
                            mCarrierTextView.setText(text.trim().isEmpty() ? "" : text);
                            param.setResult(null);
                        }
                    }
                };
                if (Build.VERSION.SDK_INT < 22) {
                    XposedHelpers.findAndHookMethod(CLASS_CARRIER_TEXT, classLoader, "updateCarrierText",
                        CLASS_ICC_STATE, CharSequence.class, CharSequence.class, carrierTextHook);
                } else {
                    XposedHelpers.findAndHookMethod(CLASS_CARRIER_TEXT, classLoader, "updateCarrierText",
                            carrierTextHook);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    } 

    private static class DismissKeyguardHandler extends Handler {
        private Object mKeyguardViewManager;

        public DismissKeyguardHandler(Object keyGuardViewManager) {
            super();
            mKeyguardViewManager = keyGuardViewManager;
        }

        @Override
        public void handleMessage(Message msg) { 
            if (msg.what == MSG_DISMISS_KEYGUARD) {
                try {
                    if (XposedHelpers.getBooleanField(mKeyguardViewManager, "mShowing")) {
                        XposedHelpers.callMethod(mKeyguardViewManager, "dismiss");
                        if (DEBUG) log("mDismissKeyguardHandler: Keyguard dismissed");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }
    };

    private static void doQuickUnlock(Object securityView, String entry) {
        try {
            final Object callback = XposedHelpers.getObjectField(securityView, "mCallback");
            final Object lockPatternUtils = XposedHelpers.getObjectField(securityView, "mLockPatternUtils");

            if (callback != null && lockPatternUtils != null && entry.length() > 3 && 
                    (Boolean) XposedHelpers.callMethod(lockPatternUtils, "checkPassword", entry)) {
                XposedHelpers.callMethod(callback, "reportUnlockAttempt", true);
                XposedHelpers.callMethod(callback, "dismiss", true);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);;
        }
    }

    private static synchronized void prepareCustomBackground() {
        try {
            if (mCustomBg != null) {
                mCustomBg = null;
            }
            final String bgType = mPrefs.getString(
                  GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND,
                  GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);
    
            if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_COLOR)) {
                int color = mPrefs.getInt(
                      GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_COLOR, Color.BLACK);
                mCustomBg = Utils.drawableToBitmap(new ColorDrawable(color));
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_IMAGE)) {
                String wallpaperFile = mGbContext.getFilesDir() + "/lockwallpaper";
                mCustomBg = BitmapFactory.decodeFile(wallpaperFile);
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN)) {
                setLastScreenBackground(false);
            }
    
            if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN) &&
                    mCustomBg != null && mPrefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_EFFECT, false)) {
                mCustomBg = Utils.blurBitmap(mContext, mCustomBg, mPrefs.getInt(
                          GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_INTENSITY, 14));
            }
            if (DEBUG) log("prepareCustomBackground: type=" + bgType);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static synchronized void setLastScreenBackground(boolean refresh) {
        try {
            String kisImageFile = mGbContext.getFilesDir() + "/kis_image.png";
            mCustomBg = BitmapFactory.decodeFile(kisImageFile);
            if (refresh && mPhoneStatusBar != null) {
                XposedHelpers.callMethod(mPhoneStatusBar, "updateMediaMetaData", false);
            }
            if (DEBUG_KIS) log("setLastScreenBackground: Last screen background updated");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void beforeLockPatternDraw(final Class<? extends Enum> displayModeEnum, final Object thisObject) {
        final Object patternDisplayMode = XposedHelpers.getObjectField(thisObject, "mPatternDisplayMode");
        final Boolean inStealthMode = XposedHelpers.getBooleanField(thisObject, "mInStealthMode");  

        if (!mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_SHOW_PATTERN_ERROR, true) &&
                    mPatternDisplayMode == null && patternDisplayMode == Enum.valueOf(displayModeEnum, "Wrong")) {
            mInStealthMode = inStealthMode;
            mPatternDisplayMode = patternDisplayMode;
            XposedHelpers.setBooleanField(thisObject, "mInStealthMode", true);
            XposedHelpers.setObjectField(thisObject, "mPatternDisplayMode", Enum.valueOf(displayModeEnum, "Correct"));
        } else {
            mPatternDisplayMode = null;
        }
    }

    private static void afterLockPatternDraw(final Object thisObject) {
        if (null != mPatternDisplayMode) {
            XposedHelpers.setBooleanField(thisObject, "mInStealthMode", mInStealthMode);
            XposedHelpers.setObjectField(thisObject, "mPatternDisplayMode", mPatternDisplayMode);
            mInStealthMode = false;
            mPatternDisplayMode = null;
        }
    }

    private static void prepareGestureDetector() {
        try {
            mGestureDetector = new GestureDetector(mContext, 
                    new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    Intent intent = new Intent(ModHwKeys.ACTION_SLEEP);
                    mContext.sendBroadcast(intent);
                    return true;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void updateCarrierText() {
        if (mCarrierTextView == null) return;
        try {
            if (Build.VERSION.SDK_INT < 22) {
                Object callback = XposedHelpers.getObjectField(mCarrierTextView, "mCallback");
                XposedHelpers.callMethod(mCarrierTextView, "updateCarrierText",
                        XposedHelpers.getObjectField(callback, "mSimState"),
                        XposedHelpers.getObjectField(callback, "mPlmn"),
                        XposedHelpers.getObjectField(callback, "mSpn"));
            } else {
                XposedHelpers.callMethod(mCarrierTextView, "updateCarrierText");
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
