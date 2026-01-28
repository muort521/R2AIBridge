package com.r2aibridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.r2aibridge.service.R2ServiceForeground
import com.r2aibridge.ui.theme.R2AIBridgeTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val logEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "BroadcastReceiver: 收到广播 action=${intent?.action}")
            when (intent?.action) {
                R2ServiceForeground.ACTION_LOG_EVENT -> {
                    val message = intent.getStringExtra(R2ServiceForeground.EXTRA_LOG_MESSAGE)
                    Log.d("MainActivity", "BroadcastReceiver: 收到日志消息=$message")
                    message?.let { logMessage ->
                        Log.d("MainActivity", "BroadcastReceiver: 调用回调 callback=${logEventCallback != null}")
                        // 通过回调传递给Compose
                        logEventCallback?.invoke(logMessage)
                    }
                }
                R2ServiceForeground.ACTION_STOP -> {
                    Log.d("MainActivity", "BroadcastReceiver: 收到 ACTION_STOP，触发停止回调")
                    stopEventCallback?.invoke()
                }
            }
        }
    }
    
    private var logEventCallback: ((String) -> Unit)? = null
    private var stopEventCallback: (() -> Unit)? = null

    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "✅ 已获得所有文件访问权限", Toast.LENGTH_SHORT).show()
                startR2Service()
            } else {
                Toast.makeText(this, "⚠️ 未授予所有文件访问权限，部分功能可能无法使用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // 普通权限已授予，检查所有文件访问权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    // 引导用户到设置页面授予所有文件访问权限
                    requestAllFilesAccess()
                } else {
                    startR2Service()
                }
            } else {
                startR2Service()
            }
        } else {
            Toast.makeText(this, "⚠️ 需要授予所有权限才能正常使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 智能加载 Radare2 库（热插拔支持）
        try {
            R2Core.loadLibraries(this)
            Log.i("MainActivity", "✅ R2 libraries loaded via smart loader")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Failed to load R2 libraries", e)
            Toast.makeText(this, "R2库加载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // 启用边到边显示和透明状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 注册日志事件接收器，并同时监听服务停止动作，便于通知栏停止与主界面按钮行为一致
        val filter = IntentFilter().apply {
            addAction(R2ServiceForeground.ACTION_LOG_EVENT)
            addAction(R2ServiceForeground.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        
        requestRequiredPermissions()
        
        setContent {
            R2AIBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartService = { startR2Service() },
                        onStopService = { stopR2Service() },
                        getWifiIpAddress = { getWifiIpAddress() },
                        onLogEventCallbackSet = { callback -> logEventCallback = callback },
                        onStopEventCallbackSet = { callback -> stopEventCallback = callback }
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        // 先检查Android 11+的所有文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "已有所有文件访问权限")
                // 已有权限，直接请求其他权限
                requestOtherPermissions()
                return
            }
        }
        
        // 请求普通权限
        requestOtherPermissions()
    }
    
    private fun requestOtherPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Android 10及以下才需要这些权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                Toast.makeText(this, "请授予'允许访问所有文件'权限", Toast.LENGTH_LONG).show()
                manageAllFilesLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                Toast.makeText(this, "请授予'允许访问所有文件'权限", Toast.LENGTH_LONG).show()
                manageAllFilesLauncher.launch(intent)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logEventReceiver)
        } catch (_: Exception) {}
    }

    private fun startR2Service() {
        val intent = Intent(this, R2ServiceForeground::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopR2Service() {
        val intent = Intent(this, R2ServiceForeground::class.java)
        stopService(intent)
    }

    private fun getWifiIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                return "未连接WiFi"
            }
            
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val ipInt = wifiInfo.ipAddress
            
            if (ipInt == 0) {
                return "未连接WiFi"
            }
            
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "未连接WiFi"
        }
    }
}

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    getWifiIpAddress: () -> String,
    onLogEventCallbackSet: ((String) -> Unit) -> Unit,
    onStopEventCallbackSet: (() -> Unit) -> Unit
) {
    var isServiceRunning by remember { mutableStateOf(true) } // 默认启动
    val commandHistory = remember { mutableStateListOf<String>() }
    val context = LocalContext.current
    val view = LocalView.current
    
    // 设置日志事件回调
        LaunchedEffect(Unit) {
        Log.d("MainActivity", "LaunchedEffect: 设置日志回调")
        onLogEventCallbackSet { logMessage ->
            Log.d("MainActivity", "Callback: 收到日志=$logMessage")
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            commandHistory.add(0, "[$timestamp] $logMessage")
        }
        // 注册停止事件回调，通知栏停止时通过此回调更新 UI
        onStopEventCallbackSet {
            isServiceRunning = false
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            commandHistory.add(0, "[$timestamp] ⛔ 服务已停止")
        }
        // 添加初始消息
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        commandHistory.add(0, "[$timestamp] 📱 应用启动")
        Log.d("MainActivity", "LaunchedEffect: 启动服务")
        // 启动服务
        onStartService()
    }
    
    // 设置透明状态栏和图标颜色
    val darkTheme = isSystemInDarkTheme()
    
    SideEffect {
        val window = (view.context as ComponentActivity).window
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        
        // 设置状态栏图标颜色：明亮模式用深色图标，深色模式用浅色图标
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }
    
    val wifiIp = remember(isServiceRunning) { getWifiIpAddress() }
    val localhostUrl = "http://127.0.0.1:5050/messages"
    val wifiUrl = if (wifiIp != "未连接WiFi") "http://$wifiIp:5050/messages" else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "Radare2 AI Bridge",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Telegram Group Link
        Text(
            text = "Telegram群: t.me/MuortVIP",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/MuortVIP"))
                    context.startActivity(intent)
                }
                .padding(bottom = 16.dp)
        )

        // Service Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning) 
                    Color(0xFF4CAF50) 
                else 
                    Color(0xFFFF9800)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = if (isServiceRunning) "服务运行中" else "服务已停止",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                
                if (isServiceRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "端口: 5050",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 本地地址
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "本地地址:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Text(
                                text = localhostUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                        }
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("MCP URL", localhostUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制: $localhostUrl", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📋", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // WiFi 地址
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "WiFi地址:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Text(
                                text = wifiUrl ?: wifiIp,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                        }
                        Button(
                            onClick = {
                                if (wifiUrl != null) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("MCP URL", wifiUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制: $wifiUrl", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "WiFi未连接", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📋", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        // Control Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onStartService()
                    isServiceRunning = true
                },
                modifier = Modifier.weight(1f),
                enabled = !isServiceRunning
            ) {
                Text("启动服务")
            }

            Button(
                onClick = {
                    onStopService()
                    isServiceRunning = false
                    val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    commandHistory.add(0, "[$timestamp] ⛔ 服务已停止")
                },
                modifier = Modifier.weight(1f),
                enabled = isServiceRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("停止服务")
            }
        }

        // MCP Tools Info
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "可用的 MCP 工具 (11个)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val tools = listOf(
                    "🚪 r2_open_file - 打开文件 (默认 aa 基础分析)",
                    "⚡ r2_analyze_file - 深度分析 (aaa, 耗时较长)",
                    "⚙️ r2_run_command - 执行 R2 命令 (通用)",
                    "📋 r2_list_functions - 列出函数列表",
                    "📝 r2_list_strings - 列出字符串 (逆向第一步)",
                    "🔗 r2_get_xrefs - 获取交叉引用 (逻辑追踪)",
                    "ℹ️ r2_get_info - 获取文件详细信息",
                    "🔍 r2_decompile_function - 反编译函数",
                    "📜 r2_disassemble - 反汇编代码",
                    "🧪 r2_test - 测试 R2 库状态 (诊断)",
                    "🔒 r2_close_session - 关闭会话"
                )
                
                tools.forEach { tool ->
                    Text(
                        text = "• $tool",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        // Command History
        if (commandHistory.isNotEmpty()) {
            Text(
                text = "历史记录 (${commandHistory.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // 使用剩余空间
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    items(commandHistory) { command ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
