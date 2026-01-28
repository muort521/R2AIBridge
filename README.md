# Radare2 AI Bridge Android App

> ✅ **构建状态**: 成功 | **APK**: `app/build/outputs/apk/debug/app-debug.apk`

将 Radare2 逆向引擎集成到 Android App，通过前台服务运行 Ktor HTTP 服务器，暴露 5 个核心 MCP 工具。

## 🎯 核心特性

- ✅ **命令行集成**: 通过 JNI 包装 Radare2 CLI（避免复杂的头文件依赖）
- ✅ **前台服务**: 后台运行 Ktor HTTP 服务器 (端口 5050)
- ✅ **MCP 协议**: JSON-RPC 2.0 实现，5 个 Radare2 工具
- ✅ **并发管理**: 16 桶锁机制，支持多客户端
- ✅ **Material 3 UI**: Jetpack Compose 现代界面
- ✅ **零头文件依赖**: 简化的 CMake 配置

## 项目结构

```
app/
├── src/main/
│   ├── cpp/                      # JNI 原生代码
│   │   ├── CMakeLists.txt        # CMake 构建配置
│   │   ├── native-lib.cpp        # JNI 实现
│   │   └── include/libr/         # Radare2 头文件
│   ├── java/com/r2aibridge/
│   │   ├── R2Core.kt             # JNI 接口
│   │   ├── MainActivity.kt       # 主界面
│   │   ├── service/
│   │   │   └── R2ServiceForeground.kt  # 前台服务
│   │   ├── mcp/
│   │   │   ├── MCPModels.kt      # MCP 数据模型
│   │   │   └── MCPServer.kt      # MCP 服务器
│   │   ├── concurrency/
│   │   │   └── R2ConcurrencyManager.kt # 并发管理
│   │   └── ui/theme/
│   │       └── Theme.kt          # Compose 主题
│   ├── jniLibs/arm64-v8a/        # Radare2 共享库
│   └── AndroidManifest.xml
└── build.gradle.kts
```

## 技术栈

- **Kotlin 1.9.22** - 主要编程语言
- **Jetpack Compose** - UI 框架
- **Ktor 3.0** - HTTP 服务器
- **Kotlinx Serialization** - JSON 序列化
- **JNI** - C++/Kotlin 桥接
- **CMake** - 原生代码构建
- **Radare2** - 逆向引擎

## MCP 工具列表

服务器在 `0.0.0.0:5050` 端点暴露以下 5 个 MCP 工具：

### 1. r2_analyze_file
分析二进制文件，加载文件并执行自动分析。

**参数:**
- `file_path` (string) - 要分析的文件路径

**返回:**
- 会话 ID 和文件基本信息

### 2. r2_execute_command
执行任意 Radare2 命令。

**参数:**
- `session_id` (string) - 会话 ID
- `command` (string) - Radare2 命令

**返回:**
- 命令执行结果

### 3. r2_disassemble
反汇编指定地址的代码。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 起始地址 (十六进制)
- `lines` (integer, optional) - 反汇编行数 (默认 10)

**返回:**
- 反汇编输出

### 4. r2_get_functions
获取二进制文件中的函数列表。

**参数:**
- `session_id` (string) - 会话 ID

**返回:**
- 函数列表

### 5. r2_close_session
关闭 Radare2 会话，释放资源。

**参数:**
- `session_id` (string) - 会话 ID

**返回:**
- 关闭确认

## API 端点

### POST /messages
MCP JSON-RPC 2.0 端点

**请求示例 - 列出工具:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list"
}
```

**请求示例 - 调用工具:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "r2_analyze_file",
    "arguments": {
      "file_path": "/sdcard/binary.elf"
    }
  }
}
```

### GET /health
健康检查端点，返回 "R2 MCP Server Running"

## 构建步骤

### 1. 准备环境
确保已安装：
- Android Studio Arctic Fox 或更高版本
- Android NDK 25+
- Gradle 8.2+
- JDK 17+

### 2. 配置 Radare2 库
将 Radare2 的 23 个 `.so` 库文件放置在：
```
app/src/main/jniLibs/arm64-v8a/
```

需要的库文件：
- libr_anal.so
- libr_arch.so
- libr_asm.so
- libr_bin.so
- libr_bp.so
- libr_config.so
- libr_cons.so
- libr_core.so
- libr_debug.so
- libr_egg.so
- libr_esil.so
- libr_flag.so
- libr_fs.so
- libr_io.so
- libr_lang.so
- libr_magic.so
- libr_main.so
- libr_muta.so
- libr_reg.so
- libr_search.so
- libr_socket.so
- libr_syscall.so
- libr_util.so

### 3. 构建项目
```bash
./gradlew assembleDebug
```

### 4. 安装到设备
```bash
./gradlew installDebug
```

或者在 Android Studio 中点击 "Run" 按钮。

## 使用方法

### 1. 启动应用
在 Android 设备上打开 "R2 AI Bridge" 应用。

### 2. 授予权限
应用会请求以下权限：
- 存储权限 (读取二进制文件)
- 网络权限
- 通知权限
- 前台服务权限

### 3. 启动服务
点击 "启动服务" 按钮，前台服务将在后台启动，通知栏会显示：
- 本地 IP 地址
- 端口号 (5050)
- 当前命令状态
- 停止按钮

### 4. 连接服务
从同一网络的设备访问：
```
http://<设备IP>:5050/messages
```

### 5. 发送 MCP 请求
使用任何 HTTP 客户端或 AI 工具发送 JSON-RPC 2.0 请求。

**示例 (使用 curl):**
```bash
# 列出所有工具
curl -X POST http://192.168.1.100:5050/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 分析文件
curl -X POST http://192.168.1.100:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":2,
    "method":"tools/call",
    "params":{
      "name":"r2_analyze_file",
      "arguments":{"file_path":"/sdcard/binary.elf"}
    }
  }'

# 执行命令
curl -X POST http://192.168.1.100:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"r2_execute_command",
      "arguments":{
        "session_id":"session_1234567890",
        "command":"pdf"
      }
    }
  }'
```

## 并发管理

应用使用基于文件路径哈希的桶锁机制（16 个桶）来管理并发访问：
- 相同文件的操作会被序列化
- 不同文件的操作可以并行执行
- 减少锁竞争，提高性能

## 前台服务

服务在前台运行，具有以下特性：
- **START_STICKY** - 系统资源允许时自动重启
- **持久通知** - 显示 IP、端口、当前命令
- **停止按钮** - 可从通知栏停止服务

## 开发注意事项

### JNI 调用
- 所有 R2Core 方法都是线程安全的
- 确保在使用完毕后调用 `r2_close_session`
- 错误会以字符串形式返回（以 "ERROR:" 开头）

### 内存管理
- RCore 实例通过 session_id 映射管理
- 未关闭的会话会导致内存泄漏
- 建议在完成分析后立即关闭会话

### 网络配置
- 服务器绑定到 `0.0.0.0:5050`
- 确保防火墙允许该端口
- 仅在受信任的网络中使用

## 故障排除

### 构建失败
- 检查 NDK 版本是否为 25+
- 确认所有 `.so` 文件存在于 `jniLibs/arm64-v8a/`
- 清理并重新构建: `./gradlew clean assembleDebug`

### 服务无法启动
- 检查所有权限是否已授予
- 查看 Logcat 输出查找错误信息
- 确认端口 5050 未被占用

### JNI 错误
- 检查 `System.loadLibrary("r2aibridge")` 是否成功
- 确认 CMakeLists.txt 中的库路径正确
- 查看原生日志: `adb logcat | grep R2Native`

## 许可证

本项目使用 Radare2，遵循 LGPL-3.0 许可证。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 相关链接

- [Radare2](https://github.com/radareorg/radare2)
- [Ktor](https://ktor.io/)
- [MCP Protocol](https://modelcontextprotocol.io/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
