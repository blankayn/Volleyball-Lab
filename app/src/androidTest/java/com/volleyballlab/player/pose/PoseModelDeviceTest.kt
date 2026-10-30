package com.volleyballlab.player.pose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import android.content.res.AssetManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PoseModelDeviceTest {
    @Test fun inaccessibleAssetsAreNotMisreportedAsAMissingModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inaccessible = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw SecurityException("fixture asset access failure")
        }
        try {
            MediaPipePoseDetector.create(inaccessible)
            fail("Detector must report the asset access failure")
        } catch (failure: PoseDetectorUnavailableException) {
            assertEquals(PoseDetectorUnavailableException.Reason.InitialisationFailed, failure.reason)
        }
    }

    @Test fun genuinelyMissingModelKeepsItsSpecificReason() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            MediaPipePoseDetector.create(context, "missing-model-for-test.task")
            fail("Missing model must be reported")
        } catch (failure: PoseDetectorUnavailableException) {
            assertEquals(PoseDetectorUnavailableException.Reason.ModelMissing, failure.reason)
        }
    }

    @Test fun packagedModelIsUncompressedAndProductionDetectorCanInitialize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.assets.openFd(MediaPipePoseDetector.DEFAULT_MODEL_ASSET).use { assertTrue(it.length > 9_000_000) }
        // CaptureViewModel creates the production detector on the main thread.
        val task = FutureTask<Unit> { MediaPipePoseDetector.create(context).close() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        task.get(60, TimeUnit.SECONDS)
    }
}
