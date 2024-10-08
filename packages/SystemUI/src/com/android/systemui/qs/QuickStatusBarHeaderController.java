/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.Bundle;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.VariableDateViewController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import android.provider.Settings;

import java.util.List;

import javax.inject.Inject;

/**
 * Controller for {@link QuickStatusBarHeader}.
 */
@QSScope
class QuickStatusBarHeaderController extends ViewController<QuickStatusBarHeader> implements
        ChipVisibilityListener, TunerService.Tunable {

    private final QuickQSPanelController mQuickQSPanelController;
    private final StatusBarIconController mStatusBarIconController;
    private final StatusIconContainer mIconContainer;
    private final StatusBarIconController.TintedIconManager mIconManager;
    private final QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    private final FeatureFlags mFeatureFlags;
    private final BatteryMeterViewController mBatteryMeterViewController;
    private final StatusBarContentInsetsProvider mInsetsProvider;
    private final HeaderPrivacyIconsController mPrivacyIconsController;

    private boolean mListening;

    private static final String QS_TILE_TINT =
        "system:" + Settings.System.QS_TILE_TINT;

    private static final String BLUR_STYLE =
        "system:" + Settings.System.BLUR_STYLE_PREFERENCE_KEY;

    private static final String COMBINED_BLUR =
        "system:" + Settings.System.COMBINED_BLUR;

    @Inject
    QuickStatusBarHeaderController(QuickStatusBarHeader view,
            HeaderPrivacyIconsController headerPrivacyIconsController,
            StatusBarIconController statusBarIconController,
            DemoModeController demoModeController,
            QuickQSPanelController quickQSPanelController,
            QSExpansionPathInterpolator qsExpansionPathInterpolator,
            FeatureFlags featureFlags,
            VariableDateViewController.Factory variableDateViewControllerFactory,
            BatteryMeterViewController batteryMeterViewController,
            StatusBarContentInsetsProvider statusBarContentInsetsProvider,
            StatusBarIconController.TintedIconManager.Factory tintedIconManagerFactory) {
        super(view);
        mPrivacyIconsController = headerPrivacyIconsController;
        mStatusBarIconController = statusBarIconController;
        mQuickQSPanelController = quickQSPanelController;
        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        mFeatureFlags = featureFlags;
        mBatteryMeterViewController = batteryMeterViewController;
        mInsetsProvider = statusBarContentInsetsProvider;

        mIconContainer = mView.findViewById(R.id.statusIcons);

        mIconManager = tintedIconManagerFactory.create(mIconContainer, StatusBarLocation.QS);
    }

    @Override
    protected void onInit() {
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mPrivacyIconsController.onParentVisible();
        mPrivacyIconsController.setChipVisibilityListener(this);
        mIconContainer.addIgnoredSlot(
                getResources().getString(com.android.internal.R.string.status_bar_managed_profile));
        mIconContainer.addIgnoredSlot(
                getResources().getString(com.android.internal.R.string.status_bar_alarm_clock));
        mIconContainer.setShouldRestrictIcons(false);
        mStatusBarIconController.addIconGroup(mIconManager);
        Dependency.get(TunerService.class).addTunable(this, QS_TILE_TINT);
        Dependency.get(TunerService.class).addTunable(this, BLUR_STYLE);
        Dependency.get(TunerService.class).addTunable(this, COMBINED_BLUR);

        List<String> rssiIgnoredSlots = List.of(
                getResources().getString(com.android.internal.R.string.status_bar_mobile)
        );

        mView.onAttach(mIconManager, mQSExpansionPathInterpolator, rssiIgnoredSlots,
                mInsetsProvider, mFeatureFlags.isEnabled(Flags.COMBINED_QS_HEADERS));
    }

    @Override
    protected void onViewDetached() {
        mPrivacyIconsController.onParentInvisible();
        mStatusBarIconController.removeIconGroup(mIconManager);
        setListening(false);
        Dependency.get(TunerService.class).removeTunable(this);
    }

    public void setListening(boolean listening) {

        if (listening == mListening) {
            return;
        }
        mListening = listening;

        mQuickQSPanelController.setListening(listening);

        if (mQuickQSPanelController.switchTileLayout(false)) {
            mView.updateResources();
        }

        if (listening) {
            mPrivacyIconsController.startListening();
        } else {
            mPrivacyIconsController.stopListening();
        }
    }

	@Override
	public void onTuningChanged(String key, String newValue) {
		switch (key) {
			case QS_TILE_TINT:
				mView.updateColors(TunerService.parseIntegerSwitch(newValue, false));
				break;
			case BLUR_STYLE:
				mView.updateAlpha(TunerService.parseIntegerSwitch(newValue, false));
				break;
			case COMBINED_BLUR:
				mView.updateIsCombined(TunerService.parseIntegerSwitch(newValue, false));
				break;
		}
	}

    @Override
    public void onChipVisibilityRefreshed(boolean visible) {
        mView.setChipVisibility(visible);
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mQuickQSPanelController.setContentMargins(marginStart, marginEnd);
    }
}
