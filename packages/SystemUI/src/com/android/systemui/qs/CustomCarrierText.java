/*
 * Copyright (C) 2022 The Nusantara Project
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

package com.android.systemui.qs;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.SingleLineTransformationMethod;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;

import java.util.Locale;

public class CustomCarrierText extends TextView {
    private final boolean mShowMissingSim;

    private final boolean mShowAirplaneMode;
    
    private ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);

    public CustomCarrierText(Context context) {
        this(context, null);
    }

    public CustomCarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        View.OnClickListener onClickListener = v -> {
            if (!v.isVisibleToUser()) {
                return;
            }
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS), 0);
        };
        setOnClickListener(onClickListener);
        boolean useAllCaps;
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CarrierText, 0, 0);
        try {
            useAllCaps = a.getBoolean(R.styleable.CarrierText_allCaps, false);
            mShowAirplaneMode = a.getBoolean(R.styleable.CarrierText_showAirplaneMode, false);
            mShowMissingSim = a.getBoolean(R.styleable.CarrierText_showMissingSim, false);
        } finally {
            a.recycle();
        }
        setTransformationMethod(new CustomCarrierTextTransformationMethod(mContext, useAllCaps));
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Only show marquee when visible
        if (visibility == VISIBLE) {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        } else {
            setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    public boolean getShowAirplaneMode() {
        return mShowAirplaneMode;
    }

    public boolean getShowMissingSim() {
        return mShowMissingSim;
    }

    private static class CustomCarrierTextTransformationMethod extends SingleLineTransformationMethod {
        private final Locale mLocale;
        private final boolean mAllCaps;

        public CustomCarrierTextTransformationMethod(Context context, boolean allCaps) {
            mLocale = context.getResources().getConfiguration().locale;
            mAllCaps = allCaps;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            source = super.getTransformation(source, view);

            if (mAllCaps && source != null) {
                source = source.toString().toUpperCase(mLocale);
            }

            return source;
        }
    }
}
