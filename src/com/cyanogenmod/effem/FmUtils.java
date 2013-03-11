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

import android.os.Handler;
import android.content.Context;
import android.widget.Toast;

public class FmUtils {
    public static final int CHANNEL_OFFSET_50KHZ = 50;

    /**
     * Format frequency in KHz to humanly readable format
     *
     * @param offset channel offset in KHz
     * @param frequency frequency in KHz
     * @return formatted string
     */
    public static String formatFrequency (int offset, int frequency) {
        String a = Double.toString((double) frequency / 1000);
        if (offset == CHANNEL_OFFSET_50KHZ) {
            a = String.format(a, "%.2f");
        } else {
            a = String.format(a, "%.1f");
        }
        return a;
    }

    /**
     * Get (localized) Program Type Name (PTY) string from PTY number
     *
     * @param i PTY number in 0..31
     * @return PTY string
     */
    public static String getPTYName(Context ctx, int i) {
        if (i < 0 || i > 31)
            i = 0;
        return ctx.getResources().getStringArray(R.array.pty_names)[i];
    }

    /**
     * Schedule a toast message to be shown via supplied handler.
     *
     * @param handler the handler the toast should be scheduled to
     * @param id string resource id for the message text
     * @param duration toast duration constant
     */
    public static void showToast(final Context ctx, final Handler handler,
            final int id, final int duration) {
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(ctx, id, duration).show();
            }
        });
    }

    /**
     * Schedule a toast message to be shown via supplied handler.
     *
     * @param handler the handler the toast should be scheduled to
     * @param text string for the message text
     * @param duration toast duration constant
     */
    public static void showToast(final Context ctx, final Handler handler,
            final String text, final int duration) {
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(ctx, text, duration).show();
            }
        });
    }
}
