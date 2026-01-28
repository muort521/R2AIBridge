package com.r2aibridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.r2aibridge.MainActivity
import com.r2aibridge.R
import com.r2aibridge.mcp.MCPServer
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

class R2ServiceForeground : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var ktorServer: EmbeddedServer<*, *>? = null
    private var currentCommand: String = "待机中"
    
    companion object {
        private const val CHANNEL_ID = "R2_SERVICE_CHANNEL"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 5050
        
        const val ACTION_STOP = "com.r2aibridge.ACTION_STOP"
        const val ACTION_LOG_EVENT = "com.r2aibridge.ACTION_LOG_EVENT"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startKtorServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("R2Service", "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("R2Service", "Received ACTION_STOP, stopping foreground service")
                // 更新通知状态，但不重复发送日志广播，直接发送 ACTION_STOP 供 UI 更新
                updateCurrentCommand("⛔ 服务已停止")
                try {
                    val stopIntentBroadcast = Intent(ACTION_STOP).apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(stopIntentBroadcast)
                } catch (e: Exception) {
                    Log.e("R2Service", "Failed to broadcast ACTION_STOP", e)
                }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ktorServer?.stop(1000, 2000)
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "R2服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Radare2 AI Bridge 后台服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, R2ServiceForeground::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val localIp = getLocalIpAddress()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("R2 MCP 服务运行中")
            .setContentText("$localIp:$PORT | $currentCommand")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun startKtorServer() {
        serviceScope.launch {
            try {
                ktorServer = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                    MCPServer.configure(this) { logEvent ->
                        updateCurrentCommand(logEvent)
                        broadcastLogEvent(logEvent)
                    }
                }.start(wait = false)
                
                val startMsg = "✅ 服务启动: 0.0.0.0:$PORT"
                updateCurrentCommand(startMsg)
                broadcastLogEvent(startMsg)
            } catch (e: Exception) {
                updateCurrentCommand("启动失败: ${e.message}")
            }
        }
    }

    private fun updateCurrentCommand(command: String) {
        currentCommand = command
        val notification = createNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun broadcastLogEvent(message: String) {
        Log.d("R2Service", "broadcastLogEvent: 发送广播 message=$message")
        val intent = Intent(ACTION_LOG_EVENT).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            setPackage(packageName) // 显式设置包名
        }
        sendBroadcast(intent)
        Log.d("R2Service", "broadcastLogEvent: 广播已发送")
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "未知"
                    }
                }
            }
        } catch (e: Exception) {
            return "未知"
        }
        return "未知"
    }
}
