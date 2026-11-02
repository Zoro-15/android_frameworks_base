/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2020 ProjectFluid
 * Copyright (C) 2019-2021 LightningFastRom
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
package com.android.keyguard.clock;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;

import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

import static com.android.systemui.statusbar.phone
        .KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

/**
 * Plugin for Apple lock screen clock face.
 */
public class AppleClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    private final SysuiColorExtractor mColorExtractor;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Root view of clock.
     */
    private ClockLayout mView;

    /**
     * Text clocks in view hierarchy.
     */
    private TextClock mTimeClock;
    private TextClock mDay;
    private TextClock mDate;

    /**
     * Create an AppleClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public AppleClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.digital_clock_apple, null);
        mTimeClock = mView.findViewById(R.id.time_clock);
        mDay = mView.findViewById(R.id.clock_day);
        mDate = mView.findViewById(R.id.clock_date);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTimeClock = null;
        mDay = null;
        mDate = null;
    }

    @Override
    public String getName() {
        return "apple";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_apple);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.apple_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {
        View previewView = mLayoutInflater.inflate(R.layout.digital_apple_preview, null);
        TextClock previewTime = previewView.findViewById(R.id.time_clock);
        TextClock previewDay = previewView.findViewById(R.id.clock_day);
        TextClock previewDate = previewView.findViewById(R.id.clock_date);

        // Initialize state of plugin before generating preview.
        previewTime.setTextColor(Color.WHITE);
        previewDay.setTextColor(Color.WHITE);
        previewDate.setTextColor(Color.WHITE);

        onTimeTick();

        return mRenderer.createPreview(previewView, width, height);
    }

    @Override
    public View getView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public View getBigClockView() {
        return null;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return CLOCK_USE_DEFAULT_Y;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        if (mTimeClock != null) {
            mTimeClock.setTextColor(color);
        }
        if (mDay != null) {
            mDay.setTextColor(color);
        }
        if (mDate != null) {
            mDate.setTextColor(color);
        }
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {}

    @Override
    public void onTimeTick() {
        if (mView != null) {
            mView.onTimeChanged();
            if (mTimeClock != null) mTimeClock.refresh();
            if (mDay != null) mDay.refresh();
            if (mDate != null) mDate.refresh();
        }
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        if (mView != null) {
            mView.setDarkAmount(darkAmount);
        }
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        if (mTimeClock != null) mTimeClock.setTimeZone(timeZone.getID());
        if (mDay != null) mDay.setTimeZone(timeZone.getID());
        if (mDate != null) mDate.setTimeZone(timeZone.getID());
        onTimeTick();
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
