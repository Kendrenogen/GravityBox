/*
 * Copyright (C) 2021 Peter Gregus for GravityBox Project (C3C076@xda)
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
package com.ceco.r.gravitybox;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import com.ceco.r.gravitybox.ledcontrol.QuietHours;
import com.ceco.r.gravitybox.ledcontrol.QuietHoursActivity;

import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModDialer {
    private static final String TAG = "GB:ModDialer";
    public static final List<String> PACKAGE_NAMES = new ArrayList<>(Arrays.asList(
            "com.google.android.dialer", "com.android.dialer"));

    private static final boolean DEBUG = false;

    private static QuietHours mQuietHours;

    private static final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
                mQuietHours = new QuietHours(intent.getExtras());
                if (DEBUG) log("QuietHours updated");
            }
        }
    };

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static class ClassInfo {
        Class<?> clazz;
        Map<String,String> methods;
        ClassInfo(Class<?> cls) {
            clazz = cls;
            methods = new HashMap<>();
        }
    }

    @SuppressWarnings("deprecation")
    private static ClassInfo resolveDialpadFragment(ClassLoader cl) {
        ClassInfo info = null;
        String[] CLASS_NAMES = new String[] {
                "com.android.dialer.app.dialpad.DialpadFragment",
                "com.android.dialer.dialpadview.DialpadFragment",
                "egh",
                "dyv"
        };
        String[] METHOD_NAMES = new String[] { "onResume", "playTone", "onPause" };
        for (String className : CLASS_NAMES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
            if (clazz == null || !Fragment.class.isAssignableFrom(clazz))
                continue;
            info = new ClassInfo(clazz);
            for (String methodName : METHOD_NAMES) {
                Method m = null;
                if (methodName.equals("onResume") || methodName.equals("onPause")) {
                    m = XposedHelpers.findMethodExactIfExists(clazz, methodName);
                } else if (methodName.equals("playTone")) {
                    for (String realMethodName : new String[] { methodName, "s", "a" }) {
                        m = XposedHelpers.findMethodExactIfExists(clazz, realMethodName,
                            int.class, int.class);
                        if (m != null) break;
                    }
                }
                if (m != null) {
                    info.methods.put(methodName, m.getName());
                }
            }
        }
        return info;
    }

    public static void init(final XSharedPreferences prefs, final XSharedPreferences qhPrefs,
            final ClassLoader classLoader, final String packageName, int sdkVersion) {

        if (sdkVersion < 29) {
            log("SDK of Dialer app too old: " + sdkVersion);
            return;
        }

        try {
            final ClassInfo classInfoDialpadFragment = resolveDialpadFragment(classLoader);

            XposedHelpers.findAndHookMethod(classInfoDialpadFragment.clazz,
                    classInfoDialpadFragment.methods.get("onResume"), new XC_MethodHook() {
                @SuppressWarnings("deprecation")
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DEBUG) log("DialpadFragment: onResume");
                    Context ctx = ((android.app.Fragment) param.thisObject).getContext();
                    ctx.registerReceiver(mBroadcastReceiver,
                            new IntentFilter(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED));
                    Intent i = new Intent(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED);
                    i.setComponent(new ComponentName(GravityBox.PACKAGE_NAME,
                            GravityBoxService.class.getName()));
                    ctx.startService(i);
                }
            });

            XposedHelpers.findAndHookMethod(classInfoDialpadFragment.clazz,
                    classInfoDialpadFragment.methods.get("playTone"),
                    int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mQuietHours != null &&
                            mQuietHours.isSystemSoundMuted(QuietHours.SystemSound.DIALPAD)) {
                        param.setResult(null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classInfoDialpadFragment.clazz,
                    classInfoDialpadFragment.methods.get("onPause"), new XC_MethodHook() {
                @SuppressWarnings("deprecation")
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DEBUG) log("DialpadFragment: onPause");
                    Context ctx = ((android.app.Fragment) param.thisObject).getContext();
                    ctx.unregisterReceiver(mBroadcastReceiver);
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "DialpadFragment: incompatible version of Dialer app", t);
        }
    }
}
