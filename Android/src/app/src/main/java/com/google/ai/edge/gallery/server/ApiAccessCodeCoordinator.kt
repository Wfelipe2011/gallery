package com.google.ai.edge.gallery.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ApiAccessCodeCoordinator @Inject constructor() {
  private val random = SecureRandom()
  private val lock = Any()
  private val _accessCode = MutableStateFlow<String?>(null)

  val accessCode = _accessCode.asStateFlow()

  fun currentCode(): String =
    synchronized(lock) {
      _accessCode.value ?: generateCode().also { _accessCode.value = it }
    }

  fun regenerateCode(): String =
    synchronized(lock) {
      val previousCode = _accessCode.value
      var nextCode: String
      do {
        nextCode = generateCode()
      } while (nextCode == previousCode)
      _accessCode.value = nextCode
      nextCode
    }

  fun matchesCurrentCode(candidate: String): Boolean {
    val currentCodeBytes = currentCode().toByteArray(Charsets.UTF_8)
    val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(candidateBytes, currentCodeBytes)
  }

  private fun generateCode(): String = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
}
