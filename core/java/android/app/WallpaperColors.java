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
 * limitations under the License
 */

package android.app;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.graphics.cam.Cam;
import com.android.internal.graphics.palette.CelebiQuantizer;
import com.android.internal.graphics.palette.Palette;
import com.android.internal.graphics.palette.VariationalKMeansQuantizer;
import com.android.internal.util.ContrastColorUtil;

import java.io.FileOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provides information about the colors of a wallpaper.
 * <p>
 * Exposes the 3 most visually representative colors of a wallpaper. Can be either
 * {@link WallpaperColors#getPrimaryColor()}, {@link WallpaperColors#getSecondaryColor()}
 * or {@link WallpaperColors#getTertiaryColor()}.
 */
public final class WallpaperColors implements Parcelable {
    /**
     * @hide
     */
    @IntDef(prefix = "HINT_", value = {HINT_SUPPORTS_DARK_TEXT, HINT_SUPPORTS_DARK_THEME},
            flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorsHints {}

    private static final boolean DEBUG_DARK_PIXELS = false;

    /**
     * Specifies that dark text is preferred over the current wallpaper for best presentation.
     * <p>
     * eg. A launcher may set its text color to black if this flag is specified.
     */
    public static final int HINT_SUPPORTS_DARK_TEXT = 1 << 0;

    /**
     * Specifies that dark theme is preferred over the current wallpaper for best presentation.
     * <p>
     * eg. A launcher may set its drawer color to black if this flag is specified.
     */
    public static final int HINT_SUPPORTS_DARK_THEME = 1 << 1;

    /**
     * Specifies that this object was generated by extracting colors from a bitmap.
     * @hide
     */
    public static final int HINT_FROM_BITMAP = 1 << 2;

    // Maximum size that a bitmap can have to keep our calculations valid
    private static final int MAX_BITMAP_SIZE = 112;

    // Even though we have a maximum size, we'll mainly match bitmap sizes
    // using the area instead. This way our comparisons are aspect ratio independent.
    private static final int MAX_WALLPAPER_EXTRACTION_AREA = MAX_BITMAP_SIZE * MAX_BITMAP_SIZE;

    // When extracting the main colors, only consider colors
    // present in at least MIN_COLOR_OCCURRENCE of the image
    private static final float MIN_COLOR_OCCURRENCE = 0.05f;

    // Decides when dark theme is optimal for this wallpaper
    private static final float DARK_THEME_MEAN_LUMINANCE = 30.0f;
    // Minimum mean luminosity that an image needs to have to support dark text
    private static final float BRIGHT_IMAGE_MEAN_LUMINANCE = 70.0f;
    // We also check if the image has dark pixels in it,
    // to avoid bright images with some dark spots.
    private static final float DARK_PIXEL_CONTRAST = 5.5f;
    private static final float MAX_DARK_AREA = 0.05f;

    private final List<Color> mMainColors;
    private final Map<Integer, Integer> mAllColors;
    private int mColorHints;

    public WallpaperColors(Parcel parcel) {
        mMainColors = new ArrayList<>();
        mAllColors = new HashMap<>();
        int count = parcel.readInt();
        for (int i = 0; i < count; i++) {
            final int colorInt = parcel.readInt();
            Color color = Color.valueOf(colorInt);
            mMainColors.add(color);
        }
        count = parcel.readInt();
        for (int i = 0; i < count; i++) {
            final int colorInt = parcel.readInt();
            final int population = parcel.readInt();
            mAllColors.put(colorInt, population);
        }
        mColorHints = parcel.readInt();
    }

    /**
     * Constructs {@link WallpaperColors} from a drawable.
     * <p>
     * Main colors will be extracted from the drawable.
     *
     * @param drawable Source where to extract from.
     */
    public static WallpaperColors fromDrawable(Drawable drawable) {
        if (drawable == null) {
            throw new IllegalArgumentException("Drawable cannot be null");
        }

        Rect initialBounds = drawable.copyBounds();
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        // Some drawables do not have intrinsic dimensions
        if (width <= 0 || height <= 0) {
            width = MAX_BITMAP_SIZE;
            height = MAX_BITMAP_SIZE;
        }

        Size optimalSize = calculateOptimalSize(width, height);
        Bitmap bitmap = Bitmap.createBitmap(optimalSize.getWidth(), optimalSize.getHeight(),
                Bitmap.Config.ARGB_8888);
        final Canvas bmpCanvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawable.draw(bmpCanvas);

        final WallpaperColors colors = WallpaperColors.fromBitmap(bitmap);
        bitmap.recycle();

        drawable.setBounds(initialBounds);
        return colors;
    }

    /**
     * Constructs {@link WallpaperColors} from a bitmap.
     * <p>
     * Main colors will be extracted from the bitmap.
     *
     * @param bitmap Source where to extract from.
     */
    public static WallpaperColors fromBitmap(@NonNull Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap can't be null");
        }
        return fromBitmap(bitmap, 0f /* dimAmount */);
    }

    /**
     * Constructs {@link WallpaperColors} from a bitmap with dimming applied.
     * <p>
     * Main colors will be extracted from the bitmap with dimming taken into account when
     * calculating dark hints.
     *
     * @param bitmap Source where to extract from.
     * @param dimAmount Wallpaper dim amount
     * @hide
     */
    public static WallpaperColors fromBitmap(@NonNull Bitmap bitmap,
            @FloatRange (from = 0f, to = 1f) float dimAmount) {
        Objects.requireNonNull(bitmap, "Bitmap can't be null");

        final int bitmapArea = bitmap.getWidth() * bitmap.getHeight();
        boolean shouldRecycle = false;
        if (bitmapArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            shouldRecycle = true;
            Size optimalSize = calculateOptimalSize(bitmap.getWidth(), bitmap.getHeight());
            bitmap = Bitmap.createScaledBitmap(bitmap, optimalSize.getWidth(),
                    optimalSize.getHeight(), false /* filter */);
        }

        final Palette palette;
        if (ActivityManager.isLowRamDeviceStatic()) {
            palette = Palette
                    .from(bitmap, new VariationalKMeansQuantizer())
                    .maximumColorCount(5)
                    .resizeBitmapArea(MAX_WALLPAPER_EXTRACTION_AREA)
                    .generate();
        } else {
            palette = Palette
                    .from(bitmap, new CelebiQuantizer())
                    .maximumColorCount(128)
                    .resizeBitmapArea(MAX_WALLPAPER_EXTRACTION_AREA)
                    .generate();
        }
        // Remove insignificant colors and sort swatches by population
        final ArrayList<Palette.Swatch> swatches = new ArrayList<>(palette.getSwatches());
        swatches.sort((a, b) -> b.getPopulation() - a.getPopulation());

        final int swatchesSize = swatches.size();

        final Map<Integer, Integer> populationByColor = new HashMap<>();
        for (int i = 0; i < swatchesSize; i++) {
            Palette.Swatch swatch = swatches.get(i);
            int colorInt = swatch.getInt();
            populationByColor.put(colorInt, swatch.getPopulation());

        }

        int hints = calculateDarkHints(bitmap, dimAmount);

        if (shouldRecycle) {
            bitmap.recycle();
        }

        return new WallpaperColors(populationByColor, HINT_FROM_BITMAP | hints);
    }

    /**
     * Constructs a new object from three colors.
     *
     * @param primaryColor Primary color.
     * @param secondaryColor Secondary color.
     * @param tertiaryColor Tertiary color.
     * @see WallpaperColors#fromBitmap(Bitmap)
     * @see WallpaperColors#fromDrawable(Drawable)
     */
    public WallpaperColors(@NonNull Color primaryColor, @Nullable Color secondaryColor,
            @Nullable Color tertiaryColor) {
        this(primaryColor, secondaryColor, tertiaryColor, 0);

        // Calculate dark theme support based on primary color.
        final double[] tmpLab = new double[3];
        ColorUtils.colorToLAB(primaryColor.toArgb(), tmpLab);
        final double luminance = tmpLab[0];
        if (luminance < DARK_THEME_MEAN_LUMINANCE) {
            mColorHints |= HINT_SUPPORTS_DARK_THEME;
        }
    }

    /**
     * Constructs a new object from three colors, where hints can be specified.
     *
     * @param primaryColor Primary color.
     * @param secondaryColor Secondary color.
     * @param tertiaryColor Tertiary color.
     * @param colorHints A combination of color hints.
     * @see WallpaperColors#fromBitmap(Bitmap)
     * @see WallpaperColors#fromDrawable(Drawable)
     */
    public WallpaperColors(@NonNull Color primaryColor, @Nullable Color secondaryColor,
            @Nullable Color tertiaryColor, @ColorsHints int colorHints) {

        if (primaryColor == null) {
            throw new IllegalArgumentException("Primary color should never be null.");
        }

        mMainColors = new ArrayList<>(3);
        mAllColors = new HashMap<>();

        mMainColors.add(primaryColor);
        mAllColors.put(primaryColor.toArgb(), 0);
        if (secondaryColor != null) {
            mMainColors.add(secondaryColor);
            mAllColors.put(secondaryColor.toArgb(), 0);
        }
        if (tertiaryColor != null) {
            if (secondaryColor == null) {
                throw new IllegalArgumentException("tertiaryColor can't be specified when "
                        + "secondaryColor is null");
            }
            mMainColors.add(tertiaryColor);
            mAllColors.put(tertiaryColor.toArgb(), 0);
        }
        mColorHints = colorHints;
    }

    /**
     * Constructs a new object from a set of colors, where hints can be specified.
     *
     * @param colorToPopulation Map with keys of colors, and value representing the number of
     *                          occurrences of color in the wallpaper.
     * @param colorHints        A combination of color hints.
     * @hide
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @see WallpaperColors#fromBitmap(Bitmap)
     * @see WallpaperColors#fromDrawable(Drawable)
     */
    public WallpaperColors(@NonNull Map<Integer, Integer> colorToPopulation,
            @ColorsHints int colorHints) {
        mAllColors = colorToPopulation;

        final Map<Integer, Cam> colorToCam = new HashMap<>();
        for (int color : colorToPopulation.keySet()) {
            colorToCam.put(color, Cam.fromInt(color));
        }
        final double[] hueProportions = hueProportions(colorToCam, colorToPopulation);
        final Map<Integer, Double> colorToHueProportion = colorToHueProportion(
                colorToPopulation.keySet(), colorToCam, hueProportions);

        final Map<Integer, Double> colorToScore = new HashMap<>();
        for (Map.Entry<Integer, Double> mapEntry : colorToHueProportion.entrySet()) {
            int color = mapEntry.getKey();
            double proportion = mapEntry.getValue();
            double score = score(colorToCam.get(color), proportion);
            colorToScore.put(color, score);
        }
        ArrayList<Map.Entry<Integer, Double>> mapEntries = new ArrayList(colorToScore.entrySet());
        mapEntries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        List<Integer> colorsByScoreDescending = new ArrayList<>();
        for (Map.Entry<Integer, Double> colorToScoreEntry : mapEntries) {
            colorsByScoreDescending.add(colorToScoreEntry.getKey());
        }

        List<Integer> mainColorInts = new ArrayList<>();
        findSeedColorLoop:
        for (int color : colorsByScoreDescending) {
            Cam cam = colorToCam.get(color);
            for (int otherColor : mainColorInts) {
                Cam otherCam = colorToCam.get(otherColor);
                if (hueDiff(cam, otherCam) < 15) {
                    continue findSeedColorLoop;
                }
            }
            mainColorInts.add(color);
        }
        List<Color> mainColors = new ArrayList<>();
        for (int colorInt : mainColorInts) {
            mainColors.add(Color.valueOf(colorInt));
        }
        mMainColors = mainColors;
        mColorHints = colorHints;
    }

    private static double hueDiff(Cam a, Cam b) {
        return (180f - Math.abs(Math.abs(a.getHue() - b.getHue()) - 180f));
    }

    private static double score(Cam cam, double proportion) {
        return cam.getChroma() + (proportion * 100);
    }

    private static Map<Integer, Double> colorToHueProportion(Set<Integer> colors,
            Map<Integer, Cam> colorToCam, double[] hueProportions) {
        Map<Integer, Double> colorToHueProportion = new HashMap<>();
        for (int color : colors) {
            final int hue = wrapDegrees(Math.round(colorToCam.get(color).getHue()));
            double proportion = 0.0;
            for (int i = hue - 15; i < hue + 15; i++) {
                proportion += hueProportions[wrapDegrees(i)];
            }
            colorToHueProportion.put(color, proportion);
        }
        return colorToHueProportion;
    }

    private static int wrapDegrees(int degrees) {
        if (degrees < 0) {
            return (degrees % 360) + 360;
        } else if (degrees >= 360) {
            return degrees % 360;
        } else {
            return degrees;
        }
    }

    private static double[] hueProportions(@NonNull Map<Integer, Cam> colorToCam,
            Map<Integer, Integer> colorToPopulation) {
        final double[] proportions = new double[360];

        double totalPopulation = 0;
        for (Map.Entry<Integer, Integer> entry : colorToPopulation.entrySet()) {
            totalPopulation += entry.getValue();
        }

        for (Map.Entry<Integer, Integer> entry : colorToPopulation.entrySet()) {
            final int color = (int) entry.getKey();
            final int population = colorToPopulation.get(color);
            final Cam cam = colorToCam.get(color);
            final int hue = wrapDegrees(Math.round(cam.getHue()));
            proportions[hue] = proportions[hue] + ((double) population / totalPopulation);
        }

        return proportions;
    }

    public static final @android.annotation.NonNull Creator<WallpaperColors> CREATOR = new Creator<WallpaperColors>() {
        @Override
        public WallpaperColors createFromParcel(Parcel in) {
            return new WallpaperColors(in);
        }

        @Override
        public WallpaperColors[] newArray(int size) {
            return new WallpaperColors[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        List<Color> mainColors = getMainColors();
        int count = mainColors.size();
        dest.writeInt(count);
        for (int i = 0; i < count; i++) {
            Color color = mainColors.get(i);
            dest.writeInt(color.toArgb());
        }
        count = mAllColors.size();
        dest.writeInt(count);
        for (Map.Entry<Integer, Integer> colorEntry : mAllColors.entrySet()) {
            if (colorEntry.getKey() != null) {
                dest.writeInt(colorEntry.getKey());
                Integer population = colorEntry.getValue();
                int populationInt = (population != null) ? population : 0;
                dest.writeInt(populationInt);
            }
        }
        dest.writeInt(mColorHints);
    }

    /**
     * Gets the most visually representative color of the wallpaper.
     * "Visually representative" means easily noticeable in the image,
     * probably happening at high frequency.
     *
     * @return A color.
     */
    public @NonNull Color getPrimaryColor() {
        return mMainColors.get(0);
    }

    /**
     * Gets the second most preeminent color of the wallpaper. Can be null.
     *
     * @return A color, may be null.
     */
    public @Nullable Color getSecondaryColor() {
        return mMainColors.size() < 2 ? null : mMainColors.get(1);
    }

    /**
     * Gets the third most preeminent color of the wallpaper. Can be null.
     *
     * @return A color, may be null.
     */
    public @Nullable Color getTertiaryColor() {
        return mMainColors.size() < 3 ? null : mMainColors.get(2);
    }

    /**
     * List of most preeminent colors, sorted by importance.
     *
     * @return List of colors.
     * @hide
     */
    public @NonNull List<Color> getMainColors() {
        return Collections.unmodifiableList(mMainColors);
    }

    /**
     * Map of all colors. Key is rgb integer, value is importance of color.
     *
     * @return List of colors.
     * @hide
     */
    public @NonNull Map<Integer, Integer> getAllColors() {
        return Collections.unmodifiableMap(mAllColors);
    }


    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WallpaperColors other = (WallpaperColors) o;
        return mMainColors.equals(other.mMainColors)
                && mAllColors.equals(other.mAllColors)
                && mColorHints == other.mColorHints;
    }

    @Override
    public int hashCode() {
        return (31 * mMainColors.hashCode() * mAllColors.hashCode()) + mColorHints;
    }

    /**
     * Returns the color hints for this instance.
     * @return The color hints.
     */
    public @ColorsHints int getColorHints() {
        return mColorHints;
    }

    /**
     * Checks if image is bright and clean enough to support light text.
     *
     * @param source What to read.
     * @param dimAmount How much wallpaper dim amount was applied.
     * @return Whether image supports dark text or not.
     */
    private static int calculateDarkHints(Bitmap source, float dimAmount) {
        if (source == null) {
            return 0;
        }

        dimAmount = MathUtils.saturate(dimAmount);
        int[] pixels = new int[source.getWidth() * source.getHeight()];
        double totalLuminance = 0;
        final int maxDarkPixels = (int) (pixels.length * MAX_DARK_AREA);
        int darkPixels = 0;
        source.getPixels(pixels, 0 /* offset */, source.getWidth(), 0 /* x */, 0 /* y */,
                source.getWidth(), source.getHeight());

        // Create a new black layer with dimAmount as the alpha to be accounted for when computing
        // the luminance.
        int dimmingLayerAlpha = (int) (255 * dimAmount);
        int blackTransparent = ColorUtils.setAlphaComponent(Color.BLACK, dimmingLayerAlpha);

        // This bitmap was already resized to fit the maximum allowed area.
        // Let's just loop through the pixels, no sweat!
        double[] tmpLab = new double[3];
        for (int i = 0; i < pixels.length; i++) {
            int pixelColor = pixels[i];
            ColorUtils.colorToLAB(pixelColor, tmpLab);
            final int alpha = Color.alpha(pixelColor);

            // Apply composite colors where the foreground is a black layer with an alpha value of
            // the dim amount and the background is the wallpaper pixel color.
            int compositeColors = ColorUtils.compositeColors(blackTransparent, pixelColor);

            // Calculate the adjusted luminance of the dimmed wallpaper pixel color.
            double adjustedLuminance = ColorUtils.calculateLuminance(compositeColors);

            // Make sure we don't have a dark pixel mass that will
            // make text illegible.
            final boolean satisfiesTextContrast = ContrastColorUtil
                    .calculateContrast(pixelColor, Color.BLACK) > DARK_PIXEL_CONTRAST;
            if (!satisfiesTextContrast && alpha != 0) {
                darkPixels++;
                if (DEBUG_DARK_PIXELS) {
                    pixels[i] = Color.RED;
                }
            }
            totalLuminance += adjustedLuminance;
        }

        int hints = 0;
        double meanLuminance = totalLuminance / pixels.length;
        if (meanLuminance > BRIGHT_IMAGE_MEAN_LUMINANCE && darkPixels < maxDarkPixels) {
            hints |= HINT_SUPPORTS_DARK_TEXT;
        }
        if (meanLuminance < DARK_THEME_MEAN_LUMINANCE) {
            hints |= HINT_SUPPORTS_DARK_THEME;
        }

        if (DEBUG_DARK_PIXELS) {
            try (FileOutputStream out = new FileOutputStream("/data/pixels.png")) {
                source.setPixels(pixels, 0, source.getWidth(), 0, 0, source.getWidth(),
                        source.getHeight());
                source.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.d("WallpaperColors", "l: " + meanLuminance + ", d: " + darkPixels +
                    " maxD: " + maxDarkPixels + " numPixels: " + pixels.length);
        }

        return hints;
    }

    private static Size calculateOptimalSize(int width, int height) {
        // Calculate how big the bitmap needs to be.
        // This avoids unnecessary processing and allocation inside Palette.
        final int requestedArea = width * height;
        double scale = 1;
        if (requestedArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            scale = Math.sqrt(MAX_WALLPAPER_EXTRACTION_AREA / (double) requestedArea);
        }
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);
        // Dealing with edge cases of the drawable being too wide or too tall.
        // Width or height would end up being 0, in this case we'll set it to 1.
        if (newWidth == 0) {
            newWidth = 1;
        }
        if (newHeight == 0) {
            newHeight = 1;
        }

        return new Size(newWidth, newHeight);
    }

    @Override
    public String toString() {
        final StringBuilder colors = new StringBuilder();
        for (int i = 0; i < mMainColors.size(); i++) {
            colors.append(Integer.toHexString(mMainColors.get(i).toArgb())).append(" ");
        }
        return "[WallpaperColors: " + colors.toString() + "h: " + mColorHints + "]";
    }
}