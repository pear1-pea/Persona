package com.example.persona.features.model

import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.ModelAdmission
import com.example.persona.core.ai.ModelAdmissionReport
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.ModelPackageInstallResult
import com.example.persona.core.ai.ModelScanReport
import com.example.persona.core.ai.ModelScanResult
import com.example.persona.core.ai.PromptFormats
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.testutil.ModelPackageFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagementViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var mainDispatcher: TestDispatcher
    private val manager: LocalModelManager = mock()
    private val hybridRepository: HybridAiRepository = mock()
    private val model = InstalledModel(
        id = ModelPackageFixtures.DEFAULT_ID,
        name = "Qwen2.5 0.5B Instruct",
        version = "1.0.0",
        modelDir = File("models", ModelPackageFixtures.DEFAULT_ID).absolutePath,
        backend = Backend.MNN,
        family = ModelFamilies.QWEN2_5,
        promptFormat = PromptFormats.QWEN_CHATML_TEXT,
        entry = "llm.mnn",
        tokenizer = "tokenizer.txt",
        contextWindow = 32_768,
        minRamGb = 4,
        minSdk = 24,
        requiredAbis = listOf("arm64-v8a"),
        totalSizeBytes = 1_000L
    )
    private val readyReport = ModelScanReport(
        directoryName = ModelPackageFixtures.DEFAULT_ID,
        modelDir = model.modelDir,
        result = ModelScanResult.Ready(
            model = model,
            admission = ModelAdmissionReport(ModelAdmission.SUPPORTED)
        ),
        totalSizeBytes = model.totalSizeBytes,
        scannedAtMillis = 1_700_000_000_000L,
        manifestRaw = """{"id":"${model.id}"}"""
    )

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        whenever(manager.deviceCapability()).thenReturn(ModelPackageFixtures.deviceProfile())
        whenever(manager.modelsRoot()).thenReturn(File("models"))
        whenever(manager.currentModel).thenReturn(MutableStateFlow<InstalledModel?>(model))
        runBlocking {
            whenever(hybridRepository.refreshLocalModels()).thenReturn(listOf(readyReport))
            whenever(hybridRepository.selectLocalModel(any())).thenReturn(model)
            whenever(hybridRepository.deleteLocalModel(any())).thenReturn(true)
            whenever(manager.installModelFromTree(any())).thenReturn(
                ModelPackageInstallResult.Success(model)
            )
            whenever(hybridRepository.clearLocalModel()).thenReturn(Unit)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh exposes the installed model and device recommendation`() = runTest {
        val viewModel = newViewModel()

        viewModel.refreshModels()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, state.items.size)
        assertEquals(model.name, state.currentModelName)
        assertTrue(state.items.first().isCurrent)
        assertTrue(state.items.first().isSelectable)
        assertTrue(state.recommendationText.contains("RAM 8GB"))
    }

    @Test
    fun `select model uses the lifecycle owner and refreshes`() = runTest {
        whenever(manager.currentModel).thenReturn(MutableStateFlow<InstalledModel?>(null))
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()
        val item = viewModel.state.value.items.first()

        viewModel.selectModel(item)
        advanceUntilIdle()

        verify(hybridRepository).selectLocalModel(model.modelDir)
        assertTrue(viewModel.state.value.message.contains("已切换当前模型"))
    }

    @Test
    fun `non selectable model is rejected locally`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()
        val blocked = viewModel.state.value.items.first().copy(isSelectable = false)

        viewModel.selectModel(blocked)

        verify(hybridRepository, never()).selectLocalModel(any())
        assertTrue(viewModel.state.value.message.contains("不可用"))
    }

    @Test
    fun `selecting the current model requests an explicit retry`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()

        viewModel.selectModel(viewModel.state.value.items.first())
        advanceUntilIdle()

        verify(hybridRepository).selectLocalModel(model.modelDir)
        assertTrue(viewModel.state.value.message.contains("已重新选择模型"))
    }

    @Test
    fun `use cloud clears the current model`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()

        viewModel.useCloud()
        advanceUntilIdle()

        verify(hybridRepository).clearLocalModel()
        assertTrue(viewModel.state.value.message.contains("云端"))
    }

    @Test
    fun `release errors are shown without claiming cloud selection succeeded`() = runTest {
        whenever(hybridRepository.clearLocalModel()).thenAnswer { throw IllegalStateException("close failed") }
        val viewModel = newViewModel()

        viewModel.useCloud()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.message.contains("切换失败"))
        assertTrue(!viewModel.state.value.isLoading)
    }

    @Test
    fun `delete model uses a single lifecycle operation`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()
        val item = viewModel.state.value.items.first()

        viewModel.deleteModel(item)
        advanceUntilIdle()

        verify(hybridRepository).deleteLocalModel(item.modelDir)
        verify(manager, never()).deleteModel(any())
        assertTrue(viewModel.state.value.message.contains("已删除模型目录"))
    }

    @Test
    fun `delete failure is reported without bypassing the lifecycle owner`() = runTest {
        whenever(hybridRepository.deleteLocalModel(any())).thenReturn(false)
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()
        val item = viewModel.state.value.items.first()

        viewModel.deleteModel(item)
        advanceUntilIdle()

        verify(manager, never()).deleteModel(any())
        assertTrue(viewModel.state.value.message.contains("删除失败"))
    }

    @Test
    fun `install success is reported`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()

        viewModel.installModel(mock<Uri>())
        advanceUntilIdle()

        verify(manager).installModelFromTree(any())
        assertTrue(viewModel.state.value.message.contains("已安装模型"))
    }

    @Test
    fun `install failure is reported`() = runTest {
        whenever(manager.installModelFromTree(any())).thenReturn(
            ModelPackageInstallResult.Failure("bad package")
        )
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()

        viewModel.installModel(mock<Uri>())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.message.contains("模型安装失败"))
        assertTrue(viewModel.state.value.message.contains("bad package"))
    }

    @Test
    fun `scan failure keeps the last known items`() = runTest {
        val viewModel = newViewModel()
        viewModel.refreshModels()
        advanceUntilIdle()
        val previous = viewModel.state.value.items

        whenever(hybridRepository.refreshLocalModels()).thenAnswer { throw IllegalStateException("scan failed") }
        viewModel.refreshModels()
        advanceUntilIdle()

        assertEquals(previous, viewModel.state.value.items)
        assertTrue(viewModel.state.value.message.contains("模型扫描失败"))
    }
    private fun newViewModel(): ModelManagementViewModel {
        return ModelManagementViewModel(manager, hybridRepository)
    }

}
