/*
 * Copyright (C) 2020 The LineageOS Project
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

package com.android.systemui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;

public class ImageUtilities {
    private static final String TAG = "ImageUtilities";
    public static final float DEFAULT_BLUR_RADIUS = 25.0f;
    private static final float BITMAP_SCALE = 0.35f;

    public static Bitmap blur(Context context, Bitmap image, float radius) {
        if (image == null) return null;
        float radAmount = Math.max(0.1f, Math.min(25.0f, radius));
        RenderScript rs = null;
        try {
            Bitmap output = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);

            rs = RenderScript.create(context);
            Allocation tmpIn = Allocation.createFromBitmap(rs, image);
            Allocation tmpOut = Allocation.createFromBitmap(rs, output);

            ScriptIntrinsicBlur blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blurScript.setRadius(radAmount);
            blurScript.setInput(tmpIn);
            blurScript.forEach(tmpOut);
            tmpOut.copyTo(output);

            return output;
        } catch (Exception e) {
            Log.e(TAG, "Error applying ScriptIntrinsicBlur", e);
            return null;
        } finally {
            if (rs != null) {
                rs.destroy();
            }
        }
    }

    public static Bitmap blur(Context context, Bitmap image) {
        return blur(context, image, DEFAULT_BLUR_RADIUS);
    }

    public static Bitmap screenshotSurface(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm != null ? wm.getDefaultDisplay() : null;
        if (display == null) return null;

        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        int rotation = display.getRotation();

        Rect crop = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        int scaledWidth = Math.max(1, Math.round(dm.widthPixels * BITMAP_SCALE));
        int scaledHeight = Math.max(1, Math.round(dm.heightPixels * BITMAP_SCALE));

        Bitmap screenshot = SurfaceControl.screenshot(crop, scaledWidth, scaledHeight, rotation);
        if (screenshot == null) {
            Log.w(TAG, "SurfaceControl.screenshot returned null (protected layer or display unavailable)");
            return null;
        }

        Bitmap softwareBitmap = screenshot.getConfig() == Bitmap.Config.HARDWARE
                ? screenshot.copy(Bitmap.Config.ARGB_8888, false)
                : screenshot;

        if (softwareBitmap != null) {
            softwareBitmap.prepareToDraw();
        }
        return softwareBitmap;
    }

    public static Bitmap captureAndBlur(Context context, float radius) {
        Bitmap screenshot = screenshotSurface(context);
        if (screenshot == null) return null;
        return blur(context, screenshot, radius);
    }

    public static Bitmap captureAndBlur(Context context) {
        return captureAndBlur(context, DEFAULT_BLUR_RADIUS);
    }
}
