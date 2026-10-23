package com.openlistsorter.app;

import android.content.Context;

import com.openlistsorter.app.data.ConnectionStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class MainActivityLaunchTest {
    @Before
    public void disableAutoLogin() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectionStore.disableAutoLogin(context);
    }

    @Test
    public void connectionScreenLaunchesWithMaterialTheme() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            assertNotNull(activity.findViewById(android.R.id.content));
            assertFalse(activity.isFinishing());
        }
    }
}
