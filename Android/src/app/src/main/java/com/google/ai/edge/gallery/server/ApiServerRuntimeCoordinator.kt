package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelRepository
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val API_RUNTIME_IDLE_TIMEOUT_MS = 60_000L
private const val TAG = "ApiServerRuntime"

sealed class ApiServerModelLoadResult {
  data class Success(val model: Model, val profile: RequiredRuntimeProfile) : ApiServerModelLoadResult()

  data class Failure(val message: String) : ApiServerModelLoadResult()
}

enum class ApiServerRuntimeStatus {
  NO_MODEL_SELECTED,
  READY_FOR_REQUEST,
  LOADING,
  READY,
  UNLOADED_AFTER_IDLE,
  ERROR,
}

data class ApiServerRuntimeState(
  val selectedModelName: String? = null,
  val loadedModelName: String? = null,
  val loadedRuntimeProfile: RequiredRuntimeProfile? = null,
  val loadingRuntimeProfile: RequiredRuntimeProfile? = null,
  val status: ApiServerRuntimeStatus = ApiServerRuntimeStatus.NO_MODEL_SELECTED,
  val errorMessage: String? = null,
)

@Singleton
class ApiServerRuntimeCoordinator
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val modelRepository: ModelRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val stateLock = Any()
  private val _runtimeState = MutableStateFlow(ApiServerRuntimeState())

  val runtimeState = _runtimeState.asStateFlow()

  @Volatile private var selectedApiModel: Model? = null
  @Volatile private var loadedApiModel: Model? = null
  @Volatile private var loadedApiRuntimeProfile: RequiredRuntimeProfile? = null
  @Volatile private var lastActivityMs: Long = 0L

  private var selectionToken: Long = 0L
  private var runtimeGeneration: Long = 0L
  private var idleUnloadToken: Long = 0L
  private var idleUnloadJob: Job? = null

  fun selectModel(model: Model) {
    synchronized(stateLock) {
      selectedApiModel = model
      selectionToken += 1
      runtimeGeneration += 1
      cancelPendingIdleUnloadLocked()
      publishSelectedRuntimeStateLocked()
    }
  }

  fun clearSelectedModel() {
    synchronized(stateLock) {
      selectedApiModel = null
      selectionToken += 1
      runtimeGeneration += 1
      cancelPendingIdleUnloadLocked()
      publishSelectedRuntimeStateLocked()
    }
  }

  fun getSelectedModel(): Model? = selectedApiModel

  fun getLoadedRuntimeProfile(): RequiredRuntimeProfile? = loadedApiRuntimeProfile

  fun getLastActivityMs(): Long = lastActivityMs

  fun onRequestAccepted() {
    synchronized(stateLock) {
      lastActivityMs = System.currentTimeMillis()
      cancelPendingIdleUnloadLocked()
    }
  }

  fun ensureModelLoaded(requiredProfile: RequiredRuntimeProfile): ApiServerModelLoadResult {
    val selectedSnapshot = synchronized(stateLock) {
      selectedApiModel?.let { model ->
        SelectedApiModelSnapshot(model = model, selectionToken = selectionToken)
      }
    }
      ?: return ApiServerModelLoadResult.Failure("No API model is selected.")
    val selectedModel = selectedSnapshot.model
    val downloadedModels = modelRepository.getDownloadedModels()
    if (downloadedModels.isEmpty()) {
      publishRuntimeErrorIfCurrent(
        selectedSnapshot,
        "No downloaded LLM model is available for the API server.",
      )
      return ApiServerModelLoadResult.Failure("No downloaded LLM model is available for the API server.")
    }
    val model = downloadedModels.find { it.name == selectedModel.name }
      ?: run {
        val message = "Selected API model '${selectedModel.name}' is not downloaded."
        publishRuntimeErrorIfCurrent(selectedSnapshot, message)
        return ApiServerModelLoadResult.Failure(message)
      }

    findReusableLoadedModel(
      model = model,
      requiredProfile = requiredProfile,
      selectedSnapshot = selectedSnapshot,
    )?.let { return it }

    if (model.initializing && model.instance == null) {
      publishRuntimeLoadingIfCurrent(selectedSnapshot, requiredProfile)
      return ApiServerModelLoadResult.Failure(
        "Selected API model '${model.name}' is still initializing."
      )
    }

    if (!publishRuntimeLoadingIfCurrent(selectedSnapshot, requiredProfile)) {
      return ApiServerModelLoadResult.Failure(
        "Selected API model changed while '${model.name}' was loading."
      )
    }
    if (!cleanupBeforeReload(modelToLoad = model, selectedSnapshot = selectedSnapshot)) {
      return ApiServerModelLoadResult.Failure(
        "Selected API model changed while '${model.name}' was loading."
      )
    }
    if (!isSelectionCurrent(selectedSnapshot)) {
      return ApiServerModelLoadResult.Failure(
        "Selected API model changed while '${model.name}' was loading."
      )
    }

    val initializationError = initializeModel(model = model, requiredProfile = requiredProfile)
    if (!isSelectionCurrent(selectedSnapshot)) {
      cleanupModel(model, updateRuntimeState = false)
      return ApiServerModelLoadResult.Failure(
        "Selected API model changed while '${model.name}' was loading."
      )
    }
    if (initializationError != null) {
      clearLoadedRuntime(model)
      publishRuntimeErrorIfCurrent(
        selectedSnapshot,
        "Failed to initialize API model '${model.name}': $initializationError",
      )
      return ApiServerModelLoadResult.Failure(
        "Failed to initialize API model '${model.name}': $initializationError"
      )
    }

    var becameStale = false
    synchronized(stateLock) {
      if (!isSelectionCurrentLocked(selectedSnapshot)) {
        becameStale = true
      } else {
        modelRepository.setActiveModel(model)
        loadedApiModel = model
        loadedApiRuntimeProfile = requiredProfile
        runtimeGeneration += 1
        publishRuntimeStateLocked(
          status = ApiServerRuntimeStatus.READY,
          selectedModel = selectedApiModel,
          loadedModel = model,
          loadedProfile = requiredProfile,
        )
      }
    }
    if (becameStale) {
      cleanupModel(model, updateRuntimeState = false)
      return ApiServerModelLoadResult.Failure(
        "Selected API model changed while '${model.name}' was loading."
      )
    }
    Log.d(TAG, "Loaded API model '${model.name}' with profile $requiredProfile")
    return ApiServerModelLoadResult.Success(model = model, profile = requiredProfile)
  }

  fun scheduleIdleUnload(hasActiveServerSessions: () -> Boolean) {
    val snapshot = synchronized(stateLock) {
      lastActivityMs = System.currentTimeMillis()
      val model = loadedApiModel ?: return
      val profile = loadedApiRuntimeProfile ?: return
      val instance = model.instance ?: return
      idleUnloadToken += 1
      IdleUnloadSnapshot(
        idleUnloadToken = idleUnloadToken,
        runtimeGeneration = runtimeGeneration,
        selectedModelName = selectedApiModel?.name,
        model = model,
        instance = instance,
        profile = profile,
      )
    }
    scheduleIdleUnload(snapshot = snapshot, hasActiveServerSessions = hasActiveServerSessions)
  }

  fun cancelPendingIdleUnload() {
    synchronized(stateLock) { cancelPendingIdleUnloadLocked() }
  }

  private fun findReusableLoadedModel(
    model: Model,
    requiredProfile: RequiredRuntimeProfile,
    selectedSnapshot: SelectedApiModelSnapshot,
  ): ApiServerModelLoadResult.Success? {
    synchronized(stateLock) {
      if (!isSelectionCurrentLocked(selectedSnapshot)) return null

      val trackedModel = loadedApiModel
      val trackedProfile = loadedApiRuntimeProfile
      if (
        trackedModel === model &&
          trackedModel.instance != null &&
          trackedProfile != null &&
          trackedProfile.satisfies(requiredProfile)
      ) {
        publishRuntimeStateLocked(
          status = ApiServerRuntimeStatus.READY,
          selectedModel = selectedApiModel,
          loadedModel = trackedModel,
          loadedProfile = trackedProfile,
        )
        return ApiServerModelLoadResult.Success(model = model, profile = trackedProfile)
      }

      if (
        modelRepository.getActiveModel() === model &&
          model.instance != null &&
          requiredProfile == RequiredRuntimeProfile.TEXT
      ) {
        loadedApiModel = model
        loadedApiRuntimeProfile = RequiredRuntimeProfile.TEXT
        runtimeGeneration += 1
        publishRuntimeStateLocked(
          status = ApiServerRuntimeStatus.READY,
          selectedModel = selectedApiModel,
          loadedModel = model,
          loadedProfile = RequiredRuntimeProfile.TEXT,
        )
        return ApiServerModelLoadResult.Success(model = model, profile = RequiredRuntimeProfile.TEXT)
      }
    }
    return null
  }

  private fun cleanupBeforeReload(
    modelToLoad: Model,
    selectedSnapshot: SelectedApiModelSnapshot,
  ): Boolean {
    val modelsToCleanup = mutableListOf<Model>()
    fun addModel(model: Model?) {
      if (model != null && model.instance != null && modelsToCleanup.none { it === model }) {
        modelsToCleanup.add(model)
      }
    }

    synchronized(stateLock) {
      if (!isSelectionCurrentLocked(selectedSnapshot)) return false
      addModel(loadedApiModel)
    }
    addModel(modelRepository.getActiveModel())
    addModel(modelToLoad)

    modelsToCleanup.forEach { model ->
      if (!isSelectionCurrent(selectedSnapshot)) return false
      Log.d(TAG, "Cleaning API runtime before reload for '${model.name}'")
      LlmChatModelHelper.cleanUp(model) {}
      if (modelRepository.getActiveModel() === model) {
        modelRepository.setActiveModel(null)
      }
    }

    synchronized(stateLock) {
      loadedApiModel = null
      loadedApiRuntimeProfile = null
      runtimeGeneration += 1
    }
    return true
  }

  private fun initializeModel(model: Model, requiredProfile: RequiredRuntimeProfile): String? {
    var initializationError = ""
    model.initializing = true
    model.cleanUpAfterInit = false
    try {
      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        supportImage = requiredProfile.supportImage,
        supportAudio = requiredProfile.supportAudio,
        onDone = { error -> initializationError = error },
        systemInstruction = null,
        tools = emptyList(),
        enableConversationConstrainedDecoding = false,
        coroutineScope = null,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize API model '${model.name}'", e)
      initializationError = e.message ?: e.javaClass.simpleName
    } finally {
      model.initializing = false
    }

    if (model.instance != null && initializationError.isEmpty()) return null
    return initializationError.ifEmpty { "Model initialization failed." }
  }

  private fun clearLoadedRuntime(model: Model, updateRuntimeState: Boolean = true) {
    if (modelRepository.getActiveModel() === model) {
      modelRepository.setActiveModel(null)
    }
    synchronized(stateLock) {
      if (loadedApiModel === model) {
        loadedApiModel = null
        loadedApiRuntimeProfile = null
        runtimeGeneration += 1
      }
      if (updateRuntimeState) publishSelectedRuntimeStateLocked()
    }
  }

  private fun cleanupModel(model: Model, updateRuntimeState: Boolean = true) {
    if (model.instance != null) {
      LlmChatModelHelper.cleanUp(model) {}
    }
    model.initializing = false
    clearLoadedRuntime(model, updateRuntimeState = updateRuntimeState)
  }

  private fun scheduleIdleUnload(
    snapshot: IdleUnloadSnapshot,
    hasActiveServerSessions: () -> Boolean,
  ) {
    val job = scope.launch {
      delay(API_RUNTIME_IDLE_TIMEOUT_MS)
      runIdleUnload(snapshot = snapshot, hasActiveServerSessions = hasActiveServerSessions)
    }
    synchronized(stateLock) { idleUnloadJob = job }
  }

  private fun runIdleUnload(
    snapshot: IdleUnloadSnapshot,
    hasActiveServerSessions: () -> Boolean,
  ) {
    if (!isSnapshotCurrent(snapshot)) return
    if (!modelRepository.tryAcquireInference()) {
      rescheduleIdleUnloadIfCurrent(snapshot, hasActiveServerSessions)
      return
    }

    try {
      if (!isSnapshotCurrent(snapshot)) return
      if (snapshot.model.initializing || hasActiveServerSessions()) {
        rescheduleIdleUnloadIfCurrent(snapshot, hasActiveServerSessions)
        return
      }

      Log.d(TAG, "Idle-unloading API model '${snapshot.model.name}' with profile ${snapshot.profile}")
      LlmChatModelHelper.cleanUp(snapshot.model) {}
      if (modelRepository.getActiveModel() === snapshot.model) {
        modelRepository.setActiveModel(null)
      }
      synchronized(stateLock) {
        if (isSnapshotOwnerLocked(snapshot)) {
          loadedApiModel = null
          loadedApiRuntimeProfile = null
          runtimeGeneration += 1
          idleUnloadJob = null
          publishRuntimeStateLocked(
            status = ApiServerRuntimeStatus.UNLOADED_AFTER_IDLE,
            selectedModel = selectedApiModel,
          )
        }
      }
    } finally {
      modelRepository.releaseInference()
    }
  }

  private fun rescheduleIdleUnloadIfCurrent(
    snapshot: IdleUnloadSnapshot,
    hasActiveServerSessions: () -> Boolean,
  ) {
    synchronized(stateLock) {
      if (!isSnapshotOwnerLocked(snapshot)) return
    }
    scheduleIdleUnload(snapshot = snapshot, hasActiveServerSessions = hasActiveServerSessions)
  }

  private fun isSnapshotCurrent(snapshot: IdleUnloadSnapshot): Boolean =
    synchronized(stateLock) {
      isSnapshotOwnerLocked(snapshot) && snapshot.model.instance === snapshot.instance
    }

  private fun isSnapshotOwnerLocked(snapshot: IdleUnloadSnapshot): Boolean =
    idleUnloadToken == snapshot.idleUnloadToken &&
      runtimeGeneration == snapshot.runtimeGeneration &&
      selectedApiModel?.name == snapshot.selectedModelName &&
      loadedApiModel === snapshot.model &&
      loadedApiRuntimeProfile == snapshot.profile

  private fun isSelectionCurrent(snapshot: SelectedApiModelSnapshot): Boolean =
    synchronized(stateLock) { isSelectionCurrentLocked(snapshot) }

  private fun isSelectionCurrentLocked(snapshot: SelectedApiModelSnapshot): Boolean =
    selectionToken == snapshot.selectionToken && selectedApiModel?.name == snapshot.model.name

  private fun publishRuntimeLoadingIfCurrent(
    snapshot: SelectedApiModelSnapshot,
    requiredProfile: RequiredRuntimeProfile,
  ): Boolean = synchronized(stateLock) {
    if (!isSelectionCurrentLocked(snapshot)) return@synchronized false

    publishRuntimeStateLocked(
      status = ApiServerRuntimeStatus.LOADING,
      selectedModel = selectedApiModel,
      loadingProfile = requiredProfile,
    )
    true
  }

  private fun publishRuntimeErrorIfCurrent(
    snapshot: SelectedApiModelSnapshot,
    message: String,
  ): Boolean = synchronized(stateLock) {
    if (!isSelectionCurrentLocked(snapshot)) return@synchronized false

    publishRuntimeStateLocked(
      status = ApiServerRuntimeStatus.ERROR,
      selectedModel = selectedApiModel,
      errorMessage = message,
    )
    true
  }

  private fun publishSelectedRuntimeStateLocked() {
    val selectedModel = selectedApiModel
    if (selectedModel == null) {
      publishRuntimeStateLocked(status = ApiServerRuntimeStatus.NO_MODEL_SELECTED)
      return
    }

    val loadedModel = loadedApiModel?.takeIf { loadedModel ->
      loadedModel.name == selectedModel.name && loadedModel.instance != null
    }
    val loadedProfile = if (loadedModel != null) loadedApiRuntimeProfile else null
    if (loadedModel != null && loadedProfile != null) {
      publishRuntimeStateLocked(
        status = ApiServerRuntimeStatus.READY,
        selectedModel = selectedModel,
        loadedModel = loadedModel,
        loadedProfile = loadedProfile,
      )
    } else {
      publishRuntimeStateLocked(
        status = ApiServerRuntimeStatus.READY_FOR_REQUEST,
        selectedModel = selectedModel,
      )
    }
  }

  private fun publishRuntimeStateLocked(
    status: ApiServerRuntimeStatus,
    selectedModel: Model? = null,
    loadedModel: Model? = null,
    loadedProfile: RequiredRuntimeProfile? = null,
    loadingProfile: RequiredRuntimeProfile? = null,
    errorMessage: String? = null,
  ) {
    val activeLoadedModel = loadedModel?.takeIf { it.instance != null }
    _runtimeState.value = ApiServerRuntimeState(
      selectedModelName = selectedModel?.name,
      loadedModelName = activeLoadedModel?.name,
      loadedRuntimeProfile = if (activeLoadedModel != null) loadedProfile else null,
      loadingRuntimeProfile = loadingProfile,
      status = status,
      errorMessage = errorMessage,
    )
  }

  private fun cancelPendingIdleUnloadLocked() {
    idleUnloadToken += 1
    idleUnloadJob?.cancel()
    idleUnloadJob = null
  }
}

private data class IdleUnloadSnapshot(
  val idleUnloadToken: Long,
  val runtimeGeneration: Long,
  val selectedModelName: String?,
  val model: Model,
  val instance: Any,
  val profile: RequiredRuntimeProfile,
)

private data class SelectedApiModelSnapshot(
  val model: Model,
  val selectionToken: Long,
)
