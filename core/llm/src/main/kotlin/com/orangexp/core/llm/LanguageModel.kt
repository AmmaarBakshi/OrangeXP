package com.orangexp.core.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import com.orangexp.core.common.coroutines.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

sealed interface ModelState {
    data object NotInstalled : ModelState

    /** Copying a model file into the app; [fraction] is `null` when the size is unknown. */
    data class Importing(val fraction: Float?) : ModelState

    /** On the device, not in memory. */
    data class Installed(val model: ModelFile) : ModelState

    data class Loading(val model: ModelFile) : ModelState

    data class Ready(val model: ModelFile, val gpu: Boolean) : ModelState

    data class Failed(val model: ModelFile?, val message: String) : ModelState
}

/**
 * A language model that runs entirely on the phone (MediaPipe LLM Inference),
 * e.g. Gemma 3n E2B or Gemma 2 2B. Nothing is downloaded: you import a model
 * file once and it never leaves the device.
 *
 * The model takes memory comparable to its file size, so it is loaded on first
 * use and released again after a few idle minutes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LanguageModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ModelState>(installed()?.let { ModelState.Installed(it) } ?: ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Model work is serialized: one load or answer at a time. */
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private var engine: LlmInference? = null
    private var engineGpu = false
    private var session: LlmInferenceSession? = null
    private var idleRelease: Job? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** Where imported models live: private storage, excluded from backups. */
    private val importDir get() = File(context.noBackupFilesDir, MODEL_DIR)

    /** Also accepted: a model pushed with adb to `Android/data/<package>/files/models`. */
    private val sideloadDir get() = context.getExternalFilesDir(MODEL_DIR)

    fun installed(): ModelFile? = listOfNotNull(importDir, sideloadDir)
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
        .maxByOrNull { it.lastModified() }
        ?.let { ModelFile(it.name, it.absolutePath, it.length()) }

    /** Copies a model file chosen by the user into private storage, replacing any earlier one. */
    suspend fun import(uri: Uri): Result<ModelFile> = withContext(Dispatchers.IO) {
        lock.withLock {
            releaseLocked()
            runCatching {
                val (name, size) = describe(uri)
                require(name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                    "Choose a .task model file (for example gemma-3n-E2B-it-int4.task)"
                }
                val dir = importDir.apply { mkdirs() }
                val partial = File(dir, "$name.partial")
                _state.value = ModelState.Importing(if (size > 0) 0f else null)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open the file" }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (size > 0 && copied - lastReport > REPORT_EVERY_BYTES) {
                                lastReport = copied
                                _state.value = ModelState.Importing((copied.toFloat() / size).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                dir.listFiles()?.filter { it != partial }?.forEach { it.delete() }
                val target = File(dir, name)
                check(partial.renameTo(target)) { "Could not store the model" }
                ModelFile(target.name, target.absolutePath, target.length())
            }.onSuccess { _state.value = ModelState.Installed(it) }
                .onFailure {
                    importDir.listFiles()?.filter { f -> f.name.endsWith(".partial") }?.forEach { f -> f.delete() }
                    _state.value = ModelState.Failed(installed(), it.message ?: "Import failed")
                }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        lock.withLock {
            releaseLocked()
            importDir.listFiles()?.forEach { it.delete() }
            sideloadDir?.listFiles()?.forEach { it.delete() }
            _state.value = installed()?.let { ModelState.Installed(it) } ?: ModelState.NotInstalled
        }
    }

    /**
     * Streams the answer to [prompt] as it is generated (each element is new text).
     * Loads the model first if needed. Cancelling collection stops generation.
     */
    fun generate(prompt: ChatPrompt, useGpu: Boolean, maxTokens: Int = DEFAULT_MAX_TOKENS): Flow<String> = flow {
        val model = installed() ?: error("No language model installed")
        val template = ChatTemplate.forModelFile(model.name)
        lock.withLock {
            idleRelease?.cancel()
            val llm = loadLocked(model, useGpu, maxTokens)
            val text = fitPrompt(llm, template, prompt, maxTokens)
            val stops = template.stopMarkers
            var emitted = 0
            val produced = StringBuilder()
            try {
                stream(llm, text).collect { delta ->
                    produced.append(delta)
                    // Hold back text that might be the start of a stop marker.
                    val stopAt = stops.map { produced.indexOf(it) }.filter { it >= 0 }.minOrNull()
                    val safeEnd = stopAt ?: (produced.length - stops.maxOf { it.length }).coerceAtLeast(emitted)
                    if (safeEnd > emitted) {
                        emit(produced.substring(emitted, safeEnd))
                        emitted = safeEnd
                    }
                    if (stopAt != null) throw StopReached()
                }
                if (produced.length > emitted) emit(produced.substring(emitted))
            } catch (_: StopReached) {
                session?.cancelGenerateResponseAsync()
            } finally {
                scheduleRelease()
            }
        }
    }.flowOn(engineDispatcher)

    private fun stream(llm: LlmInference, text: String): Flow<String> = callbackFlow {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            // Templates are applied by ChatTemplate; the runtime must not add its own.
            .setPromptTemplates(
                PromptTemplates.builder()
                    .setUserPrefix("").setUserSuffix("")
                    .setModelPrefix("").setModelSuffix("")
                    .setSystemPrefix("").setSystemSuffix("")
                    .build(),
            )
            .build()
        val current = LlmInferenceSession.createFromOptions(llm, options)
        session = current
        current.addQueryChunk(text)
        val future = current.generateResponseAsync { partial, done ->
            if (!partial.isNullOrEmpty()) trySend(partial)
            if (done) close()
        }
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                close(e)
            }
        }, callbackExecutor)
        awaitClose {
            if (!future.isDone) runCatching { current.cancelGenerateResponseAsync() }
            runCatching { current.close() }
            session = null
        }
    }

    /** Stops the answer being generated, if any. */
    fun cancel() {
        runCatching { session?.cancelGenerateResponseAsync() }
    }

    private fun loadLocked(model: ModelFile, useGpu: Boolean, maxTokens: Int): LlmInference {
        engine?.let { if (engineGpu == useGpu) return it }
        releaseLocked()
        _state.value = ModelState.Loading(model)
        fun create(gpu: Boolean) = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.path)
                .setMaxTokens(maxTokens)
                .setMaxTopK(TOP_K)
                .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                .build(),
        )
        val (llm, gpu) = try {
            create(useGpu) to useGpu
        } catch (e: UnsatisfiedLinkError) {
            // The runtime is packaged for 64-bit ARM only.
            _state.value = ModelState.Failed(model, "This phone's processor is not supported (64-bit ARM needed)")
            throw IllegalStateException("Language model runtime not available on this device", e)
        } catch (e: Exception) {
            if (!useGpu) {
                _state.value = ModelState.Failed(model, e.message ?: "The model could not be loaded")
                throw e
            }
            Log.w(TAG, "GPU backend failed, using the CPU", e)
            try {
                create(false) to false
            } catch (e2: Exception) {
                _state.value = ModelState.Failed(model, e2.message ?: "The model could not be loaded")
                throw e2
            }
        }
        engine = llm
        engineGpu = gpu
        _state.value = ModelState.Ready(model, gpu)
        return llm
    }

    /** Drops the oldest turns until the prompt leaves room for an answer. */
    private fun fitPrompt(llm: LlmInference, template: ChatTemplate, prompt: ChatPrompt, maxTokens: Int): String {
        var history = prompt.history
        while (true) {
            val text = template.format(prompt.copy(history = history))
            val tokens = runCatching { llm.sizeInTokens(text) }.getOrDefault(text.length / 4)
            if (tokens <= maxTokens - ANSWER_BUDGET || history.isEmpty()) return text
            history = history.drop(if (history.size >= 2) 2 else 1)
        }
    }

    private fun scheduleRelease() {
        idleRelease?.cancel()
        idleRelease = scope.launch(engineDispatcher) {
            delay(IDLE_RELEASE_MS)
            lock.withLock { releaseLocked() }
        }
    }

    private fun releaseLocked() {
        runCatching { session?.close() }
        session = null
        runCatching { engine?.close() }
        val wasLoaded = engine != null
        engine = null
        if (wasLoaded || _state.value is ModelState.Ready) {
            _state.value = installed()?.let { ModelState.Installed(it) } ?: ModelState.NotInstalled
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: "model.task"
                    val size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    return sanitize(name) to size
                }
            }
        return sanitize(uri.lastPathSegment ?: "model.task") to -1L
    }

    private fun sanitize(name: String) = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    private class StopReached : RuntimeException()

    private companion object {
        const val TAG = "LanguageModel"
        const val MODEL_DIR = "models"
        val EXTENSIONS = setOf("task", "bin", "litertlm")
        const val DEFAULT_MAX_TOKENS = 2_048
        const val ANSWER_BUDGET = 512
        const val TEMPERATURE = 0.7f
        const val TOP_K = 40
        const val TOP_P = 0.95f
        const val IDLE_RELEASE_MS = 3 * 60_000L
        const val REPORT_EVERY_BYTES = 16L shl 20
    }
}
