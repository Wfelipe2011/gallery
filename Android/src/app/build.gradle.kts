/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.InputStream
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

fun String.asBuildConfigStringLiteral(): String =
  "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val ngrokKeyFile = rootProject.layout.projectDirectory.file("../../ngrok.key")
val ngrokAuthtokenProvider =
  if (ngrokKeyFile.asFile.isFile) {
    providers.fileContents(ngrokKeyFile).asText.map { it.trim() }
  } else {
    providers.provider { "" }
  }

val ngrokNativeArtifact = configurations.create("ngrokNativeArtifact") {
  isCanBeConsumed = false
  isCanBeResolved = true
}
val ngrokNativeJniLibsDir = layout.buildDirectory.dir("generated/ngrokNativeJniLibs")
val ngrokNativeArtifactFiles = ngrokNativeArtifact.incoming.artifactView {}.files
val extractNgrokNativeJniLibs =
  tasks.register("extractNgrokNativeJniLibs") {
    val libraryFile = ngrokNativeJniLibsDir.map { it.file("arm64-v8a/libngrok_java.so") }
    inputs.files(ngrokNativeArtifactFiles)
    outputs.file(libraryFile)
    outputs.upToDateWhen { false }

    doLast {
      val artifact =
        ngrokNativeArtifactFiles.files.singleOrNull { file ->
          file.name.startsWith("ngrok-java-native") && file.extension == "jar"
        } ?: throw GradleException("Unable to find ngrok-java-native artifact.")

      val bytes =
        ZipFile(artifact).use { zipFile: ZipFile ->
          val entry =
            zipFile.getEntry("libngrok_java.so")
              ?: throw GradleException("ngrok native artifact does not contain libngrok_java.so.")
          zipFile.getInputStream(entry).use { input: InputStream -> input.readBytes() }
        }

      val jniVersion18 =
        byteArrayOf(0x00, 0x01, 0x80.toByte(), 0x52, 0x20, 0x00, 0xa0.toByte(), 0x72)
      val jniVersion16 =
        byteArrayOf(0xc0.toByte(), 0x00, 0x80.toByte(), 0x52, 0x20, 0x00, 0xa0.toByte(), 0x72)

      fun ByteArray.sliceIndexes(slice: ByteArray): List<Int> {
        val indexes = mutableListOf<Int>()
        for (index in 0..size - slice.size) {
          var matches = true
          for (sliceIndex in slice.indices) {
            if (this[index + sliceIndex] != slice[sliceIndex]) {
              matches = false
              break
            }
          }
          if (matches) indexes += index
        }
        return indexes
      }

      val patchIndexes = bytes.sliceIndexes(jniVersion18)
      if (patchIndexes.size != 1) {
        throw GradleException(
          "Expected one ngrok JNI_VERSION_1_8 return sequence, found ${patchIndexes.size}.",
        )
      }

      jniVersion16.copyInto(bytes, patchIndexes.single())
      val outputFile = libraryFile.get().asFile
      outputFile.parentFile.mkdirs()
      outputFile.writeBytes(bytes)
    }
  }

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.google.aiedge.gallery"
    minSdk = 31
    targetSdk = 35
    versionCode = 29
    versionName = "1.0.12"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField(
      "String",
      "NGROK_AUTHTOKEN",
      ngrokAuthtokenProvider.get().asBuildConfigStringLiteral(),
    )
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  sourceSets {
    getByName("main") {
      jniLibs.srcDir(ngrokNativeJniLibsDir)
    }
  }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
  dependsOn(extractNgrokNativeJniLibs)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.gson)
  implementation(libs.ngrok.java)
  implementation(variantOf(libs.ngrok.java.native) { classifier("linux-android-aarch_64") })
  ngrokNativeArtifact(variantOf(libs.ngrok.java.native) { classifier("linux-android-aarch_64") })
  implementation(libs.slf4j.api)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
