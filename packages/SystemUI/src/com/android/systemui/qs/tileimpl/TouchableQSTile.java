package com.android.systemui.qs.tileimpl;

import android.view.View;

// For use with SliderQSTileViewImpl
public interface TouchableQSTile {

    public boolean isDeviceSupported();

    public View.OnTouchListener getTouchListener();

    public String getSettingsSystemKey();
}
