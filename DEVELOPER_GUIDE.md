# 开发者指南

本文档面向希望修改或扩展 Radare2 AI Bridge 项目的开发者。

## 🛠️ 开发环境设置

### 必需工具
- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **Android SDK** API 34
- **Android NDK** 25.1.8937393 或更高版本
- **CMake** 3.22.1
- **Gradle** 8.2
- **JDK** 17

### 推荐插件
- Kotlin (内置)
- Android NDK Support
- C/C++ Support

## 📂 项目结构详解

```
Radare2/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/                    # C++ JNI 代码
│   │   │   │   ├── CMakeLists.txt      # 构建配置
│   │   │   │   ├── native-lib.cpp      # JNI 实现
│   │   │   │   └── include/libr/       # Radare2 头文件
│   │   │   ├── java/com/r2aibridge/
│   │   │   │   ├── R2Core.kt           # JNI 接口
│   │   │   │   ├── MainActivity.kt     # UI 入口
│   │   │   │   ├── service/            # 服务层
│   │   │   │   │   └── R2ServiceForeground.kt
│   │   │   │   ├── mcp/                # MCP 协议
│   │   │   │   │   ├── MCPModels.kt
│   │   │   │   │   └── MCPServer.kt
│   │   │   │   ├── concurrency/        # 并发控制
│   │   │   │   │   └── R2ConcurrencyManager.kt
│   │   │   │   └── ui/theme/           # UI 主题
│   │   │   ├── jniLibs/arm64-v8a/      # 原生库
│   │   │   ├── res/                    # 资源文件
│   │   │   └── AndroidManifest.xml
│   │   └── test/                       # 单元测试
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── 文档...
```

## 🔧 核心组件

### 1. JNI 层 (native-lib.cpp)

**职责**: 桥接 Java/Kotlin 与 Radare2 C API

**关键函数**:
```cpp
// 初始化 Radare2 核心
JNIEXPORT jlong JNICALL Java_com_r2aibridge_R2Core_initR2Core(...)

// 执行命令
JNIEXPORT jstring JNICALL Java_com_r2aibridge_R2Core_executeCommand(...)

// 关闭核心
JNIEXPORT void JNICALL Java_com_r2aibridge_R2Core_closeR2Core(...)
```

**扩展示例** - 添加新的 JNI 函数:
```cpp
// 在 native-lib.cpp 中添加
extern "C" JNIEXPORT jstring JNICALL
Java_com_r2aibridge_R2Core_getArchitecture(
        JNIEnv* env,
        jobject /* this */,
        jlong corePtr) {
    RCore* core = reinterpret_cast<RCore*>(corePtr);
    const char* arch = r_config_get(core->config, "asm.arch");
    return env->NewStringUTF(arch ? arch : "unknown");
}

// 在 R2Core.kt 中声明
external fun getArchitecture(corePtr: Long): String
```

### 2. MCP 服务器 (MCPServer.kt)

**职责**: 处理 JSON-RPC 2.0 请求，调度工具执行

**添加新工具**:
```kotlin
// 1. 在 handleToolsList() 中添加工具定义
createToolSchema(
    "r2_new_tool",
    "新工具的描述",
    mapOf(
        "param1" to mapOf("type" to "string", "description" to "参数说明")
    ),
    listOf("param1")
)

// 2. 在 handleToolCall() 中添加路由
when (toolName) {
    // ... 现有工具
    "r2_new_tool" -> executeNewTool(arguments)
    else -> // ...
}

// 3. 实现工具函数
private suspend fun executeNewTool(args: JsonObject): JsonElement {
    val param1 = args["param1"]?.jsonPrimitive?.content
        ?: return json.encodeToJsonElement(ToolCallResult(success = false, error = "Missing param1"))
    
    // 工具逻辑
    val result = doSomething(param1)
    
    return json.encodeToJsonElement(ToolCallResult(success = true, output = result))
}
```

### 3. 并发管理器 (R2ConcurrencyManager.kt)

**职责**: 使用桶锁机制管理并发访问

**调整桶数量**:
```kotlin
// 修改 BUCKET_COUNT 以平衡并发性能
private const val BUCKET_COUNT = 32  // 从 16 增加到 32
```

**使用示例**:
```kotlin
// 在需要文件锁保护的地方
R2ConcurrencyManager.withFileLock(filePath) {
    // 临界区代码
    val result = R2Core.executeCommand(corePtr, command)
    result
}

// 非阻塞尝试
val result = R2ConcurrencyManager.tryWithFileLock(filePath) {
    performOperation()
}
if (result == null) {
    // 无法获取锁，处理冲突
}
```

### 4. 前台服务 (R2ServiceForeground.kt)

**职责**: 运行 Ktor 服务器，管理服务生命周期

**修改端口**:
```kotlin
companion object {
    private const val PORT = 8080  // 从 5050 改为 8080
}
```

**添加新路由**:
```kotlin
// 在 MCPServer.configure() 的 routing {} 块中
get("/custom-endpoint") {
    call.respondText("Custom response")
}
```

## 🧪 测试

### 单元测试

创建 `app/src/test/java/com/r2aibridge/` 目录并添加测试：

```kotlin
// R2CoreTest.kt
class R2CoreTest {
    @Test
    fun testInitR2Core() {
        val corePtr = R2Core.initR2Core()
        assertNotEquals(0L, corePtr)
        R2Core.closeR2Core(corePtr)
    }
}

// MCPServerTest.kt
class MCPServerTest {
    @Test
    fun testToolsListSchema() {
        val tools = MCPServer.getToolsList()
        assertEquals(5, tools.size)
        assertTrue(tools.any { it.name == "r2_analyze_file" })
    }
}
```

### 集成测试

```kotlin
// app/src/androidTest/java/com/r2aibridge/
@RunWith(AndroidJUnit4::class)
class ServiceTest {
    @Test
    fun testServiceStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, R2ServiceForeground::class.java)
        context.startService(intent)
        Thread.sleep(2000)
        // 验证服务正在运行
    }
}
```

### 运行测试
```bash
# 单元测试
./gradlew test

# 集成测试（需要连接设备）
./gradlew connectedAndroidTest
```

## 🐛 调试

### 查看日志

```bash
# 查看所有日志
adb logcat

# 过滤 R2 相关日志
adb logcat | grep -E "R2Native|R2Service|MCP"

# 查看原生崩溃
adb logcat | grep "DEBUG"

# 清除日志后重新开始
adb logcat -c && adb logcat
```

### 调试 JNI 代码

1. 在 Android Studio 中设置断点
2. 选择 "Debug 'app'" 而不是 "Run 'app'"
3. 在 C++ 代码中也可以设置断点
4. 使用 LLDB 调试器

### 调试网络请求

```bash
# 使用 curl 测试
curl -v http://192.168.1.100:5050/health

# 使用 Postman 或 Insomnia 测试 MCP 请求

# 查看网络流量
adb shell tcpdump -i wlan0 port 5050
```

## 📦 构建变体

### Debug 构建
```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

### Release 构建
```bash
./gradlew assembleRelease
# 需要配置签名
# 输出: app/build/outputs/apk/release/app-release.apk
```

### 配置签名

在 `app/build.gradle.kts` 中添加:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/keystore.jks")
            storePassword = "password"
            keyAlias = "key_alias"
            keyPassword = "key_password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ...
        }
    }
}
```

## 🚀 性能优化

### 1. 减少 APK 大小

**启用代码压缩**:
```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

**移除未使用的资源**:
```kotlin
android {
    buildTypes {
        release {
            isShrinkResources = true
        }
    }
}
```

### 2. 内存优化

**及时关闭 R2 会话**:
```kotlin
// 在完成分析后
R2Core.closeR2Core(corePtr)
r2Cores.remove(sessionId)
```

**限制并发会话数**:
```kotlin
object MCPServer {
    private const val MAX_SESSIONS = 10
    
    private fun executeAnalyzeFile(args: JsonObject): JsonElement {
        if (r2Cores.size >= MAX_SESSIONS) {
            return json.encodeToJsonElement(ToolCallResult(
                success = false,
                error = "Too many sessions, please close some sessions"
            ))
        }
        // ...
    }
}
```

### 3. 网络优化

**启用 HTTP/2**:
```kotlin
// 使用 Netty 引擎
embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
    // ...
}
```

**启用压缩**:
```kotlin
install(Compression) {
    gzip {
        priority = 1.0
    }
}
```

## 🔐 安全加固

### 1. 添加认证

```kotlin
// 在 MCPServer.kt 中
private const val API_TOKEN = "your-secret-token"

app.routing {
    post("/mcp") {
        val token = call.request.headers["Authorization"]
        if (token != "Bearer $API_TOKEN") {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        // 正常处理
    }
}
```

### 2. HTTPS 支持

```kotlin
val keyStoreFile = File("keystore.jks")
val keyStore = KeyStore.getInstance(keyStoreFile, "password".toCharArray())
val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
keyManagerFactory.init(keyStore, "password".toCharArray())

val sslContext = SSLContext.getInstance("TLS")
sslContext.init(keyManagerFactory.keyManagers, null, null)

embeddedServer(CIO, 
    applicationEngineEnvironment {
        connector {
            port = 8080
        }
        sslConnector(
            keyStore = keyStore,
            keyAlias = "alias",
            keyStorePassword = { "password".toCharArray() },
            privateKeyPassword = { "password".toCharArray() }
        ) {
            port = 8443
        }
        module {
            MCPServer.configure(this) { }
        }
    }
).start(wait = false)
```

### 3. 输入验证

```kotlin
private fun validateFilePath(path: String): Boolean {
    // 防止路径遍历攻击
    val normalized = File(path).canonicalPath
    return normalized.startsWith("/sdcard/") || normalized.startsWith("/storage/")
}

private suspend fun executeAnalyzeFile(args: JsonObject): JsonElement {
    val filePath = args["file_path"]?.jsonPrimitive?.content
        ?: return error("Missing file_path")
    
    if (!validateFilePath(filePath)) {
        return error("Invalid file path")
    }
    // ...
}
```

## 📊 性能监控

### 添加指标收集

```kotlin
// MetricsCollector.kt
object MetricsCollector {
    private val requestCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val totalResponseTime = AtomicLong(0)
    
    fun recordRequest(durationMs: Long, success: Boolean) {
        requestCount.incrementAndGet()
        if (!success) errorCount.incrementAndGet()
        totalResponseTime.addAndGet(durationMs)
    }
    
    fun getMetrics(): String {
        val count = requestCount.get()
        val errors = errorCount.get()
        val avgTime = if (count > 0) totalResponseTime.get() / count else 0
        
        return """
            Total Requests: $count
            Errors: $errors
            Avg Response Time: ${avgTime}ms
        """.trimIndent()
    }
}

// 在路由中使用
post("/metrics") {
    call.respondText(MetricsCollector.getMetrics())
}
```

## 🌍 国际化

### 添加多语言支持

```xml
<!-- res/values-zh/strings.xml -->
<resources>
    <string name="app_name">R2 AI 桥接</string>
    <string name="service_running">服务运行中</string>
</resources>

<!-- res/values-en/strings.xml -->
<resources>
    <string name="app_name">R2 AI Bridge</string>
    <string name="service_running">Service Running</string>
</resources>
```

## 🔄 持续集成

### GitHub Actions 示例

```yaml
# .github/workflows/android.yml
name: Android CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    - name: Build with Gradle
      run: ./gradlew assembleDebug
    - name: Run tests
      run: ./gradlew test
    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-debug
        path: app/build/outputs/apk/debug/app-debug.apk
```

## 📚 推荐阅读

- [Radare2 Book](https://book.rada.re/)
- [Ktor Documentation](https://ktor.io/docs/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Android NDK Documentation](https://developer.android.com/ndk)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)

## 🤝 贡献流程

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 编写代码和测试
4. 提交更改 (`git commit -m 'Add amazing feature'`)
5. 推送到分支 (`git push origin feature/amazing-feature`)
6. 创建 Pull Request
7. 等待代码审查

## 💡 开发技巧

- 使用 `adb shell run-as com.r2aibridge` 访问应用私有目录
- 使用 `adb shell am start -n com.r2aibridge/.MainActivity` 快速启动应用
- 使用 Android Profiler 监控内存和 CPU 使用
- 使用 Layout Inspector 调试 Compose UI
- 保持原生库和 JNI 接口同步更新
- 定期清理未使用的会话避免内存泄漏

---

**Happy Hacking!** 🚀
