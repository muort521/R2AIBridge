# 部署检查清单

在将应用部署到生产环境之前，请确保完成以下检查项。

## ✅ 必须完成项

### 📦 构建配置

- [ ] **Radare2 库文件已就位**
  - 23 个 `.so` 文件位于 `app/src/main/jniLibs/arm64-v8a/`
  - 验证命令: `ls -la app/src/main/jniLibs/arm64-v8a/ | wc -l` (应显示 23)

- [ ] **本地配置文件**
  - 复制 `local.properties.example` 为 `local.properties`
  - 设置正确的 Android SDK 路径

- [ ] **Gradle 同步成功**
  - 在 Android Studio 中点击 "Sync Project with Gradle Files"
  - 无错误或警告

- [ ] **CMake 构建成功**
  - 检查 Build 输出无 CMake 错误
  - 确认所有 23 个库都被正确链接

### 🔧 代码检查

- [ ] **JNI 接口测试**
  - `R2Core.initR2Core()` 返回非零指针
  - `R2Core.executeCommand()` 能正确执行命令
  - `R2Core.closeR2Core()` 正常释放资源

- [ ] **MCP 工具验证**
  - 所有 20 个工具都在 `tools/list` 中返回
  - 每个工具的 JSON Schema 正确
  - 工具调用能正确返回结果或错误

- [ ] **并发管理测试**
  - 多个并发请求不会导致崩溃
  - 文件锁正确工作
  - 无死锁情况

- [ ] **前台服务功能**
  - 服务能正常启动和停止
  - 通知正确显示 IP 和端口
  - START_STICKY 机制工作正常

### 🎨 UI 检查

- [ ] **权限请求**
  - 所有必需权限都能正常请求
  - 权限被拒绝时有适当提示
  - 重新授权后功能正常

- [ ] **界面响应**
  - 启动/停止按钮工作正常
  - IP 地址正确显示
  - 历史记录正常更新

- [ ] **主题适配**
  - 深色/浅色模式都能正常显示
  - 无 UI 元素重叠或错位

### 🌐 网络功能

- [ ] **HTTP 服务器**
  - 能绑定到 0.0.0.0:5050
  - `/health` 端点返回正确
  - `/mcp` 端点处理 JSON-RPC 2.0 请求

- [ ] **MCP 协议**
  - `tools/list` 返回完整工具列表
  - `tools/call` 正确调用工具
  - 错误响应格式正确（JSON-RPC 2.0）

- [ ] **IP 地址获取**
  - WiFi 连接时能正确获取 IP
  - 移动网络时的处理正确
  - 无网络时显示 "未知"

### 🔒 安全检查

- [ ] **文件访问**
  - 只能访问允许的目录
  - 防止路径遍历攻击
  - 错误消息不泄露敏感信息

- [ ] **资源管理**
  - 无内存泄漏
  - R2Core 正确释放
  - 会话超时自动清理

- [ ] **网络安全**
  - 仅在受信任网络使用的警告
  - （可选）添加认证机制
  - （可选）HTTPS 支持

## ⚙️ 测试项

### 单元测试

- [ ] **JNI 测试**
  ```bash
  ./gradlew test
  ```

- [ ] **MCP 模型测试**
  - 序列化/反序列化正确
  - JSON Schema 验证

### 集成测试

- [ ] **端到端测试**
  ```bash
  ./gradlew connectedAndroidTest
  ```

- [ ] **手动测试场景**
  - [ ] 分析小文件 (< 1MB)
  - [ ] 分析大文件 (> 10MB)
  - [ ] 执行多个命令
  - [ ] 并发多个会话
  - [ ] 长时间运行 (> 1 小时)
  - [ ] 网络中断后恢复
  - [ ] 设备休眠后唤醒

### 性能测试

- [ ] **响应时间**
  - 简单命令 < 100ms
  - 分析文件 < 5s (小文件)
  - 并发请求无明显延迟

- [ ] **资源使用**
  - 内存使用 < 200MB (空闲)
  - CPU 使用 < 30% (处理请求时)
  - 电池消耗合理

- [ ] **稳定性**
  - 连续运行 24 小时无崩溃
  - 处理 1000+ 请求无异常
  - 错误恢复正常

## 📱 设备测试

### 测试设备清单

- [ ] **至少一台物理设备**
  - Android 8.0+ (API 26+)
  - ARM64-v8a 架构
  - 连接到稳定 WiFi

- [ ] **多种设备型号**（推荐）
  - 低端设备 (2GB RAM)
  - 中端设备 (4GB RAM)
  - 高端设备 (8GB+ RAM)

- [ ] **不同 Android 版本**
  - Android 8.0 (API 26)
  - Android 10.0 (API 29)
  - Android 12.0 (API 31)
  - Android 14.0 (API 34)

## 📝 文档检查

- [ ] **README.md**
  - 准确的安装步骤
  - 正确的使用示例
  - 故障排除信息完整

- [ ] **MCP_EXAMPLES.md**
  - 所有示例请求可执行
  - 响应格式正确
  - curl 命令有效

- [ ] **QUICKSTART.md**
  - 5 分钟内可完成
  - 步骤清晰无歧义
  - 链接都有效

## 🚀 构建检查

### Debug 构建

- [ ] **构建成功**
  ```bash
  ./gradlew clean assembleDebug
  ```

- [ ] **APK 签名正确**
  ```bash
  jarsigner -verify -verbose -certs app/build/outputs/apk/debug/app-debug.apk
  ```

- [ ] **APK 可安装**
  ```bash
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

### Release 构建（生产）

- [ ] **配置签名证书**
  - 创建或获取发布密钥
  - 在 `build.gradle.kts` 中配置
  - 密钥安全存储

- [ ] **启用代码混淆**
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
  - ProGuard 规则正确

- [ ] **构建 Release APK**
  ```bash
  ./gradlew assembleRelease
  ```

- [ ] **APK 优化**
  - 大小合理 (< 50MB)
  - 无未使用资源
  - 代码已混淆

## 📊 质量指标

- [ ] **代码覆盖率** (可选)
  - 单元测试覆盖 > 60%
  - 关键路径覆盖 100%

- [ ] **静态分析** (可选)
  ```bash
  ./gradlew lint
  ```
  - 无 Error 级别问题
  - Warning 已审查

- [ ] **性能基准**
  - 启动时间 < 3s
  - 服务启动 < 1s
  - 首次请求响应 < 500ms

## 🔄 发布准备

### Google Play Store（如需发布）

- [ ] **应用商店信息**
  - 应用名称
  - 简短描述
  - 详细描述
  - 应用图标 (512x512)
  - 特色图片

- [ ] **截图准备**
  - 手机截图 (至少 2 张)
  - 平板截图 (可选)
  - 多语言版本 (可选)

- [ ] **隐私政策**
  - 隐私政策 URL
  - 数据收集说明
  - 权限使用说明

### 其他分发渠道

- [ ] **APK 直接分发**
  - 签名的 Release APK
  - 版本号和变更日志
  - 安装说明

- [ ] **GitHub Release**
  - 创建 Release Tag
  - 上传 APK
  - 编写 Release Notes

## ✅ 最终检查

- [ ] **版本信息**
  - `versionCode` 递增
  - `versionName` 符合语义化版本
  - 更新日志编写完成

- [ ] **法律合规**
  - 开源许可证声明
  - 第三方库许可证
  - Radare2 LGPL-3.0 遵守

- [ ] **备份**
  - 签名密钥已备份
  - 源代码已提交
  - 文档已同步

- [ ] **回滚计划**
  - 保留上一版本 APK
  - 准备回滚步骤
  - 用户通知计划

## 📞 支持准备

- [ ] **问题跟踪**
  - GitHub Issues 已开启
  - Issue 模板已创建
  - 响应流程已定义

- [ ] **用户支持**
  - FAQ 文档
  - 联系方式
  - 社区渠道 (可选)

---

## 🎉 部署！

当所有必须项都勾选完成后，你就可以部署应用了！

```bash
# 最终构建命令
./gradlew clean assembleRelease

# 输出位置
# app/build/outputs/apk/release/app-release.apk
```

**祝部署顺利！** 🚀

---

**最后更新**: 2026-01-27  
**检查清单版本**: v1.0
