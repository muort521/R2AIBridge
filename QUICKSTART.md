# 快速开始指南

## 5 分钟快速部署

### 1️⃣ 克隆或下载项目
```bash
git clone <repository-url>
cd Radare2
```

### 2️⃣ 准备 Radare2 库文件
确保以下 23 个 `.so` 文件位于 `app/src/main/jniLibs/arm64-v8a/` 目录：

```
libr_anal.so    libr_arch.so     libr_asm.so      libr_bin.so
libr_bp.so      libr_config.so   libr_cons.so     libr_core.so
libr_debug.so   libr_egg.so      libr_esil.so     libr_flag.so
libr_fs.so      libr_io.so       libr_lang.so     libr_magic.so
libr_main.so    libr_muta.so     libr_reg.so      libr_search.so
libr_socket.so  libr_syscall.so  libr_util.so
```

**获取方式:**
- 从 Radare2 官方编译（推荐）
- 从 Termux 中提取: `cp /data/data/com.termux/files/usr/lib/libr_*.so`

### 3️⃣ 在 Android Studio 中打开项目
```bash
# 确保已安装 Android Studio
# File → Open → 选择项目根目录
```

### 4️⃣ 同步 Gradle 依赖
Android Studio 会自动提示同步，或手动点击 "Sync Project with Gradle Files"

### 5️⃣ 构建并运行
```bash
# 方式 1: Android Studio
点击 Run 按钮 (绿色三角形)

# 方式 2: 命令行
./gradlew installDebug
```

### 6️⃣ 在设备上使用

1. **打开应用** "R2 AI Bridge"
2. **授予所有权限** (存储、网络、通知)
3. **点击"启动服务"** 
4. **查看通知栏** 记录显示的 IP 地址，例如 `192.168.1.100:3000`

### 7️⃣ 测试 MCP 服务

```bash
# 健康检查
curl http://192.168.1.100:3000/health

# 列出工具
curl -X POST http://192.168.1.100:3000/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 分析文件 (先将测试文件放到设备)
adb push test_binary /sdcard/Download/test_binary

curl -X POST http://192.168.1.100:3000/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":2,
    "method":"tools/call",
    "params":{
      "name":"r2_analyze_file",
      "arguments":{"file_path":"/sdcard/Download/test_binary"}
    }
  }' | jq .
```

## 🎉 完成！

现在你可以通过 MCP 协议使用 AI 工具调用 Radare2 进行逆向分析了！

## 📚 下一步

- 阅读 [README.md](README.md) 了解详细功能
- 查看 [MCP_EXAMPLES.md](MCP_EXAMPLES.md) 学习更多用法
- 参考 [PROJECT_SUMMARY.md](PROJECT_SUMMARY.md) 了解架构设计

## ⚠️ 常见问题

### Q: 构建失败 "CMake Error"
**A:** 检查 NDK 是否正确安装，版本应为 25+
```bash
# 在 Android Studio
Tools → SDK Manager → SDK Tools → 勾选 NDK (Side by side)
```

### Q: 应用闪退
**A:** 检查 Logcat 日志，确保所有 .so 文件都存在

### Q: 找不到设备 IP
**A:** 确保设备连接到 WiFi，可以在应用中查看或使用 `adb shell ip addr`

### Q: 端口 3000 被占用
**A:** 修改 `R2ServiceForeground.kt` 中的 `PORT` 常量

### Q: 权限被拒绝
**A:** 在设备设置中手动授予存储权限，或重新安装应用

## 🆘 需要帮助？

- 查看完整文档: [README.md](README.md)
- 提交 Issue: GitHub Issues
- 查看日志: `adb logcat | grep -E "R2Native|R2Service|MCP"`
