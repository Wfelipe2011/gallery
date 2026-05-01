package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.InferenceServerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ApiServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _connectionUrl = MutableStateFlow("")
    val connectionUrl = _connectionUrl.asStateFlow()

    private val _selectedApiModel = MutableStateFlow<Model?>(null)
    val selectedApiModel = _selectedApiModel.asStateFlow()

    fun startServer() {
        val intent = Intent(context, InferenceServerService::class.java)
        context.startForegroundService(intent)
        _isServerRunning.value = true
        _connectionUrl.value = "http://${getLocalIpAddress()}:8080/v1/chat/completions"
    }

    fun stopServer() {
        val intent = Intent(context, InferenceServerService::class.java)
        context.stopService(intent)
        _isServerRunning.value = false
        _connectionUrl.value = ""
        clearSelectedModel()
    }

    fun selectModel(model: Model) {
        _selectedApiModel.value = model
    }

    fun clearSelectedModel() {
        _selectedApiModel.value = null
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
