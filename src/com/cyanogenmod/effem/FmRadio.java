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

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import com.stericsson.hardware.fm.FmBand;
import com.stericsson.hardware.fm.FmReceiver;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.app.Notification.Builder;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;

import java.io.IOException;


public class FmRadio extends Activity {

    // The string to find in android logs
    private static final String LOG_TAG = "Effem";

    // The string to show that the station list is empty
    private static final String EMPTY_STATION_LIST = "No stations available";

    // The 50kHz channel offset
    private static final int CHANNEL_OFFSET_50KHZ = 50;

    // The base menu identifier
    private static final int BASE_OPTION_MENU = 0;

    // The band menu identifier
    private static final int BAND_SELECTION_MENU = 1;

    // The station menu identifier
    private static final int STATION_SELECTION_MENU = 2;

    // Handle to the Media Player that plays the audio from the selected station
    private MediaPlayer mMediaPlayer;

    // The scan listener that receives the return values from the scans
    private FmReceiver.OnScanListener mReceiverScanListener;

    // The listener that receives the RDS data from the current channel
    private FmReceiver.OnRDSDataFoundListener mReceiverRdsDataFoundListener;

    // The started listener is activated when the radio has started
    private FmReceiver.OnStartedListener mReceiverStartedListener;

    // Displays the currently tuned frequency
    private TextView mFrequencyTextView;

    // Displays the current station name if there is adequate RDS data
    private TextView mStationNameTextView;

    // Displays radio text information from RDS data
    private TextView mStationInfoTextView;

    // Displays program type name from RDS data
    private TextView mProgramTypeTextView;

    // Handle to the FM radio Band object
    private FmBand mFmBand;

	// current tuned frequency, in khz
	private int mCurrentFrequency;

    // Handle to the FM radio receiver object
    private FmReceiver mFmReceiver;

    // Notification that shows when radio is running
    private Notification.Builder mRadioNotification;

    // Indicates if we are in the initialization sequence
    private boolean mInit = true;

    // Indicates that we are restarting the app
    private boolean mRestart = false;

    // Protects the MediaPlayer and FmReceiver against rapid muting causing
    // errors
    private boolean mPauseMutex = false;

    // Array of the available stations in MHz
    private ArrayAdapter<CharSequence> mMenuAdapter;

    // Required for legacy audio
    private AudioManager mAudioman;

    // Notification display
    private NotificationManager mNotificationManager;

    // The name of the storage string
    public static final String PREFS_NAME = "FMRadioPrefsFile";

    // The menu items
    public static final int FM_BAND = Menu.FIRST;

    public static final int BAND_US = Menu.FIRST + 1;

    public static final int BAND_EU = Menu.FIRST + 2;

    public static final int BAND_JAPAN = Menu.FIRST + 3;

    public static final int BAND_CHINA = Menu.FIRST + 4;

    public static final int STATION_SELECT = Menu.FIRST + 5;

    public static final int STATION_SELECT_MENU_ITEMS = STATION_SELECT + 1;

    // The currently selected FM Radio band
    private int mSelectedBand;

    /**
     * Required method from parent class
     *
     * @param icicle - The previous instance of this app
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main);
        mFmReceiver = (FmReceiver) getSystemService("fm_receiver");
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        mSelectedBand = settings.getInt("selectedBand", 1);
        mCurrentFrequency = settings.getInt("currentFrequency", 0);
        mFmBand = new FmBand(mSelectedBand);
        mNotificationManager =
                (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        mAudioman = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		prepareNotification();
        setupButtons();
    }

    /**
     * Starts up the listeners and the FM radio if it isn't already active
     */
    @Override
    protected void onStart() {
        super.onStart();
        mReceiverScanListener = new com.stericsson.hardware.fm.FmReceiver.OnScanListener() {

            // FullScan results
            public void onFullScan(int[] frequency, int[] signalStrength, boolean aborted) {
                ((ImageButton) findViewById(R.id.FullScan)).setEnabled(true);
                showToast("Fullscan complete", Toast.LENGTH_LONG);
                mMenuAdapter.clear();
                if (frequency.length == 0) {
                    mMenuAdapter.add(EMPTY_STATION_LIST);
                    return;
                }
                for (int i = 0; i < frequency.length; i++) {
                    mMenuAdapter.add(formatFrequency(frequency[i]));
                }
                if (mInit) {
                    mInit = false;
                    try {
                        mFmReceiver.setFrequency(frequency[0]);
                        mFrequencyTextView.setText(mMenuAdapter.getItem(0).toString());
                    } catch (IOException e) {
                        showToast("Unable to set the receiver's frequency", Toast.LENGTH_LONG);
                    } catch (IllegalArgumentException e) {
                        showToast("Unable to set the receiver's frequency", Toast.LENGTH_LONG);
                    }
                }
            }

            // Returns the new frequency.
            public void onScan(int tunedFrequency, int signalStrength, int scanDirection, boolean aborted) {
                updateFrequency(tunedFrequency, false);
                ((ImageButton) findViewById(R.id.ScanUp)).setEnabled(true);
                ((ImageButton) findViewById(R.id.ScanDown)).setEnabled(true);
            }
        };
        mReceiverRdsDataFoundListener = new com.stericsson.hardware.fm.FmReceiver.OnRDSDataFoundListener() {

            // Receives the current frequency's RDS Data
            public void onRDSDataFound(Bundle rdsData, int frequency) {
                if (mFmReceiver.getState() != FmReceiver.STATE_STARTED)
                    return;

                if (rdsData.containsKey("PSN")) {
                    mStationNameTextView.setText(rdsData.getString("PSN").trim());
                    setNotification(rdsData.getString("PSN").trim(), mCurrentFrequency);
                }
                
                if (rdsData.containsKey("RT")) {
                	String text = rdsData.getString("RT").trim();

                	// only update if the text differs, otherwise this messes
                	// up the marquee
                	if (!mStationInfoTextView.getText().equals(text))
                    	mStationInfoTextView.setText(text);
                }

                if (rdsData.containsKey("PTY")) {
                    int pty = rdsData.getShort("PTY");
                    if (pty > 0)
                        mProgramTypeTextView.setText(getPTYName(pty));
                    else
                        mProgramTypeTextView.setText("");
                }

            }
        };

        mReceiverStartedListener = new com.stericsson.hardware.fm.FmReceiver.OnStartedListener() {

            public void onStarted() {
                // Activate all the buttons
                ((ImageButton) findViewById(R.id.ScanUp)).setEnabled(true);
                ((ImageButton) findViewById(R.id.ScanDown)).setEnabled(true);
                ((ImageButton) findViewById(R.id.Pause)).setEnabled(true);
                //((ImageButton) findViewById(R.id.FullScan)).setEnabled(true);
                //initialBandscan();
                startAudio();
                if (mCurrentFrequency > 0) {
                    updateFrequency(mCurrentFrequency, true);
                } else {
                    mFmReceiver.scanUp();
                }
            }
        };

        mFmReceiver.addOnScanListener(mReceiverScanListener);
        mFmReceiver.addOnRDSDataFoundListener(mReceiverRdsDataFoundListener);
        mFmReceiver.addOnStartedListener(mReceiverStartedListener);

        //if (!mRestart) {
            turnRadioOn();
        //}
        mRestart = false;
    }

    /**
     * Stops the FM Radio listeners
     */
    @Override
    protected void onRestart() {
        super.onRestart();
        mRestart = true;
    }

    /**
     * Stops the FM Radio listeners
     */
    @Override
    protected void onStop() {
        super.onStop();

        if (mFmReceiver != null) {
            mFmReceiver.removeOnScanListener(mReceiverScanListener);
            mFmReceiver.removeOnRDSDataFoundListener(mReceiverRdsDataFoundListener);
            mFmReceiver.removeOnStartedListener(mReceiverStartedListener);
        }

        try {
            if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED) {
                AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                mFmReceiver.reset();
                if (mMediaPlayer != null) {
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                }
                mNotificationManager.cancel(1);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Unable to reset correctly", e);
        }

    }

    /**
     * Saves the FmBand for next time the program is used and closes the radio
     * and media player.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putInt("selectedBand", mSelectedBand);
        editor.putInt("currentFrequency", mCurrentFrequency);
        editor.commit();
        try {
            if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED) {
                AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                mFmReceiver.reset();
                if (mMediaPlayer != null) {
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                }
                mNotificationManager.cancel(1);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Unable to reset correctly", e);
        }
    }

    private String getPTYName(int i) {
        final String[] ptymap = {
            "Unknown",
            "News",
            "Affairs",
            "Info",
            "Sport",
            "Educate",
            "Drama",
            "Culture",
            "Science",
            "Varied",
            "Pop",
            "Rock",
            "Easy Listening",
            "Light classic",
            "Serious classic",
            "Other",
            "Weather",
            "Finance",
            "Children",
            "Social",
            "Religion",
            "Phone In",
            "Travel",
            "Leisure",
            "Jazz",
            "Country",
            "National",
            "Oldies",
            "Folk",
            "Documentary",
            "Alarm Test",
            "Alarm"
        };
        return ptymap[i];
    }

    /**
     * Starts the initial bandscan in it's own thread
     */
    private void initialBandscan() {
        Thread bandscanThread = new Thread() {
            public void run() {
                try {
                    mFmReceiver.startFullScan();
                } catch (IllegalStateException e) {
                    showToast("Unable to start the scan", Toast.LENGTH_LONG);
                    return;
                }
            }
        };
        bandscanThread.start();
    }

    /**
     * Helper method to display toast
     */
    private void showToast(final String text, final int duration) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), text, duration).show();
            }
        });
    }

    /**
     * Prepare notification object
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

    private void setNotification(String stationName, int frequency) {
        if (stationName != null && frequency > 0) {
            mRadioNotification.setContentTitle(stationName)
                .setContentText(formatFrequency(frequency) + " MHz");
            Notification n = mRadioNotification.build();
            mNotificationManager.notify(1, n);
        } else if (frequency > 0) {
            mRadioNotification.setContentTitle(formatFrequency(frequency) + " MHz")
                .setContentText("");
            Notification n = mRadioNotification.build();
            mNotificationManager.notify(1, n);
        }
    }

    private String formatFrequency (int frequency) {
        String a = Double.toString((double) frequency / 1000);
        if (mFmBand.getChannelOffset() == CHANNEL_OFFSET_50KHZ) {
            a = String.format(a, "%.2f");
        } else {
            a = String.format(a, "%.1f");
        }
        return a;
    }

    /**
     * Starts the FM receiver and makes the buttons appear inactive
     */
    private void turnRadioOn() {
        // the receiver might be running already because this activity
        // was killed
        if (mFmReceiver.getState() == FmReceiver.STATE_IDLE) {
            try {
                mFmReceiver.startAsync(mFmBand);
                // Darken the the buttons
                ((ImageButton) findViewById(R.id.ScanUp)).setEnabled(false);
                ((ImageButton) findViewById(R.id.ScanDown)).setEnabled(false);
                ((ImageButton) findViewById(R.id.Pause)).setEnabled(false);
                ((ImageButton) findViewById(R.id.FullScan)).setEnabled(false);
                //showToast("Scanning initial stations", Toast.LENGTH_LONG);
            } catch (IOException e) {
                showToast("Unable to start the radio receiver.", Toast.LENGTH_LONG);
            } catch (IllegalStateException e) {
                showToast("Unable to start the radio receiver.", Toast.LENGTH_LONG);
            }
        } else {
	        updateFrequency(mCurrentFrequency, true);
        }
    }

    /**
     * Starts the FM receiver and makes the buttons appear inactive
     */
    private void startAudio() {

        mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource("fmradio://rx");
            mMediaPlayer.prepare();
            mMediaPlayer.start();
        } catch (IOException e) {
            //showToast("Unable to start the media player", Toast.LENGTH_LONG);
            // fall back to legacy audio routing
            AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
            AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_FM, AudioSystem.DEVICE_STATE_AVAILABLE, "");
        }
    }

    /**
     * Update frequency state and UI
     *
     * @param freq new frequency to set in KHz
     * @param whether frequency should be set on the receiver
     *
     */
    private boolean updateFrequency(int frequency, boolean setFrequency) {
        mCurrentFrequency = frequency;

        if (setFrequency == true) {
            try {
                // only change frequency if it's different, otherwise
                // an audible pop can occur
                if (mFmReceiver.getFrequency() != frequency)
                    mFmReceiver.setFrequency(frequency);
                // resume receiver if it is paused
                if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED)
                    mFmReceiver.resume();
            } catch (IllegalStateException e) {
                showToast("Unable to seek", Toast.LENGTH_LONG);
                return false;
            } catch (IOException e) {
                showToast("Unable to resume", Toast.LENGTH_LONG);
                return false;
            }
        }

        mFrequencyTextView.setText(formatFrequency(mCurrentFrequency));
        setNotification(null, mCurrentFrequency);
        mStationInfoTextView.setText("");
        mStationNameTextView.setText(R.string.no_rds);
        mProgramTypeTextView.setText("");

        return true;
    }

    /**
     * Sets up the buttons and their listeners
     */
    private void setupButtons() {

        mMenuAdapter = new ArrayAdapter<CharSequence>(this, android.R.layout.simple_spinner_item);
        mMenuAdapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);
        mMenuAdapter.add(getResources().getString(R.string.no_stations));
        mFrequencyTextView = (TextView) findViewById(R.id.FrequencyTextView);
        mStationNameTextView = (TextView) findViewById(R.id.PSNTextView);
        mStationInfoTextView = (TextView) findViewById(R.id.RTTextView);
        mProgramTypeTextView = (TextView) findViewById(R.id.PTYTextView);
        mStationInfoTextView.setSelected(true);

        final ImageButton scanUp = (ImageButton) findViewById(R.id.ScanUp);
        final ImageButton scanDown = (ImageButton) findViewById(R.id.ScanDown);
        final ImageButton pause = (ImageButton) findViewById(R.id.Pause);
        final ImageButton fullScan = (ImageButton) findViewById(R.id.FullScan);

        scanUp.setOnLongClickListener (new OnLongClickListener() {

            public boolean onLongClick(View v) {
                int newFrequency = mCurrentFrequency + mFmBand.getChannelOffset();
                return updateFrequency(newFrequency, true);
            }
        });

        scanDown.setOnLongClickListener (new OnLongClickListener() {

            public boolean onLongClick(View v) {
                int newFrequency = mCurrentFrequency - mFmBand.getChannelOffset();
                return updateFrequency(newFrequency, true);
            }
        });

        scanUp.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                try {
                    if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED)
                        mFmReceiver.resume();
                    mFmReceiver.scanUp();
                } catch (IllegalStateException e) {
                    showToast("Unable to ScanUp", Toast.LENGTH_LONG);
                    return;
                } catch (IOException e) {
                    showToast("Unable to resume", Toast.LENGTH_LONG);
                    return;
                }
                scanUp.setEnabled(false);
            }
        });

        scanDown.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                try {
                    if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED)
                        mFmReceiver.resume();
                    mFmReceiver.scanDown();
                } catch (IllegalStateException e) {
                    showToast("Unable to ScanDown", Toast.LENGTH_LONG);
                    return;
                } catch (IOException e) {
                    showToast("Unable to resume", Toast.LENGTH_LONG);
                    return;
                }
                scanDown.setEnabled(false);
            }
        });

        pause.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                if (mFmReceiver.getState() == FmReceiver.STATE_PAUSED && mPauseMutex != true) {
                    try {
                        mPauseMutex = true;
                        mFmReceiver.resume();
                        if (mMediaPlayer != null)
                            mMediaPlayer.start();
                        pause.setImageResource(R.drawable.pausebutton);
                        setNotification(null, mCurrentFrequency);
                    } catch (IOException e) {
                        showToast("Unable to resume", Toast.LENGTH_LONG);
                    } catch (IllegalStateException e) {
                        showToast("Unable to resume", Toast.LENGTH_LONG);
                    }
                    mPauseMutex = false;
                } else if (mFmReceiver.getState() == FmReceiver.STATE_STARTED
                        && mPauseMutex != true) {
                    try {
                        mPauseMutex = true;
                        if (mMediaPlayer != null)
                            mMediaPlayer.pause();
                        mFmReceiver.pause();
                        pause.setImageResource(R.drawable.playbutton);
                        mNotificationManager.cancel(1);
                    } catch (IOException e) {
                        showToast("Unable to pause", Toast.LENGTH_LONG);
                    } catch (IllegalStateException e) {
                        showToast("Unable to pause", Toast.LENGTH_LONG);
                    }
                    mPauseMutex = false;
                } else if (mPauseMutex) {
                    showToast("MediaPlayer busy. Please wait and try again.", Toast.LENGTH_LONG);
                } else {
                    Log.i(LOG_TAG, "No action: incorrect state - " + mFmReceiver.getState());
                }
            }
        });

        fullScan.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                try {
                    //fullScan.setEnabled(false);
                    //showToast("Scanning for stations", Toast.LENGTH_LONG);
                    mFmReceiver.startFullScan();
                } catch (IllegalStateException e) {
                    showToast("Unable to start the scan", Toast.LENGTH_LONG);
                }
            }
        });
    }

    /**
     * Sets up the options menu when the menu button is pushed, dynamic
     * population of the station select menu
     */
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        boolean result = super.onCreateOptionsMenu(menu);
        SubMenu subMenu = menu.addSubMenu(BASE_OPTION_MENU, FM_BAND, Menu.NONE,
                R.string.band_select);
        subMenu.setIcon(android.R.drawable.ic_menu_mapmode);
        // Populate the band selection menu
        subMenu.add(BAND_SELECTION_MENU, BAND_US, Menu.NONE, R.string.band_us);
        subMenu.add(BAND_SELECTION_MENU, BAND_EU, Menu.NONE, R.string.band_eu);
        subMenu.add(BAND_SELECTION_MENU, BAND_JAPAN, Menu.NONE, R.string.band_ja);
        subMenu.add(BAND_SELECTION_MENU, BAND_CHINA, Menu.NONE, R.string.band_ch);
        subMenu.setGroupCheckable(BAND_SELECTION_MENU, true, true);
        subMenu.getItem(mSelectedBand).setChecked(true);

        subMenu = menu.addSubMenu(BASE_OPTION_MENU, STATION_SELECT, Menu.NONE,
                R.string.station_select);
        subMenu.setIcon(android.R.drawable.ic_menu_more);
        subMenu.getItem().setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        // Dynamically populate the station select menu each time the option
        // button is pushed
        if (mMenuAdapter.isEmpty()) {
            subMenu.setGroupEnabled(STATION_SELECTION_MENU, false);
        } else {
            subMenu.setGroupEnabled(STATION_SELECTION_MENU, true);
            for (int i = 0; i < mMenuAdapter.getCount(); i++) {
                subMenu.add(STATION_SELECTION_MENU, STATION_SELECT_MENU_ITEMS + i, Menu.NONE,
                        mMenuAdapter.getItem(i));
            }
            subMenu.setGroupCheckable(STATION_SELECTION_MENU, true, true);
        }
        return result;
    }

    public int getSelectStationMenuItem(MenuItem item) {
        return item.getItemId() - STATION_SELECT_MENU_ITEMS;
    }

    /**
     * React to a selection in the option menu
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getGroupId()) {
            case BAND_SELECTION_MENU:
                switch (item.getItemId()) {
                    case BAND_US:
                        mSelectedBand = FmBand.BAND_US;
                        item.setChecked(true);
                        break;
                    case BAND_EU:
                        mSelectedBand = FmBand.BAND_EU;
                        item.setChecked(true);
                        break;
                    case BAND_JAPAN:
                        mSelectedBand = FmBand.BAND_JAPAN;
                        item.setChecked(true);
                        break;
                    case BAND_CHINA:
                        mSelectedBand = FmBand.BAND_CHINA;
                        item.setChecked(true);
                        break;
                    default:
                        break;
                }
                mFmBand = new FmBand(mSelectedBand);
                try {
                    mFmReceiver.reset();
                    mCurrentFrequency = mFmBand.getDefaultFrequency();
                    updateFrequency(mCurrentFrequency, true);
                } catch (IOException e) {
                    showToast("Unable to restart the FM Radio", Toast.LENGTH_LONG);
                }
                if (mMediaPlayer != null) {
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                }
                turnRadioOn();
                break;
            case STATION_SELECTION_MENU:
                try {
                    if (!mMenuAdapter.getItem(getSelectStationMenuItem(item)).toString().matches(
                            EMPTY_STATION_LIST)) {
                        int freq = (int) (Double.valueOf(mMenuAdapter.getItem(
                                getSelectStationMenuItem(item)).toString()) * 1000);
                        updateFrequency(freq, true);
                        mFrequencyTextView.setText(mMenuAdapter.getItem(
                                getSelectStationMenuItem(item)).toString());
                    }
                } catch (IllegalArgumentException e) {
                    showToast("Unable to set the frequency", Toast.LENGTH_LONG);
                }

                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }
}
