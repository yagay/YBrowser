package com.yagay.ybrowser.sample;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Discovery-only. The Loader never starts/binds this service. */
public final class FeatureDescriptorService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
