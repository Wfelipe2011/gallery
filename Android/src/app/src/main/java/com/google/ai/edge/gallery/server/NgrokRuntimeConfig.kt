package com.google.ai.edge.gallery.server

import android.os.Build
import com.google.ai.edge.gallery.BuildConfig

object NgrokRuntimeConfig {
  const val LOCAL_FORWARD_URL = "http://127.0.0.1:8080"
  private const val SUPPORTED_ANDROID_ABI = "arm64-v8a"

  val authtoken: String
    get() = BuildConfig.NGROK_AUTHTOKEN.trim()

  fun hasAuthtoken(): Boolean = authtoken.isNotEmpty()

  fun isSupportedDeviceAbi(): Boolean = Build.SUPPORTED_ABIS.contains(SUPPORTED_ANDROID_ABI)

  fun unsupportedAbiErrorMessage(): String? {
    if (isSupportedDeviceAbi()) return null
    val deviceAbis = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" }
    return "Embedded ngrok tunnel is packaged for Android ARM64 only. Device ABI(s): $deviceAbis."
  }
}
