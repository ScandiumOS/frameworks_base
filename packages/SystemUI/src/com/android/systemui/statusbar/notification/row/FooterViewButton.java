/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.AlphaOptimizedButton;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.tuner.TunerService;

public class FooterViewButton extends AlphaOptimizedButton {

    private static final String BLUR_STYLE =
        "system:" + Settings.System.BLUR_STYLE_PREFERENCE_KEY;
    private boolean mBlurStyleEnable;

    public FooterViewButton(Context context) {
        this(context, null);
    }

    public FooterViewButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable((key, newValue) -> {
            if (key.equals(BLUR_STYLE)) {
            	mBlurStyleEnable = TunerService.parseIntegerSwitch(newValue, false);
                updateBackground();
            }
        },  BLUR_STYLE);
    }

    public void updateBackground() {
    	Drawable bg = getBackground();
    	int alphaBlur = ActivatableNotificationView.mIsBlurCombinedEnabled ? 100 : 153;
    	if (bg != null) bg.setAlpha(mBlurStyleEnable ? alphaBlur : 255);
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. The standard implementation doesn't
     * account for translation.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = ((ViewGroup) mParent).getTranslationX();
        float translationY = ((ViewGroup) mParent).getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }
}
