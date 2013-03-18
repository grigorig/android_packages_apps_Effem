/*
 * Copyright (C) 2013 Grigori Goronzy <greg@chown.ath.cx>
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

import android.os.*;
import android.view.View;
import android.view.MotionEvent;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.ImageButton;


/**
 * ImageButton with support for repeated longclicks
 *
 * @author Grigori Goronzy
 */
public class RepeatImageButton extends ImageButton {
    private OnLongClickListener repeatClickListener;
    private OnClickListener clickListener;

    private int delay = 700;
    private int interval = 400;
    private boolean enabled = true;

    private boolean longClickFired;
    private Handler mHandler;

    private final Runnable repeatAction = new Runnable() {
        @Override
        public void run() {
            if (repeatClickListener != null) {
                boolean res = repeatClickListener
                    .onLongClick(RepeatImageButton.this);
                if (res == true)
                    mHandler.postDelayed(this, interval);
                longClickFired = true;
            }
        }
    };

    public RepeatImageButton(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mHandler != null)
                    return true;
                longClickFired = false;
                if (enabled) {
                    mHandler = new Handler();
                    mHandler.postDelayed(repeatAction, delay);
                    setPressed(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mHandler == null)
                    return true;
                mHandler.removeCallbacks(repeatAction);
                mHandler = null;
                setPressed(false);
                if (!longClickFired && clickListener != null) {
                    clickListener.onClick(RepeatImageButton.this);
                }
                break;
        }
        return true;
    }

    @Override
    public void setEnabled(boolean state) {
        super.setEnabled(state);
        enabled = state;
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener listener) {
        repeatClickListener = listener;
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        clickListener = listener;
    }

    /**
     * Set click repeat interval
     *
     * @param d initial delay
     * @param i repeat interval in milliseconds (default is 400ms)
     */
    public void setRepeatInterval(int d, int i) {
        delay = d;
        interval = i;
    }
}
