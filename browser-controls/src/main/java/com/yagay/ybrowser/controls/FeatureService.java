package com.yagay.ybrowser.controls;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Declarative feature registration; never started by the Loader. */
public final class FeatureService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
