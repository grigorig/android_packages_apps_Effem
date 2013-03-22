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
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.*;
import android.util.Log;
import android.view.Gravity;
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
import android.media.AudioManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.graphics.Typeface;

import java.io.IOException;
import java.util.Comparator;
import org.json.*;

public class FmRadio extends Activity
        implements FmRadioService.Callbacks, ServiceConnection {

    private static final String LOG_TAG = "Effem";
    public static final String PREFS_NAME = "FMRadioPrefsFile";

    // Menu identifiers
    private static final int BASE_OPTION_MENU = 0;
    private static final int BAND_SELECTION_MENU = 1;
    private static final int LOUDSPEAKER_SELECTION_MENU = 2;
    private static final int BT_EXIT_BEHAVIOUR_SELECTION_MENU = 3;
    private static final int HEADSET_EXIT_BEHAVIOUR_SELECTION_MENU = 4;
    private static final int STATION_SELECTION_MENU = 5;

    public static final int FM_BAND = Menu.FIRST;
    public static final int BAND_US = Menu.FIRST + 1;
    public static final int BAND_EU = Menu.FIRST + 2;
    public static final int BAND_JAPAN = Menu.FIRST + 3;
    public static final int BAND_CHINA = Menu.FIRST + 4;

    public static final int OUTPUT_SOUND = Menu.FIRST + 5;
    public static final int OUTPUT_HEADSET = Menu.FIRST + 6;
    public static final int OUTPUT_SPEAKER = Menu.FIRST + 7;

    public static final int BT_EXIT_BEHAVIOUR = Menu.FIRST + 8;
    public static final int BT_EXIT_BEHAVIOUR_DONOTHING = Menu.FIRST + 9;
    public static final int BT_EXIT_BEHAVIOUR_RESTOREINITIALBTSTATE = Menu.FIRST + 10;
    public static final int BT_EXIT_BEHAVIOUR_PROMPT = Menu.FIRST + 11;
    public static final int BT_EXIT_BEHAVIOUR_ALWAYSDISABLE = Menu.FIRST + 12;

    public static final int HEADSET_EXIT_BEHAVIOUR = Menu.FIRST + 13;
    public static final int HEADSET_EXIT_BEHAVIOUR_OFF = Menu.FIRST + 14;
    public static final int HEADSET_EXIT_BEHAVIOUR_ON = Menu.FIRST + 15;

    public static final int STATION_SELECT = Menu.FIRST + 16;
    public static final int STATION_SELECT_MENU_ITEMS = STATION_SELECT + 1;

    // Application context
    private Context context;

    // Views for frequency, station name, program type and radio text
    private TextView mFrequencyTextView;
    private TextView mStationNameTextView;
    private TextView mProgramTypeTextView;
    private TextView mStationInfoTextView;

    // FM state
    private HandlerThread mWorker;
    private Handler mWorkerHandler;
    private FmRadioService mService;
    private int mCurrentFrequency;
    private boolean mFirstStart = true;
    private int mSelectedBand;
    private int mSelectedOutput;

    // BT
    private int mBluetoothExitBehaviour = 0;
    private int mHeadsetBehaviour = 0;
    private boolean mBluetoothEnabled = false;
    private boolean mInitialBtState = false;
    private ProgressDialog mBluetoothStartingDialog;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                mBluetoothEnabled = (state == BluetoothAdapter.STATE_ON);
                if (!mBluetoothEnabled) {
                    // Bluetooth is disabled so we should turn off FM too.
                    if (mService != null) {
                        mService.stopRadio();
                    }
                }
            }
        }
    };

    // Array of the available stations in MHz
    private ArrayAdapter<MenuTuple> mMenuAdapter;

    /**
     * Required method from parent class
     *
     * @param icicle - The previous instance of this app
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        context = getApplicationContext();
        setContentView(R.layout.main);

        // restore preferences
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        mSelectedBand = settings.getInt("selectedBand", 1);
        mCurrentFrequency = settings.getInt("currentFrequency", 0);
        if (context.getResources().getBoolean(R.bool.speaker_supported)) {
            mSelectedOutput = settings.getInt("selectedOutput", 0) > 0 ? 1 : 0;
        }
        if (context.getResources().getBoolean(R.bool.require_bt)) {
            mBluetoothExitBehaviour = settings.getInt("bluetoothExitBehaviour", 0);
            mHeadsetBehaviour = settings.getInt("headsetBehaviour", 0);
        }

        // misc setup
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // BT
        if (getResources().getBoolean(R.bool.require_bt)) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiver(mIntentReceiver, filter);
        }

        // worker thread for async execution of FM stuff
        mWorker = new HandlerThread("EffemWorker");
        mWorker.start();
        mWorkerHandler = new Handler(mWorker.getLooper());

        // ui preparations
        setupButtons();
    }

    /**
     * Starts up the listeners and the FM radio if it isn't already active
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.i(LOG_TAG, "onResume");

        // start and bind to service
        startService(new Intent(this, FmRadioService.class));
        bindService(new Intent(this, FmRadioService.class), this,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName component, IBinder binder) {
        mService = ((FmRadioService.LocalBinder)binder).getService();
        // start radio on initial start
        mWorkerHandler.post(new Runnable() { public void run() {
		if (context.getResources().getBoolean(R.bool.require_bt)) {
			asyncCheckAndEnableRadio();
		} else {
                    if (mFirstStart)
                        mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                }
                mService.resumeCallbacks();
                mService.setCallbacks(FmRadio.this);
                }});
        mFirstStart = false;
    }

    @Override
    public void onServiceDisconnected(ComponentName component) {
        mService = null;

        if (mHeadsetBehaviour > 0) {
            toggleRadioOffBluetoothExitBehaviour();
        }
    }

    /**
     * Stops the FM Radio listeners and unbinds/stops service
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.i(LOG_TAG, "onPause");

        // suspend callbacks to save power
        // especially, this will disable RDS
        mService.suspendCallbacks();

        // if no playback is going on, the service can exit
        if (!mService.isStarted())
            stopService(new Intent(this, FmRadioService.class));

        // unbind from service
        unbindService(this);
    }

    /**
     * Saves the FmBand for next time the program is used and closes the radio
     * and media player.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWorker.quit();

        // save preferences
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putInt("selectedBand", mSelectedBand);
        editor.putInt("currentFrequency", mCurrentFrequency);
        if (context.getResources().getBoolean(R.bool.speaker_supported)) {
            editor.putInt("selectedOutput", mSelectedOutput);
        }
        if (context.getResources().getBoolean(R.bool.require_bt)) {
            editor.putInt("bluetoothExitBehaviour", mBluetoothExitBehaviour);
            editor.putInt("headsetBehaviour", mHeadsetBehaviour);
        }
        try {
            JSONObject conf = new JSONObject();
            JSONArray stations = new JSONArray();
            conf.put("stations", stations);
            for (int i=0; i < mMenuAdapter.getCount(); i++)
                stations.put(mMenuAdapter.getItem(i).toJSON());
            editor.putString("stations", conf.toString());
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to save station list");
        }
        editor.commit();

        // Toggle BT on/off depending on value in preferences
        if (context.getResources().getBoolean(R.bool.require_bt)) {
            toggleRadioOffBluetoothExitBehaviour();
        }

        // Unregister BT receiver
        if (mIntentReceiver != null && getResources().getBoolean(R.bool.require_bt)) {
            unregisterReceiver(mIntentReceiver);
            mIntentReceiver = null;
        }
    }

    @Override
    public void onReceiverStateChanged(boolean state) {
        Log.i(LOG_TAG, "onReceiverStateChanged " + state);

        // switch button image for play/pause
        ImageButton favorite = (ImageButton) findViewById(R.id.Favorite);
        ImageButton pause    = (ImageButton) findViewById(R.id.Pause);
        if (state == true) {
            pause.setImageResource(R.drawable.pausebutton);
            favorite.setEnabled(true);
        } else {
            pause.setImageResource(R.drawable.playbutton);
            favorite.setEnabled(false);
        }
    }

    @Override
    public void onFrequencyChanged(int frequency, int offset) {
        mCurrentFrequency = frequency;
        String freqFormatted = FmUtils.formatFrequency(offset,
                mCurrentFrequency);

        ((ImageButton) findViewById(R.id.ScanUp)).setEnabled(true);
        ((ImageButton) findViewById(R.id.ScanDown)).setEnabled(true);

        mFrequencyTextView.setText(freqFormatted);
        mStationInfoTextView.setText("");
        mStationNameTextView.setText(R.string.no_rds);
        mProgramTypeTextView.setText("");

        final ImageButton favorite = (ImageButton) findViewById(R.id.Favorite);
        if (getFavorite(frequency))
            favorite.setImageResource(R.drawable.favoritebuttonpress);
        else
            favorite.setImageResource(R.drawable.favoritebutton);
    }

    @Override
    public void onRdsDataAvailable(Bundle rdsData) {
        if (rdsData.containsKey("PSN")) {
            mStationNameTextView.setText(rdsData.getString("PSN").trim());
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
                mProgramTypeTextView.setText(FmUtils.getPTYName(this, pty));
            else
                mProgramTypeTextView.setText("");
        }
    }

    private void asyncCheckAndEnableRadio() {
        Log.d(LOG_TAG, "asyncCheckAndEnableRadio");

        // Save the initial state of the BT adapter
        mBluetoothEnabled = BluetoothAdapter.getDefaultAdapter().isEnabled();
        mInitialBtState  = mBluetoothEnabled;

        if (mBluetoothEnabled) {
            mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
        }
        else {
            // Enable the BT adapter
            BluetoothAdapter.getDefaultAdapter().enable();

            AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
                @Override
                protected void onPreExecute() {
                    if (mBluetoothStartingDialog != null) {
                        mBluetoothStartingDialog.dismiss();
                        mBluetoothStartingDialog = null;
                    }
                    mBluetoothStartingDialog = ProgressDialog.show(FmRadio.this, null, getString(R.string.init_FM), true, false);
                    super.onPreExecute();
                }

                @Override
                protected Boolean doInBackground(Void... params) {
                    int n = 0;
                    try {
                        while (!mBluetoothEnabled && n < 30) {
                            Thread.sleep(1000);
                            ++n;
                        }
                    } catch (InterruptedException e) {
                    } finally {
                        return true;
                    }
                }

                @Override
                protected void onPostExecute(Boolean result) {
                    if (mBluetoothStartingDialog != null) {
                        mBluetoothStartingDialog.dismiss();
                        mBluetoothStartingDialog = null;
                    }
                    if (mBluetoothEnabled){
                        mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                    }
                    else {
                        Toast toast = Toast.makeText(FmRadio.this, getString(R.string.need_bluetooth), Toast.LENGTH_LONG);
                        toast.setGravity(Gravity.TOP | Gravity.CENTER, 0, 240);
                        toast.show();
                    }
                    super.onPostExecute(result);
                }
            };
            task.execute();
        }
    }

    private void toggleRadioOffBluetoothExitBehaviour() {
        Log.d(LOG_TAG, "toggleRadioOffBluetoothBehavior");

        // Check if the BT adapter is currently enabled
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            switch (mBluetoothExitBehaviour) {
                case 0:
                    Log.d(LOG_TAG, "toggleRadioOffBluetoothBehavior: Preference is to leave BT "+
                                  "adapter on so not disabling");
                    break;
                case 1: // Restore initial BT state
                    if (!mInitialBtState) {
                        BluetoothAdapter.getDefaultAdapter().disable();
                    }
                    break;

                case 2: // Prompt for action
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setMessage(R.string.prompt_disable_bt)
                    .setPositiveButton(R.string.prompt_yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            BluetoothAdapter.getDefaultAdapter().disable();
                        }})
                    .setNegativeButton(R.string.prompt_no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            // Do nothing
                        }})
                    .show();
                    break;

                case 3: // Always disable bluetooth
                    BluetoothAdapter.getDefaultAdapter().disable();
                    break;
            }
        }
    }

    /**
     * Sets up the buttons and their listeners
     */
    private void setupButtons() {
        // populate favorites menu
        mMenuAdapter = new ArrayAdapter<MenuTuple>(this, android.R.layout.simple_spinner_item);

        try {
            SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
            JSONObject conf = new JSONObject(settings.getString("stations", ""));
            JSONArray stations = conf.getJSONArray("stations");
            for (int i=0; i < stations.length(); i++) {
                MenuTuple mt = MenuTuple.fromJSON(stations.getJSONObject(i));
                mMenuAdapter.add(mt);
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to load station list");
        }

        // get references to buttons
        mFrequencyTextView = (TextView) findViewById(R.id.FrequencyTextView);
        mStationNameTextView = (TextView) findViewById(R.id.PSNTextView);
        mStationInfoTextView = (TextView) findViewById(R.id.RTTextView);
        mProgramTypeTextView = (TextView) findViewById(R.id.PTYTextView);
        final ImageButton scanUp = (ImageButton) findViewById(R.id.ScanUp);
        final ImageButton scanDown = (ImageButton) findViewById(R.id.ScanDown);
        final ImageButton pause = (ImageButton) findViewById(R.id.Pause);
        final ImageButton favorite = (ImageButton) findViewById(R.id.Favorite);
        mStationInfoTextView.setSelected(true);

        // set typeface for station frequency widget
        // this is done here instead of layout xml to keep ICS compatibility
        mFrequencyTextView.setTypeface(Typeface.create("sans-serif-light",
                Typeface.NORMAL));

        scanUp.setOnLongClickListener (new OnLongClickListener() {
            public boolean onLongClick(View v) {
                scanUp.setEnabled(false);
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.changeFrequency(FmRadioService.SEEK_STEPUP, 0);
                }});
                return true;
            }
        });

        scanDown.setOnLongClickListener (new OnLongClickListener() {
            public boolean onLongClick(View v) {
                scanDown.setEnabled(false);
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.changeFrequency(FmRadioService.SEEK_STEPDOWN, 0);
                }});
                return true;
            }
        });

        scanUp.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                    if (mService.isStarted())
                        mService.changeFrequency(FmRadioService.SEEK_SCANUP, 0);
                }});
                scanUp.setEnabled(false);
            }
        });

        scanDown.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                    if (mService.isStarted())
                        mService.changeFrequency(FmRadioService.SEEK_SCANDOWN, 0);
                }});
                scanDown.setEnabled(false);
            }
        });

        pause.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mService.isStarted())
                    mService.stopRadio();
                else {
                    mWorkerHandler.post(new Runnable() { public void run() {
                        mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                    }});
                }
            }
        });

        favorite.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                toggleFavorite(v, mCurrentFrequency);
            }
        });
    }

    private boolean getFavorite(int frequency) {
        for (int i = 0; i < mMenuAdapter.getCount(); i++) {
            if (mMenuAdapter.getItem(i).frequency == frequency)
                return true;
        }
        return false;
    }

    private void toggleFavorite(View v, int frequency) {
        final ImageButton favorite = (ImageButton) findViewById(R.id.Favorite);

        // check if it already exists
        if (getFavorite(frequency)) {
            // favorite should be removed
            for (int i = 0; i < mMenuAdapter.getCount(); i++) {
                if (mMenuAdapter.getItem(i).frequency == frequency)
                    mMenuAdapter.remove(mMenuAdapter.getItem(i));
            }
            invalidateOptionsMenu();
            favorite.setImageResource(R.drawable.favoritebutton);
        } else {
            // insert favorite
            int offset = mService.getChannelOffset();
            String freqFormatted = FmUtils.formatFrequency(offset, frequency);
            String freqString = "";
            if (mStationNameTextView.getText() != getText(R.string.no_rds)) {
                freqString = mStationNameTextView.getText() + " (" + freqFormatted + ")";
            } else
                freqString = freqFormatted;
            mMenuAdapter.add(new MenuTuple(frequency, freqString));
            // sort (ascending)
            mMenuAdapter.sort(new Comparator<MenuTuple>() {
                public int compare(MenuTuple l, MenuTuple r) {
                    return l.frequency - r.frequency;
                }
                public boolean equals(Object obj) {
                    return this == obj;
                }
            });
            invalidateOptionsMenu();
            favorite.setImageResource(R.drawable.favoritebuttonpress);
        }
    }

    /**
     * Sets up the options menu when the menu button is pushed, dynamic
     * population of the station select menu
     */
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        boolean result = super.onCreateOptionsMenu(menu);

        // Create and populate the band selection menu
        SubMenu subMenu = menu.addSubMenu(BASE_OPTION_MENU, FM_BAND, Menu.NONE,
                R.string.band_select);
        subMenu.setIcon(android.R.drawable.ic_menu_mapmode);
        subMenu.add(BAND_SELECTION_MENU, BAND_US, Menu.NONE, R.string.band_us);
        subMenu.add(BAND_SELECTION_MENU, BAND_EU, Menu.NONE, R.string.band_eu);
        subMenu.add(BAND_SELECTION_MENU, BAND_JAPAN, Menu.NONE, R.string.band_ja);
        subMenu.add(BAND_SELECTION_MENU, BAND_CHINA, Menu.NONE, R.string.band_ch);
        subMenu.setGroupCheckable(BAND_SELECTION_MENU, true, true);
        subMenu.getItem(mSelectedBand).setChecked(true);

        // Create and populate the speaker/headset selection menu if speaker supported
        if (context.getResources().getBoolean(R.bool.speaker_supported)) {
            subMenu = menu.addSubMenu(BASE_OPTION_MENU, OUTPUT_SOUND, Menu.NONE,
                R.string.output_select);
            subMenu.setIcon(android.R.drawable.ic_menu_mapmode);
            subMenu.add(LOUDSPEAKER_SELECTION_MENU, OUTPUT_HEADSET, Menu.NONE,
                R.string.output_select_default);
            subMenu.add(LOUDSPEAKER_SELECTION_MENU, OUTPUT_SPEAKER, Menu.NONE,
                R.string.output_select_loudspeaker);
            subMenu.setGroupCheckable(LOUDSPEAKER_SELECTION_MENU, true, true);
            subMenu.getItem(mSelectedOutput).setChecked(true);
        }


        // Create and populate the bluetooth selection menu if BT supported
        if (context.getResources().getBoolean(R.bool.require_bt)) {
            // BT_EXIT_BEHAVIOUR
            subMenu = menu.addSubMenu(BASE_OPTION_MENU, BT_EXIT_BEHAVIOUR, Menu.NONE,
                R.string.bt_exit_behaviour);
            subMenu.setIcon(android.R.drawable.ic_menu_mapmode);
            subMenu.add(BT_EXIT_BEHAVIOUR_SELECTION_MENU, BT_EXIT_BEHAVIOUR_DONOTHING, Menu.NONE,
                R.string.bt_exit_behaviour_donothing);
            subMenu.add(BT_EXIT_BEHAVIOUR_SELECTION_MENU, BT_EXIT_BEHAVIOUR_RESTOREINITIALBTSTATE, Menu.NONE,
                R.string.bt_exit_behaviour_restoreinitialbtstate);
            subMenu.add(BT_EXIT_BEHAVIOUR_SELECTION_MENU, BT_EXIT_BEHAVIOUR_PROMPT, Menu.NONE,
                R.string.bt_exit_behaviour_prompt);
            subMenu.add(BT_EXIT_BEHAVIOUR_SELECTION_MENU, BT_EXIT_BEHAVIOUR_ALWAYSDISABLE, Menu.NONE,
                R.string.bt_exit_behaviour_alwaysdisable);
            subMenu.setGroupCheckable(BT_EXIT_BEHAVIOUR_SELECTION_MENU, true, true);
            subMenu.getItem(mBluetoothExitBehaviour).setChecked(true);

            // HEADSET_EXIT_BEHAVIOUR
            subMenu = menu.addSubMenu(BASE_OPTION_MENU, HEADSET_EXIT_BEHAVIOUR, Menu.NONE,
                R.string.headset_exit_behaviour);
            subMenu.setIcon(android.R.drawable.ic_menu_mapmode);
            subMenu.add(HEADSET_EXIT_BEHAVIOUR_SELECTION_MENU, HEADSET_EXIT_BEHAVIOUR_OFF, Menu.NONE,
                R.string.headset_exit_behaviour_off);
            subMenu.add(HEADSET_EXIT_BEHAVIOUR_SELECTION_MENU, HEADSET_EXIT_BEHAVIOUR_ON, Menu.NONE,
                R.string.headset_exit_behaviour_on);
            subMenu.setGroupCheckable(HEADSET_EXIT_BEHAVIOUR_SELECTION_MENU, true, true);
            subMenu.getItem(mHeadsetBehaviour).setChecked(true);
        }

        // Create the station select menu
        subMenu = menu.addSubMenu(BASE_OPTION_MENU, STATION_SELECT, Menu.NONE,
                R.string.station_select);
        subMenu.getItem().setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        // Dynamically populate the station select menu
        if (mMenuAdapter.isEmpty()) {
            subMenu.setGroupEnabled(STATION_SELECTION_MENU, false);
        } else {
            subMenu.setGroupEnabled(STATION_SELECTION_MENU, true);
            for (int i = 0; i < mMenuAdapter.getCount(); i++) {
                subMenu.add(STATION_SELECTION_MENU, STATION_SELECT_MENU_ITEMS + i, Menu.NONE,
                        mMenuAdapter.getItem(i).toString());
            }
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
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.stopRadio();
                    mService.startRadio(mSelectedBand, 0, mSelectedOutput);
                }});
                break;

            case LOUDSPEAKER_SELECTION_MENU:
                mSelectedOutput = (item.getItemId() == OUTPUT_HEADSET) ? 0 : 1;
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.stopRadio();
                    mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                }});
                break;

            case BT_EXIT_BEHAVIOUR_SELECTION_MENU:
                switch (item.getItemId()) {
                    case BT_EXIT_BEHAVIOUR_DONOTHING:
                        mBluetoothExitBehaviour = 0;
                        item.setChecked(true);
                        break;
                    case BT_EXIT_BEHAVIOUR_RESTOREINITIALBTSTATE:
                        mBluetoothExitBehaviour = 1;
                        item.setChecked(true);
                        break;
                    case BT_EXIT_BEHAVIOUR_PROMPT:
                        mBluetoothExitBehaviour = 2;
                        item.setChecked(true);
                        break;
                    case BT_EXIT_BEHAVIOUR_ALWAYSDISABLE:
                        mBluetoothExitBehaviour = 3;
                        item.setChecked(true);
                        break;
                    default:
                        break;

                }
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.stopRadio();
                    mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                }});
                break;

            case HEADSET_EXIT_BEHAVIOUR_SELECTION_MENU:
                switch (item.getItemId()) {
                    case HEADSET_EXIT_BEHAVIOUR_OFF:
                        mHeadsetBehaviour = 0;
                        item.setChecked(true);
                        break;
                    case HEADSET_EXIT_BEHAVIOUR_ON:
                        mHeadsetBehaviour = 1;
                        item.setChecked(true);
                        break;
                    default:
                        break;

                }
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.stopRadio();
                    mService.startRadio(mSelectedBand, mCurrentFrequency, mSelectedOutput);
                }});
                break;

            case STATION_SELECTION_MENU:
                final int freq = mMenuAdapter.getItem(getSelectStationMenuItem(item)).frequency;
                mWorkerHandler.post(new Runnable() { public void run() {
                    if (!mService.isStarted())
                        mService.startRadio(mSelectedBand, freq, mSelectedOutput);
                    else
                        mService.changeFrequency(FmRadioService.SEEK_ABSOLUTE, freq);
                }});
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }
}

class MenuTuple {
    public int frequency;
    public String name;

    public MenuTuple(int frequency, String name) {
        this.frequency = frequency;
        this.name = name;
    }

    public String toString() {
        return name;
    }

    // JSON

    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("frequency", frequency);
        json.put("name", name);
        return json;
    }

    public static MenuTuple fromJSON(JSONObject json) throws JSONException {
        MenuTuple mt = new MenuTuple(json.getInt("frequency"),
                json.getString("name"));
        return mt;
    }
}
