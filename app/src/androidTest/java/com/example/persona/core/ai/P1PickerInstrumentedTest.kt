package com.example.persona.core.ai

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.features.model.ModelManagementActivity
import com.example.persona.R
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P1PickerInstrumentedTest {
    @Test(timeout = 900_000L)
    fun userFacingDirectoryPickerInstallsARealPackage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runP1Picker") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(context, validator, installer)
        manager.clearCurrentModel()
        manager.refreshInstalledModels().forEach { assertTrue(manager.deleteModel(it.modelDir)) }
        val expected = "qwen2.5-0.5b-instruct-mnn-int4"
        val ready = File(manager.modelsRoot(), "$expected/READY")
        val monitor = instrumentation.addMonitor(ModelManagementActivity::class.java.name, null, false)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, ModelManagementActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) { "Model manager did not open" }
        instrumentation.removeMonitor(monitor)
        withTimeout(30_000L) {
            var opened = false
            while (!opened) {
                instrumentation.runOnMainSync {
                    val button = activity.findViewById<View>(R.id.btnInstallModel)
                    if (button.isEnabled) opened = button.performClick()
                }
                if (!opened) delay(100)
            }
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "P1_PICKER_READY: select QA model directory through Install Model\n") })
        withTimeout(840_000L) { while (!ready.isFile) delay(500) }
        val report = validator.validate(ready.parentFile!!, manager.deviceCapability())
        assertTrue("Picker must publish a valid package", report.result is ModelScanResult.Ready)
        suspend fun waitForStatus(prefix: String) = withTimeout(30_000L) {
            var matched = false
            while (!matched) {
                instrumentation.runOnMainSync {
                    matched = activity.findViewById<TextView>(R.id.tvStatus).text.toString().startsWith(prefix)
                }
                if (!matched) delay(100)
            }
        }
        waitForStatus("已安装模型")
        instrumentation.runOnMainSync {
            val row = activity.findViewById<RecyclerView>(R.id.rvModels).findViewHolderForAdapterPosition(0)
            assertTrue(checkNotNull(row).itemView.findViewById<View>(R.id.btnSelect).performClick())
        }
        waitForStatus("已切换当前模型")
        instrumentation.runOnMainSync {
            val row = activity.findViewById<RecyclerView>(R.id.rvModels).findViewHolderForAdapterPosition(0)
            val button = checkNotNull(row).itemView.findViewById<TextView>(R.id.btnSelect)
            assertTrue(button.isEnabled && button.text.toString() == "重新选择")
            assertTrue(button.performClick())
        }
        waitForStatus("已重新选择模型")
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(context.filesDir, "p1-picker-ui.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "P1_PICKER_INSTALLED: $expected\n") })
    }
}
