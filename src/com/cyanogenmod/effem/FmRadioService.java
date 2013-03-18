/*
 * Copyright (C) 2011 The Android Open Source Project
 *               2013 Grigori Goronzy <greg@chown.ath.cx>
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

package com.cyanogenmod.effem;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import com.stericsson.hardware.fm.FmBand;
import com.stericsson.hardware.fm.FmReceiver;
import android.media.AudioSystem;
import android.app.Notification.Builder;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import java.io.IOException;

public class FmRadioService extends Service {
    // various constants
    static final String LOG_TAG = "EffemService";
    static final int PLAY_NOTIFICATION = 1;

    // seek modes
    static final int SEEK_RESET    = 0;
    static final int SEEK_ABSOLUTE = 1;
    static final int SEEK_SCANUP   = 2;
    static final int SEEK_SCANDOWN = 3;
    static final int SEEK_STEPUP   = 4;
    static final int SEEK_STEPDOWN = 5;

    private Handler mHandler;
    private MediaPlayer mMediaPlayer;
    private FmBand mFmBand;
    private FmReceiver mFmReceiver;
    private Notification.Builder mRadioNotification;
    private Notification mNotificationInstance;
    private NotificationManager mNotificationManager;
    private FmReceiver.OnScanListener mReceiverScanListener;
    private FmReceiver.OnRDSDataFoundListener mReceiverRdsDataFoundListener;
    private FmReceiver.OnStartedListener mReceiverStartedListener;
    private BroadcastReceiver mHeadsetReceiver;

    private int mCurrentFrequency;
    private int mDefaultOutput = 0; // 0=headset, 1=speaker
    private boolean mCallbacksEnabled = false;
    private boolean mHeadsetConnected = false;

    // Binder for direct access to local service
    private Binder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        FmRadioService getService() {
            return FmRadioService.this;
        }
    }

    // callbacks for activity
    private Callbacks mCallbacks = null;
    public interface Callbacks {
        /**
         * Receiver changed state
         *
         * @param state new state (on == true)
         */
        public void onReceiverStateChanged(boolean state);

        /**
         * Frequency changed
         *
         * @param new frequency in KHz
         * @param offset channel offset in KHz (for formatting)
         */
        public void onFrequencyChanged(int frequency, int offset);

        /**
         * New RDS data is available
         *
         * @param rdsData Bundle with key to value mappings
         */
        public void onRdsDataAvailable(Bundle rdsData);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mFmReceiver = (FmReceiver)getSystemService("fm_receiver");
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        prepareNotification();

        // listen for headset connection events
        mHeadsetReceiver = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent intent) {
                int state = intent.getIntExtra("state", -1);
                Log.i(LOG_TAG, "headset state is " + state);
                mHeadsetConnected = state == 1;

                // stop radio if headset disconnected
                if (mHeadsetConnected == false && isStarted()) {
                    Log.i(LOG_TAG, "stopping receiver");
                    if (mCallbacksEnabled == false) {
                        Log.i(LOG_TAG, "nothing to do, stopping service");
                        stopSelf();
                        updateReceiverState(false);
                        // enter special state that allows the user to start up
                        // the activity again from the notification area
                        //startForeground(PLAY_NOTIFICATION, mNotificationInstance);
                    } else {
                        updateReceiverState(false);
                    }
                }
            }
        };
        registerReceiver(mHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mHeadsetReceiver);
        unregisterReceiverCallbacks();
        updateReceiverState(false);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        mHandler = new Handler();
        return mBinder;
    }

    private void tryPost(Runnable run) {
        if (mHandler != null && mCallbacks != null) {
            mHandler.post(run);
        }
    }

    private void registerReceiverCallbacks() {
        if (mCallbacksEnabled == true)
            return;

        mReceiverScanListener = new com.stericsson.hardware.fm.FmReceiver.OnScanListener() {
            public void onFullScan(int[] frequency, int[] signalStrength, boolean aborted) {
                // not implemented because full scan is never used
            }

            public void onScan(int tunedFrequency, int signalStrength,
                    int scanDirection, boolean aborted) {
                updateFrequency(tunedFrequency, true);
            }
        };

        mReceiverRdsDataFoundListener = new com.stericsson.hardware.fm.FmReceiver.OnRDSDataFoundListener() {
            // Receives the current frequency's RDS Data
            public void onRDSDataFound(final Bundle rdsData, int frequency) {
                if (!isStarted() || frequency != mCurrentFrequency)
                    return;

                // notify activity
                tryPost(new Runnable() {
                    public void run() {
                        mCallbacks.onRdsDataAvailable(rdsData);
                    }
                });

                // set station name in notification
                if (rdsData.containsKey("PSN")) {
                    setNotification(rdsData.getString("PSN").trim(), mCurrentFrequency);
                }
                
            }
        };

        mReceiverStartedListener = new com.stericsson.hardware.fm.FmReceiver.OnStartedListener() {
            public void onStarted() {
                updateAudioState(true);
                if (mCurrentFrequency <= 0) {
                    mCurrentFrequency = mFmBand.getDefaultFrequency();
                }
                updatePlayState(true);
                tryPost(new Runnable() {
                    public void run() {
                        mCallbacks.onReceiverStateChanged(true);
                    }
                });
                updateFrequency(mCurrentFrequency, true);
                startForeground(PLAY_NOTIFICATION, mNotificationInstance);
            }
        };

        mFmReceiver.addOnScanListener(mReceiverScanListener);
        mFmReceiver.addOnStartedListener(mReceiverStartedListener);
        mFmReceiver.addOnRDSDataFoundListener(mReceiverRdsDataFoundListener);

        mCallbacksEnabled = true;
    }

    private void unregisterReceiverCallbacks() {
        if (mCallbacksEnabled == false)
            return;

        mFmReceiver.removeOnScanListener(mReceiverScanListener);
        mFmReceiver.removeOnStartedListener(mReceiverStartedListener);
        mFmReceiver.removeOnRDSDataFoundListener(mReceiverRdsDataFoundListener);

        mCallbacksEnabled = false;
    }

    /**
     * Toggles FM audio routing
     *
     * @param state desired state
     */
    private void updateAudioState(boolean state) {

        if (state == true) {
            // try digital audio playback first
            try {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setDataSource("fmradio://rx");
                mMediaPlayer.prepare();
                mMediaPlayer.start();
            } catch (Exception e) {
                // fall back to legacy audio routing
                mMediaPlayer = null;
                AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, (mDefaultOutput == 1) ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE);
                AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_AVAILABLE, "");
            }
        } else {
            if (mMediaPlayer != null) {
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_NONE);
            AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
        }
    }

    /**
     * Update FM receiver and audio output state
     *
     * @param state requested state
     */
    private synchronized void updateReceiverState(boolean state) {
        if (mFmReceiver.getState() == FmReceiver.STATE_IDLE
                && state == true) {
            try {
                registerReceiverCallbacks();
                mFmReceiver.start(mFmBand);
                updateAudioState(true);
                if (mCurrentFrequency <= 0) {
                    mCurrentFrequency = mFmBand.getDefaultFrequency();
                }
                updatePlayState(true);
                tryPost(new Runnable() {
                    public void run() {
                        mCallbacks.onReceiverStateChanged(true);
                    }
                });
                updateFrequency(mCurrentFrequency, true);
                startForeground(PLAY_NOTIFICATION, mNotificationInstance);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.fm_start_error, Toast.LENGTH_LONG);
            } catch (IllegalStateException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.fm_start_error, Toast.LENGTH_LONG);
            }
        } else if (mFmReceiver.getState() != FmReceiver.STATE_IDLE
                && state == false) {
            try {
                unregisterReceiverCallbacks();
                updatePlayState(false);
                updateAudioState(false);
                mFmReceiver.reset();
                tryPost(new Runnable() {
                    public void run() {
                        mCallbacks.onReceiverStateChanged(false);
                    }
                });
                stopForeground(true);
                //mNotificationManager.cancel(PLAY_NOTIFICATION);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Failed to stop FM receiver");
            }
        } else if (mFmReceiver.getState() == FmReceiver.STATE_STARTED) {
            // in case of a hot restart, the onStarted callback is never
            // called, so we need to set the frequency here
            updatePlayState(true);
            if (mCurrentFrequency > 0) {
                updateFrequency(mCurrentFrequency, true);
            }
            tryPost(new Runnable() {
                public void run() {
                    mCallbacks.onReceiverStateChanged(true);
                }
            });
            startForeground(PLAY_NOTIFICATION, mNotificationInstance);
        } else {
            Log.i(LOG_TAG, "No action for updateReceiverState: incorrect state - " + mFmReceiver.getState());
        }
    }

    /**
     * Update playback state (paused or playing) and associated UI elements
     *
     * @param state requested state
     */
    private synchronized void updatePlayState(boolean state) {
        if (state == true) {
            try {
                mFmReceiver.resume();
                if (mMediaPlayer != null)
                    mMediaPlayer.start();
            } catch (IOException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.resume_error, Toast.LENGTH_LONG);
            } catch (IllegalStateException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.resume_error, Toast.LENGTH_LONG);
            }
        } else {
            try {
                if (mMediaPlayer != null)
                    mMediaPlayer.pause();
                mFmReceiver.pause();
                //mNotificationManager.cancel(PLAY_NOTIFICATION);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.pause_error, Toast.LENGTH_LONG);
            } catch (IllegalStateException e) {
                Log.e(LOG_TAG, e.toString());
                FmUtils.showToast(this, mHandler, R.string.pause_error, Toast.LENGTH_LONG);
            }
        }
    }

    /**
     * Update frequency state and associated UI elements
     *
     * @param freq new frequency to set in KHz
     * @param setFrequency whether frequency should be set on the receiver
     *
     */
    private synchronized boolean updateFrequency(final int frequency,
            boolean setFrequency) {
        mCurrentFrequency = frequency;

        try {
            // only change frequency if it's different from current, otherwise
            // an audible pop can occur
            if (setFrequency && mFmReceiver.getFrequency() != frequency)
                mFmReceiver.setFrequency(frequency);
        } catch (IllegalStateException e) {
            Log.e(LOG_TAG, e.toString());
            FmUtils.showToast(this, mHandler, R.string.seek_error, Toast.LENGTH_LONG);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, e.toString());
            FmUtils.showToast(this, mHandler, R.string.seek_error, Toast.LENGTH_LONG);
            return false;
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
            FmUtils.showToast(this, mHandler, R.string.seek_error, Toast.LENGTH_LONG);
            return false;
        }

        setNotification(null, mCurrentFrequency);

        final int channelOffset = mFmBand.getChannelOffset();
        tryPost(new Runnable() {
            public void run() {
                mCallbacks.onFrequencyChanged(frequency, channelOffset);
            }
        });

        return true;
    }

    /**
     * Prepare notification builder with appropriate settings
     *
     */
    private void prepareNotification() {
        // create notification builder
        mRadioNotification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.stat_notify_fm)
                .setOngoing(true)
                .setWhen(0);

        // switch to activity when notification is clicked
        PendingIntent resultIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, FmRadio.class), 0);
        mRadioNotification.setContentIntent(resultIntent);
    }

    /**
     * Set notification according to frequency and PSN
     *
     * @param stationName PSN string, or null
     * @param frequency frequency in KHz
     */
    private void setNotification(String stationName, int frequency) {
        if (isStarted() == false)
            return;

        int offset = mFmBand.getChannelOffset();
        String freqFormatted = FmUtils.formatFrequency(offset, frequency);

        if (stationName != null && frequency > 0) {
            mRadioNotification.setContentTitle(stationName)
                .setContentText(freqFormatted + " MHz");
            mNotificationInstance = mRadioNotification.getNotification();
            mNotificationManager.notify(PLAY_NOTIFICATION, mNotificationInstance);
        } else if (frequency > 0) {
            mRadioNotification.setContentTitle(freqFormatted + " MHz")
                .setContentText("");
            mNotificationInstance = mRadioNotification.getNotification();
            mNotificationManager.notify(PLAY_NOTIFICATION, mNotificationInstance);
        }
    }

    /**
     * Start radio with given band and frequency
     *
     * @param band FmBand constant
     * @param frequency frequency in Khz
     * @param output headset/speaker
     * @return success
     */
    public boolean startRadio(int band, int frequency, int output) {
        Log.v(LOG_TAG, "startRadio");

        if (mHeadsetConnected == false) {
            FmUtils.showToast(this, mHandler, R.string.no_headset_error,
                    Toast.LENGTH_LONG);
            return false;
        }

        mCurrentFrequency = frequency;
        mDefaultOutput = output;
        mFmBand = new FmBand(band);
        updateReceiverState(true);
        return true;
    }

    /**
     * Stop radio
     */
    public void stopRadio() {
        Log.v(LOG_TAG, "stopRadio");
        updateReceiverState(false);
    }

    /**
     * Set receiver callbacks (or null to disable them)
     * This also starts an instant notification about current state
     * on these callbacks
     */
    public void setCallbacks(FmRadioService.Callbacks cb) {
        Log.i(LOG_TAG, "setCallbacks");
        mCallbacks = cb;

        // notify about current state via callbacks
        int channelOffset = 0;
        if (isStarted())
            channelOffset = mFmBand.getChannelOffset();
        final boolean started = isStarted();
        final int frequency = mCurrentFrequency;
        final int offset = channelOffset;
        tryPost(new Runnable() {
            public void run() {
                mCallbacks.onReceiverStateChanged(started);
                if (frequency > 0 && offset > 0)
                    mCallbacks.onFrequencyChanged(frequency, offset);
            }
        });
    }

    /**
     * Change frequency
     *
     * @param mode frequency change mode
     * @param frequency frequency parameter for absolute mode
     * @return success
     */
    public boolean changeFrequency(int mode, int frequency) {
        Log.v(LOG_TAG, "changeFrequency");

        if (!isStarted() || !isReady()) {
            Log.e(LOG_TAG, "radio not ready");
            return false;
        }

        switch (mode) {
        case SEEK_RESET:
            // use the band's default frequency
            frequency = mFmBand.getDefaultFrequency();
            updateFrequency(frequency, true);
            break;
        case SEEK_ABSOLUTE:
            updateFrequency(frequency, true);
            break;
        case SEEK_SCANUP:
            mFmReceiver.scanUp();
            break;
        case SEEK_SCANDOWN:
            mFmReceiver.scanDown();
            break;
        case SEEK_STEPUP:
            frequency = mCurrentFrequency + mFmBand.getChannelOffset();
            updateFrequency(frequency, true);
            break;
        case SEEK_STEPDOWN:
            frequency = mCurrentFrequency - mFmBand.getChannelOffset();
            updateFrequency(frequency, true);
            break;
        default:
            Log.e(LOG_TAG, "illegal seek mode");
            return false;
        }
        return true;
    }

    /**
     * Suspend callbacks (to save power)
     */
    public void suspendCallbacks() {
        Log.v(LOG_TAG, "suspendCallbacks");
        unregisterReceiverCallbacks();
    }

    /**
     * Resume callbacks
     */
    public void resumeCallbacks() {
        Log.v(LOG_TAG, "resumeCallbacks");
        registerReceiverCallbacks();
    }

    /**
     * Return whether radio has been started
     *
     * @return radio state
     */
    public boolean isStarted() {
        return (mFmReceiver.getState() != FmReceiver.STATE_IDLE)
                && (mFmReceiver.getState() != FmReceiver.STATE_STARTING)
                && (mFmBand != null);
    }

    /**
     * Return whether radio is ready for executing a command
     *
     * @return command state
     */
    public boolean isReady() {
        int state = mFmReceiver.getState();
        return state != FmReceiver.STATE_SCANNING
                && state != FmReceiver.STATE_STARTING;
    }

    /**
     * Get channel offset of currently active band
     *
     * @return channel offset in KHz
     */
    public int getChannelOffset() {
        if (!isStarted())
            return -1;
        return mFmBand.getChannelOffset();
    }
}
