/*
 * Copyright (C) 2016 The CyanogenMod project
 *               2017 The LineageOS Project
 *
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

package com.bliss.device.DeviceSettings;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.Manifest;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

import java.util.List;

public class TouchKeyHandler implements DeviceKeyHandler {

    private static final String TAG = TouchKeyHandler.class.getSimpleName();

    private static final String GESTURE_WAKEUP_REASON = "touchscreen-gesture-wakeup";
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final int GESTURE_REQUEST = 0;
    private static final int GESTURE_WAKELOCK_DURATION = 3000;
    private static final int EVENT_PROCESS_WAKELOCK_DURATION = 500;

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final PowerManager mPowerManager;
    private final WakeLock mGestureWakeLock;
    private final EventHandler mEventHandler;
    private final Vibrator mVibrator;

    private final SparseIntArray mActionMapping = new SparseIntArray();

    private boolean mInPocket;

    private final BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int[] keycodes = intent.getIntArrayExtra(
                    Constants.UPDATE_EXTRA_KEYCODE_MAPPING);
            int[] actions = intent.getIntArrayExtra(
                    Constants.UPDATE_EXTRA_ACTION_MAPPING);
            mActionMapping.clear();
            if (keycodes != null && actions != null && keycodes.length == actions.length) {
                for (int i = 0; i < keycodes.length; i++) {
                    mActionMapping.put(keycodes[i], actions[i]);
                }
            }
        }
    };

    public TouchKeyHandler(final Context context) {
        mContext = context;

        mAudioManager = mContext.getSystemService(AudioManager.class);

        mPowerManager = context.getSystemService(PowerManager.class);
        mGestureWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DeviceKeyHandler:TouchscreenGestureWakeLock");

        mEventHandler = new EventHandler();

        mVibrator = context.getSystemService(Vibrator.class);

        mContext.registerReceiver(mUpdateReceiver,
                new IntentFilter(Constants.UPDATE_PREFS_ACTION));
    }

    @Override
    public KeyEvent handleKeyEvent(final KeyEvent event) {
        final int action = mActionMapping.get(event.getScanCode(), -1);
        if (action < 0 || event.getAction() != KeyEvent.ACTION_UP
                || !hasSetupCompleted() || mInPocket) {
            return event;
        }

        if (action != 0 && !mEventHandler.hasMessages(GESTURE_REQUEST)) {
            final Message msg = getMessageForAction(action);
            mGestureWakeLock.acquire(EVENT_PROCESS_WAKELOCK_DURATION);
            mEventHandler.sendMessage(msg);
        }

        return null;
    }

    public void onPocketStateChanged(boolean inPocket) {
        mInPocket = inPocket;
    }

    private boolean hasSetupCompleted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    private Message getMessageForAction(final int action) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        msg.arg1 = action;
        return msg;
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(final Message msg) {
            switch (msg.arg1) {
                case Constants.ACTION_AMBIENT_DISPLAY:
                    launchDozePulse();
                    break;
                case Constants.ACTION_WAKE_DEVICE:
                    wakeDevice();
                    break;
            }
        }
    }

    private void launchDozePulse() {
        final boolean dozeEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, 1) != 0;
        if (dozeEnabled) {
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            final Intent intent = new Intent(PULSE_ACTION);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            doHapticFeedback();
        }
    }

    private void wakeDevice() {
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) {
            Log.w(TAG, "Unable to send media key event");
            return;
        }
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
    }

    private void startActivitySafely(final Intent intent) {
        if (intent == null) {
            Log.w(TAG, "No intent passed to startActivitySafely");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            final UserHandle user = new UserHandle(UserHandle.USER_CURRENT);
            mContext.startActivityAsUser(intent, null, user);
        } catch (ActivityNotFoundException e) {
            // Ignore
        }
    }

    private void doHapticFeedback() {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }

        if (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
            final boolean enabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.TOUCHSCREEN_GESTURE_HAPTIC_FEEDBACK, 1) != 0;
            if (enabled) {
                mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK));
            }
        }
    }
}
