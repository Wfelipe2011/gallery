package com.google.ai.edge.gallery.data

import com.google.ai.edge.gallery.data.Model
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that holds the currently active (initialized) LLM model and serializes concurrent
 * inference requests across the UI and the local API server.
 */
@Singleton
class ModelRepository @Inject constructor() {

  @Volatile private var activeModel: Model? = null

  private val inferenceLock = AtomicBoolean(false)

  /** Called by the UI ViewModel after a model finishes loading. */
  fun setActiveModel(model: Model) {
    activeModel = model
  }

  /** Returns the currently active model, or null if none is loaded. */
  fun getActiveModel(): Model? = activeModel

  /**
   * Attempts to acquire the inference lock. Returns true if acquired (caller may proceed),
   * false if another inference is already in progress.
   */
  fun tryAcquireInference(): Boolean = inferenceLock.compareAndSet(false, true)

  /** Releases the inference lock so the next request can proceed. */
  fun releaseInference() {
    inferenceLock.set(false)
  }
}
