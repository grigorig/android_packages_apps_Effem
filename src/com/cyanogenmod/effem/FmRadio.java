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
import android.os.*;
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
import android.media.AudioManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;

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
    private static final int STATION_SELECTION_MENU = 2;
    public static final int FM_BAND = Menu.FIRST;
    public static final int BAND_US = Menu.FIRST + 1;
    public static final int BAND_EU = Menu.FIRST + 2;
    public static final int BAND_JAPAN = Menu.FIRST + 3;
    public static final int BAND_CHINA = Menu.FIRST + 4;
    public static final int STATION_SELECT = Menu.FIRST + 5;
    public static final int STATION_SELECT_MENU_ITEMS = STATION_SELECT + 1;

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
        setContentView(R.layout.main);

        // restore preferences
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        mSelectedBand = settings.getInt("selectedBand", 1);
        mCurrentFrequency = settings.getInt("currentFrequency", 0);

        // misc setup
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

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
    protected void onStart() {
        super.onStart();

        // start and bind to service
        startService(new Intent(this, FmRadioService.class));
        bindService(new Intent(this, FmRadioService.class), this,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName component, IBinder binder) {
        mService = ((FmRadioService.LocalBinder)binder).getService();
        mService.setCallbacks(this);

        // start radio on initial start
        if (mFirstStart) {
            mWorkerHandler.post(new Runnable() { public void run() {
                mService.startRadio(mSelectedBand, mCurrentFrequency);
            }});
            mFirstStart = false;
        }
        mService.resumeCallbacks();
    }

    @Override
    public void onServiceDisconnected(ComponentName component) {
        mService = null;
    }

    /**
     * Stops the FM Radio listeners
     */
    @Override
    protected void onStop() {
        super.onStop();

        // suspend callbacks to save power
        // especially, this will disable RDS
        mService.suspendCallbacks();

        // unbind from service
        unbindService(this);

        // if no playback is going on, the service can exit
        if (mService.isStarted() == false)
            stopService(new Intent(this, FmRadioService.class));
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

        scanUp.setOnLongClickListener (new OnLongClickListener() {
            public boolean onLongClick(View v) {
                scanUp.setEnabled(false);
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency);
                    if (mService.isStarted())
                        mService.changeFrequency(FmRadioService.SEEK_STEPUP, 0);
                }});
                return true;
            }
        });

        scanDown.setOnLongClickListener (new OnLongClickListener() {
            public boolean onLongClick(View v) {
                scanDown.setEnabled(false);
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency);
                    if (mService.isStarted())
                        mService.changeFrequency(FmRadioService.SEEK_STEPDOWN, 0);
                }});
                return true;
            }
        });

        scanUp.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency);
                    if (mService.isStarted())
                        mService.changeFrequency(FmRadioService.SEEK_SCANUP, 0);
                }});
                scanUp.setEnabled(false);
            }
        });

        scanDown.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mWorkerHandler.post(new Runnable() { public void run() {
                    mService.startRadio(mSelectedBand, mCurrentFrequency);
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
                        mService.startRadio(mSelectedBand, mCurrentFrequency);
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
                    mService.startRadio(mSelectedBand, 0);
                }});
                break;
            case STATION_SELECTION_MENU:
                final int freq = mMenuAdapter.getItem(getSelectStationMenuItem(item)).frequency;
                mWorkerHandler.post(new Runnable() { public void run() {
                    if (!mService.isStarted())
                        mService.startRadio(mSelectedBand, freq);
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
