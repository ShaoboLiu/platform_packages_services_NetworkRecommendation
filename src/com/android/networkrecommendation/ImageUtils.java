/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkrecommendation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Helper for image manipulation */
public class ImageUtils {

    /**
     * Convert a drawable to a bitmap, scaled to fit within maxWidth and maxHeight.
     */
    public static Bitmap buildScaledBitmap(Drawable drawable, int maxWidth,
            int maxHeight) {
        if (drawable == null) {
            return null;
        }
        int originalWidth = drawable.getIntrinsicWidth();
        int originalHeight = drawable.getIntrinsicHeight();

        if ((originalWidth <= maxWidth) && (originalHeight <= maxHeight)
                && (drawable instanceof BitmapDrawable)) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        if (originalHeight <= 0 || originalWidth <= 0) {
            return null;
        }

        // create a new bitmap, scaling down to fit the max dimensions of
        // a large notification icon if necessary
        float ratio = Math.min((float) maxWidth / (float) originalWidth,
                (float) maxHeight / (float) originalHeight);
        ratio = Math.min(1.0f, ratio);
        int scaledWidth = (int) (ratio * originalWidth);
        int scaledHeight = (int) (ratio * originalHeight);
        Bitmap result = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);

        // and paint our app bitmap on it
        Canvas canvas = new Canvas(result);
        drawable.setBounds(0, 0, scaledWidth, scaledHeight);
        drawable.draw(canvas);

        return result;
    }
}
