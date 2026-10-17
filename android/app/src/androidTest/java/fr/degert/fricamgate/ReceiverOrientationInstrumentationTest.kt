package com.nvr.camnory

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverOrientationInstrumentationTest {
    @Test fun receiverManifestDoesNotForcePhonesIntoLandscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = context.packageManager.getActivityInfo(ComponentName(context, TvActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.screenOrientation)
    }
}
