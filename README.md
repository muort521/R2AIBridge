# Radare2 AI Bridge Android App

> ✅ **构建状态**: 成功 | **APK**: `app/build/outputs/apk/debug/app-debug.apk`

将 Radare2 逆向引擎集成到 Android App，通过前台服务运行 Ktor HTTP 服务器，暴露 20 个核心 MCP 工具。

## 🎯 核心特性

- ✅ **命令行集成**: 通过 JNI 包装 Radare2 CLI（避免复杂的头文件依赖）
- ✅ **前台服务**: 后台运行 Ktor HTTP 服务器 (端口 5050)
- ✅ **MCP 协议**: JSON-RPC 2.0 实现，20 个 Radare2 + OS 工具
- ✅ **并发管理**: 16 桶锁机制，支持多客户端
- ✅ **Material 3 UI**: Jetpack Compose 现代界面
- ✅ **零头文件依赖**: 简化的 CMake 配置

## 项目结构

```
app/
├── src/main/
│   ├── cpp/                      # JNI 原生代码
│   │   ├── CMakeLists.txt        # CMake 构建配置
│   │   └── native-lib.cpp        # JNI 实现
│   ├── java/com/r2aibridge/
│   │   ├── R2Core.kt             # JNI 接口
│   │   ├── MainActivity.kt       # 主界面
│   │   ├── ShellUtils.kt         # Shell 工具类
│   │   ├── service/
│   │   │   └── R2ServiceForeground.kt  # 前台服务
│   │   ├── mcp/
│   │   │   ├── MCPModels.kt      # MCP 数据模型
│   │   │   ├── MCPServer.kt      # MCP 服务器
│   │   │   └── R2SessionManager.kt  # R2 会话管理
│   │   ├── concurrency/
│   │   │   └── R2ConcurrencyManager.kt # 并发管理
│   │   └── ui/theme/
│   │       └── Theme.kt          # Compose 主题
│   ├── jniLibs/arm64-v8a/        # Radare2 共享库
│   │   ├── libr_anal.so
│   │   ├── libr_arch.so
│   │   ├── libr_asm.so
│   │   ├── libr_bin.so
│   │   ├── libr_bp.so
│   │   ├── libr_config.so
│   │   ├── libr_cons.so
│   │   ├── libr_core.so
│   │   ├── libr_debug.so
│   │   ├── libr_egg.so
│   │   ├── libr_esil.so
│   │   ├── libr_flag.so
│   │   ├── libr_fs.so
│   │   ├── libr_io.so
│   │   ├── libr_lang.so
│   │   ├── libr_magic.so
│   │   ├── libr_main.so
│   │   ├── libr_muta.so
│   │   ├── libr_reg.so
│   │   ├── libr_search.so
│   │   ├── libr_socket.so
│   │   ├── libr_syscall.so
│   │   └── libr_util.so
│   ├── res/
│   │   ├── drawable/
│   │   │   └── ic_launcher.png
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

### 根目录文件

```
.
├── .github/workflows/            # GitHub Actions 工作流
├── .vscode/                      # VS Code 配置
├── app/                          # 主应用模块
├── .gitignore                    # Git 忽略文件
├── BUILD_STATUS.md               # 构建状态报告
├── BUILD_SUCCESS_REPORT.md       # 构建成功报告
├── DEPLOYMENT_CHECKLIST.md       # 部署检查清单
├── DEPLOYMENT_GUIDE.md           # 部署指南
├── DEVELOPER_GUIDE.md            # 开发者指南
├── MCP_EXAMPLES.md               # MCP 示例
├── PROJECT_SUMMARY.md            # 项目摘要
├── QUICKSTART.md                 # 快速开始指南
├── README.md                     # 项目说明
├── gradle.properties             # Gradle 属性
├── gradlew                       # Gradle 包装器 (Unix)
├── gradlew.bat                   # Gradle 包装器 (Windows)
└── local.properties.example      # 本地属性示例
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

服务器在 `0.0.0.0:5050` 端点暴露以下 28 个 MCP 工具：

### 1. r2_open_file
打开文件并执行基础分析 (默认 a 基础分析)。

**参数:**
- `file_path` (string) - 要分析的文件路径
- `auto_analyze` (boolean, optional) - 是否自动执行基础分析 (默认 true)

**返回:**
- 会话 ID 和文件基本信息

### 2. r2_analyze_file
执行深度分析 (aaa, 耗时较长)。

**参数:**
- `file_path` (string) - 要分析的文件路径

**返回:**
- 分析结果和文件信息

### 3. r2_analyze_target
智能分析策略 (精准下刀)。

**策略说明:**
- `basic` (aa): 基础分析，识别符号和入口点
- `blocks` (aab): 分析基本块结构，修复函数截断问题
- `calls` (aac): 递归分析函数调用，发现未识别的子函数
- `refs` (aar): 分析数据引用，识别字符串引用和全局变量
- `pointers` (aad): 分析数据段指针，用于C++虚表和跳转表恢复
- `full` (aaa): 全量深度分析（耗时极长，仅在小文件或必要时使用）

**参数:**
- `strategy` (string) - 分析策略模式
- `address` (string, optional) - 指定分析的起始地址或符号

**返回:**
- 分析结果和状态反馈

### 4. r2_run_command
执行 R2 命令 (通用)。

**参数:**
- `session_id` (string) - 会话 ID
- `command` (string) - Radare2 命令

**返回:**
- 命令执行结果

### 5. r2_config_manager
配置管理 (动态调整分析参数)。

**参数:**
- `session_id` (string) - 会话 ID
- `configs` (object) - 配置参数键值对

**返回:**
- 配置更新结果

### 6. r2_analysis_hints
分析提示 (手动修正分析错误)。

**参数:**
- `session_id` (string) - 会话 ID
- `hint_type` (string) - 提示类型
- `address` (string) - 目标地址
- `data` (string, optional) - 提示数据

**返回:**
- 提示应用结果

### 7. r2_list_functions
列出函数列表。

**参数:**
- `session_id` (string) - 会话 ID
- `filter` (string, optional) - 函数名过滤器
- `limit` (integer, optional) - 最大返回数量 (默认 500)

**返回:**
- 函数列表

### 8. r2_list_strings
列出字符串 (逆向第一步)。

**参数:**
- `session_id` (string) - 会话 ID
- `mode` (string, optional) - 搜索模式 ('data' 或 'all')
- `min_length` (integer, optional) - 最小字符串长度 (默认 5)

**返回:**
- 字符串列表

### 9. r2_get_xrefs
获取交叉引用 (逻辑追踪)。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 目标地址或函数名
- `direction` (string, optional) - 引用方向 ('to' 或 'from')
- `limit` (integer, optional) - 最大返回数量 (默认 50)

**返回:**
- 交叉引用列表

### 10. r2_manage_xrefs
管理交叉引用 (手动修复)。

**参数:**
- `session_id` (string) - 会话 ID
- `action` (string) - 操作类型 ('add' 或 'remove')
- `from` (string) - 源地址
- `to` (string) - 目标地址

**返回:**
- 操作结果

### 11. r2_get_info
获取文件详细信息。

**参数:**
- `session_id` (string) - 会话 ID
- `detailed` (boolean, optional) - 是否显示详细信息 (默认 false)

**返回:**
- 文件信息

### 12. r2_decompile_function
反编译函数。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 函数地址

**返回:**
- 反编译代码

### 13. r2_disassemble
反汇编代码。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 起始地址 (十六进制)
- `lines` (integer, optional) - 反汇编行数 (默认 10)

**返回:**
- 反汇编输出

### 14. r2_test
测试 R2 库状态 (诊断)。

**参数:**
- 无

**返回:**
- 测试结果

### 15. r2_close_session
关闭会话。

**参数:**
- `session_id` (string) - 会话 ID

**返回:**
- 关闭确认

### 16. os_list_dir
列出目录内容 (支持 Root)。

**参数:**
- `path` (string) - 目标文件夹的绝对路径

**返回:**
- 目录内容列表，包含文件类型和大小

### 17. os_read_file
读取文件内容 (支持 Root)。

**参数:**
- `path` (string) - 目标文件的绝对路径

**返回:**
- 文件内容（自动截断大文件以防 OOM）

### 18. termux_command
Termux 环境命令 (AI 沙盒)。

**参数:**
- `command` (string) - 要执行的命令

**返回:**
- 命令执行结果

### 19. termux_save_script
保存代码 (赋权/所有者)。

**参数:**
- `script` (string) - 脚本内容
- `path` (string) - 保存路径

**返回:**
- 保存结果

### 20. sqlite_query
SQL 查询 (读取私有数据库)。

**参数:**
- `db_path` (string) - 数据库文件路径
- `query` (string) - SQL 查询语句

**返回:**
- 查询结果

### 21. read_logcat
读取 Android 系统日志 (Logcat)。

**参数:**
- `filter` (string, optional) - 日志过滤器
- `lines` (integer, optional) - 读取行数 (默认 100)

**返回:**
- 日志内容

### 22. rename_function
智能重命名函数 (语义理解)。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 函数地址
- `new_name` (string) - 新函数名

**返回:**
- 重命名结果

### 23. simulate_execution
模拟执行 (ESIL 沙盒)。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 起始地址
- `steps` (integer, optional) - 执行步数 (默认 100)

**返回:**
- 执行结果和状态

### 24. add_knowledge_note
持久化知识库 (记录重要发现)。

**参数:**
- `note` (string) - 知识库笔记内容
- `category` (string, optional) - 分类标签

**返回:**
- 保存结果

### 25. batch_decrypt_strings
批量解密字符串对抗混淆。

**参数:**
- `session_id` (string) - 会话 ID
- `method` (string) - 解密方法
- `params` (object, optional) - 解密参数

**返回:**
- 解密结果

### 26. scan_crypto_signatures
扫描加密签名识别算法。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string, optional) - 扫描起始地址

**返回:**
- 加密算法识别结果

### 27. apply_hex_patch
对指定地址应用二进制 Patch。

**参数:**
- `session_id` (string) - 会话 ID
- `address` (string) - 目标地址
- `patch` (string) - 十六进制补丁数据

**返回:**
- 补丁应用结果

### 28. find_jni_methods
列出所有的 JNI 接口函数。

**参数:**
- `session_id` (string) - 会话 ID

**返回:**
- JNI 方法列表

## API 端点

### POST /mcp
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
http://<设备IP>:5050/mcp
```

### 5. 发送 MCP 请求
使用任何 HTTP 客户端或 AI 工具发送 JSON-RPC 2.0 请求。

**示例 (使用 curl):**
```bash
# 列出所有工具
curl -X POST http://192.168.1.100:5050/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 分析文件
curl -X POST http://192.168.1.100:5050/mcp \
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
curl -X POST http://192.168.1.100:5050/mcp \
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
