# 🚀 Radare2-AI-Bridge 快速部署指南

> ✅ **状态**: 已构建并安装成功 | **协议**: 完整 MCP 2024-11-05 支持

---

## 📱 部署步骤

### 1. 安装 APK
```bash
# APK 位置
app/build/outputs/apk/debug/app-debug.apk

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 在设备上安装 Radare2

**选项 A: 使用 Termux（推荐）**
```bash
# 在 Termux 中执行
pkg install radare2
```

**选项 B: 从源码编译**
```bash
git clone https://github.com/radareorg/radare2
cd radare2
sys/android-build.sh
```

### 3. 启动应用

1. 打开 **R2AI Bridge** App
2. 授予所需权限：
   - ✅ 存储权限（MANAGE_EXTERNAL_STORAGE）
   - ✅ 通知权限
   - ✅ 网络权限
3. 点击 **"启动服务"** 按钮
4. 记录显示的设备 IP 地址（如：192.168.2.22）

### 4. 配置 AI 应用

在 Claude Desktop / Cline / 其他 MCP 客户端中配置：

```json
{
  "mcpServers": {
    "radare2": {
      "url": "http://192.168.2.22:5050",
      "transport": "sse"
    }
  }
}
```

或使用 HTTP endpoint：
```
POST http://192.168.2.22:5050/messages
```

---

## 🛠️ 可用工具（7个）

### 1. `r2_open_file` - 打开文件
```json
{
  "session_id": "session_xxx",
  "file_path": "/sdcard/Download/app.apk"
}
```

### 2. `r2_analyze_file` - 分析文件
```json
{
  "file_path": "/sdcard/Download/app.apk"
}
```
返回：Session ID + 文件信息

### 3. `r2_run_command` - 执行命令
```json
{
  "session_id": "session_xxx",
  "command": "afl"  // 列出所有函数
}
```

支持的命令示例：
- `afl` - 列出函数
- `iz` - 列出字符串
- `pdf @ main` - 反汇编 main 函数
- `px 100 @ 0x401000` - 查看内存十六进制
- `ii` - 列出导入表
- `ie` - 列出导出表

### 4. `r2_list_functions` - 列出函数
```json
{
  "session_id": "session_xxx"
}
```

### 5. `r2_decompile_function` - 反编译函数
```json
{
  "session_id": "session_xxx",
  "address": "main"  // 或 "0x401000"
}
```

### 6. `r2_disassemble` - 反汇编
```json
{
  "session_id": "session_xxx",
  "address": "0x401000",
  "lines": 20  // 可选，默认 10
}
```

### 7. `r2_close_session` - 关闭会话
```json
{
  "session_id": "session_xxx"
}
```

---

## 🔍 测试示例

### 完整 MCP 协议测试

#### 1. Initialize（初始化）
```bash
curl -X POST http://192.168.2.22:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {"protocolVersion": "2024-11-05"}
  }'
```

#### 2. Tools List（获取工具列表）
```bash
curl -X POST http://192.168.2.22:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
  }'
```

#### 3. Tools Call（调用工具）
```bash
# 分析文件
curl -X POST http://192.168.2.22:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "r2_analyze_file",
      "arguments": {
        "file_path": "/sdcard/Download/app.apk"
      }
    }
  }'

# 列出函数
curl -X POST http://192.168.2.22:5050/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "r2_list_functions",
      "arguments": {
        "session_id": "session_1234567890"
      }
    }
  }'
```

#### 4. Health Check（健康检查）
```bash
curl http://192.168.2.22:5050/health
```

#### 5. SSE（流式连接）
```bash
curl -N http://192.168.2.22:5050/sse
```

---

## 📊 架构特性

### ✅ 已实现功能

1. **完整 MCP 协议支持**
   - initialize / notifications/initialized
   - tools/list / tools/call
   - 标准 JSON-RPC 2.0 格式

2. **7 个核心工具**
   - 文件打开/分析
   - 命令执行
   - 函数列表/反编译
   - 反汇编
   - 会话管理

3. **企业级特性**
   - CORS 跨域支持
   - SSE 长连接
   - 详细日志系统
   - 内容格式自动修复
   - 错误处理与恢复

4. **性能优化**
   - 命令行模式（避免头文件依赖）
   - 前台服务（后台稳定运行）
   - 并发管理（16 桶锁）

### 🎯 技术栈

- **Kotlin**: 2.0.21
- **Ktor Server**: 3.0.0（CIO 引擎）
- **Jetpack Compose**: Material 3
- **JNI**: C++17
- **Radare2**: 命令行集成
- **Android**: SDK 34 / NDK 25.2.9519653

---

## 🐛 故障排查

### 问题 1: 无法连接到服务器
**解决**:
- 确保设备和电脑在同一网络
- 检查防火墙设置
- 查看 App 中显示的 IP 地址是否正确

### 问题 2: 命令返回 "ERROR: Failed to execute r2"
**解决**:
- 确保已安装 Radare2（`pkg install radare2` in Termux）
- 检查 r2 是否在 PATH 中（`which r2`）
- 查看 logcat 日志（`adb logcat | grep MCPServer`）

### 问题 3: 文件无法打开
**解决**:
- 确保文件路径正确
- 授予存储权限（MANAGE_EXTERNAL_STORAGE）
- 使用绝对路径（如：`/sdcard/Download/app.apk`）

### 问题 4: 会话丢失
**解决**:
- 记录 `r2_analyze_file` 返回的 session_id
- 会话在服务重启后会清空
- 重新分析文件以创建新会话

---

## 📝 日志查看

### 实时日志
```bash
adb logcat | grep -E "MCPServer|R2AIBridge"
```

### 关键日志
```
[HH:mm:ss.SSS] 🚀 MCP 服务器已启动
[HH:mm:ss.SSS] [App -> R2] tools/call (ID: abc12345)
[HH:mm:ss.SSS] 分析文件: /sdcard/app.apk
[HH:mm:ss.SSS] 执行命令: afl (Session: session_1234)
[HH:mm:ss.SSS] [R2 -> App] 2048 bytes
```

---

## 🔐 安全注意事项

1. **网络安全**: 服务器绑定到 `0.0.0.0`，局域网内所有设备可访问
2. **权限控制**: 无认证机制，建议仅在可信网络使用
3. **文件访问**: 可访问设备上的所有文件，请谨慎使用
4. **命令执行**: 支持任意 r2 命令，确保来源可信

---

## 📚 参考资源

- [Radare2 Book](https://book.rada.re/)
- [MCP Protocol Spec](https://spec.modelcontextprotocol.io/)
- [Ktor Documentation](https://ktor.io/docs/)
- [Android NDK Guide](https://developer.android.com/ndk)

---

**状态**: ✅ 生产就绪  
**版本**: 1.0 (2026-01-27)  
**架构**: 命令行集成模式  
**协议**: MCP 2024-11-05
