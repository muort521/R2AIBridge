package com.r2aibridge.mcp

import android.util.Log
import com.r2aibridge.R2Core
import com.r2aibridge.ShellUtils
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object MCPServer {
    
    private const val TAG = "R2AI"
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        coerceInputValues = true
    }

    private fun logInfo(msg: String) {
        val timestamp = dateFormat.format(Date())
        val logMsg = "[$timestamp] $msg"
        Log.i(TAG, logMsg)
        println(logMsg)
    }

    private fun logError(msg: String, error: String? = null) {
        val timestamp = dateFormat.format(Date())
        val logMsg = "[$timestamp] ⚠️ $msg" + (error?.let { ": $it" } ?: "")
        Log.e(TAG, logMsg)
        println(logMsg)
    }

    /**
     * 清洗和截断 Radare2 的输出，防止 AI 崩溃
     * @param raw 原始输出
     * @param maxLines 最大行数
     * @param maxChars 最大字符数
     * @param filterGarbage 是否过滤垃圾段 (如 .eh_frame)
     * @return 清洗后的输出
     */
    private fun sanitizeOutput(
        raw: String, 
        maxLines: Int = 500, 
        maxChars: Int = 16000,
        filterGarbage: Boolean = false
    ): String {
        if (raw.isBlank()) return "(Empty Output)"

        var output = raw
        
        // 1. 过滤垃圾段 (如 .eh_frame, .text 中的乱码)
        if (filterGarbage) {
            output = output.lineSequence()
                .filter { line ->
                    !line.contains(".eh_frame") && 
                    !line.contains(".gcc_except_table") &&
                    !line.contains("libunwind")
                }
                .joinToString("\n")
        }
        
        // 2. 字符数截断
        if (output.length > maxChars) {
            logInfo("输出超过 $maxChars 字符，已截断")
            return output.take(maxChars) + "\n\n[⛔ SYSTEM: 输出超过 $maxChars 字符，已强制截断。请缩小分析范围。]"
        }
        
        // 3. 行数截断
        val lines = output.lines()
        if (lines.size > maxLines) {
            logInfo("输出超过 $maxLines 行 (共 ${lines.size} 行)，已截断")
            return lines.take(maxLines).joinToString("\n") + 
                   "\n\n[⛔ SYSTEM: 输出超过 $maxLines 行 (共 ${lines.size} 行)，已截断。请使用过滤参数缩小范围。]"
        }

        return output
    }

    /**
     * 检查设备是否有 Root 权限
     */
    private fun hasRootPermission(): Boolean {
        return try {
            logInfo("检查 Root 权限...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo test"))
            val exitCode = process.waitFor()
            val hasPermission = exitCode == 0
            logInfo("Root 权限检查结果: $hasPermission (exitCode: $exitCode)")
            hasPermission
        } catch (e: Exception) {
            logError("Root 权限检查异常", e.message)
            false
        }
    }

    /**
     * Root 复制逻辑：尝试打开文件 -> 失败 -> 强行 Root 复制到缓存 777 -> 打开副本
     * @param originalPath 原始文件路径
     * @return 成功返回副本路径，失败返回 null
     */
    private fun tryRootCopy(originalPath: String): String? {
        // 先检查是否有 Root 权限
        if (!hasRootPermission()) {
            logError("设备未获得 Root 权限，无法执行 Root 复制", "文件: $originalPath")
            return null
        }

        try {
            val originalFile = File(originalPath)
            if (!originalFile.exists()) {
                logError("原始文件不存在，无法复制", originalPath)
                return null
            }

            // 创建缓存目录
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "r2_root_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // 生成副本路径
            val fileName = originalFile.name
            val copyPath = File(cacheDir, "${System.currentTimeMillis()}_${fileName}").absolutePath

            logInfo("尝试 Root 复制文件: $originalPath -> $copyPath")

            // 执行 Root 复制命令
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$originalPath' '$copyPath' && chmod 777 '$copyPath'"))
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // 验证副本是否存在且可读
                val copyFile = File(copyPath)
                if (copyFile.exists() && copyFile.canRead()) {
                    logInfo("Root 复制成功: $copyPath")
                    return copyPath
                } else {
                    logError("Root 复制后文件不存在或不可读", copyPath)
                }
            } else {
                val error = process.errorStream.bufferedReader().readText()
                logError("Root 复制失败", "exitCode=$exitCode, error=$error")
            }
        } catch (e: Exception) {
            logError("Root 复制异常", e.message)
        }

        return null
    }

    /**
     * 清理所有 Root 复制的副本文件
     */
    fun cleanupRootCopies() {
        try {
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "r2_root_cache")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val files = cacheDir.listFiles()
                if (files != null) {
                    var deletedCount = 0
                    for (file in files) {
                        if (file.isFile && file.delete()) {
                            deletedCount++
                        }
                    }
                    logInfo("已清理 $deletedCount 个 Root 复制副本文件")
                }
            }
        } catch (e: Exception) {
            logError("清理 Root 复制副本失败", e.message)
        }
    }

    fun configure(app: Application, onLogEvent: (String) -> Unit) {
        app.install(ContentNegotiation) {
            json(json)
        }

        // CORS 支持
        app.intercept(ApplicationCallPipeline.Plugins) {
            call.response.header("Access-Control-Allow-Origin", "*")
            call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            call.response.header("Access-Control-Allow-Headers", "*")
            
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                finish()
            }
        }

        app.routing {
            // MCP 根端点 - 服务信息
            get("/") {
                val info = buildJsonObject {
                    put("name", "Radare2 MCP Server")
                    put("version", "1.0")
                    put("status", "running")
                    put("endpoints", JsonArray(listOf(
                        JsonPrimitive("/messages - Standard MCP endpoint"),
                        JsonPrimitive("/health - Health check")
                    )))
                }
                
                call.respondText(
                    text = json.encodeToString(JsonObject.serializer(), info),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                )
            }

            post("/messages") {
                var requestId: JsonElement? = null
                var method = "unknown"

                try {
                    val requestBody = call.receiveText()

                    if (requestBody.isBlank()) {
                        val errorObj = buildJsonObject {
                            put("code", -32700)
                            put("message", "Empty request body")
                        }
                        val errorResp = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", JsonNull)
                            put("error", errorObj)
                        }.toString()

                        call.respondText(
                            text = errorResp,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.BadRequest
                        )
                        return@post
                    }

                    val request = json.decodeFromString<MCPRequest>(requestBody)
                    requestId = request.id
                    method = request.method

                    val idStr = when (val id = request.id) {
                        is JsonPrimitive -> id.content.take(8)
                        else -> "null"
                    }

                    val clientIp = call.request.local.remoteHost
                    val logMsg = "📥 ${request.method} | $clientIp | ID:$idStr"
                    logInfo("[App -> R2] ${request.method} (ID: $idStr)")
                    onLogEvent(logMsg)

                    // 处理通知（不需要响应）
                    if (method == "notifications/initialized") {
                        logInfo("客户端已初始化")
                        call.respond(HttpStatusCode.NoContent)
                        return@post
                    }

                    val result = when (request.method) {
                        "initialize" -> handleInitialize(request.params)
                        "ping" -> handlePing()
                        "tools/list" -> handleToolsList()
                        "tools/call" -> {
                            val toolName = request.params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                            val toolLogMsg = "🔧 工具调用: $toolName | $clientIp"
                            onLogEvent(toolLogMsg)
                            handleToolCall(request.params, onLogEvent)
                        }
                        else -> {
                            logError("未知方法", method)
                            val errorObj = buildJsonObject {
                                put("code", -32601)
                                put("message", "Method not found: ${request.method}")
                            }
                            val errorResp = buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", request.id ?: JsonNull)
                                put("error", errorObj)
                            }.toString()

                            call.respondText(
                                text = errorResp,
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.OK
                            )
                            return@post
                        }
                    }

                    // 🔥 手动构建响应 JSON，强制包含 jsonrpc: "2.0"
                    val responseJson = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", request.id ?: JsonNull)
                        put("result", result)
                    }.toString()

                    // 记录响应
                    if (responseJson.length < 500) {
                        logInfo("[R2 -> App] ${responseJson.take(200)}")
                    } else {
                        logInfo("[R2 -> App] ${responseJson.length} bytes")
                    }

                    // 设置响应头
                    call.response.header(HttpHeaders.CacheControl, "no-cache")

                    call.respondText(
                        text = responseJson,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    logError("处理请求失败", e.message)
                    onLogEvent("⚠️ 错误: ${e.message}")

                    val errorObj = buildJsonObject {
                        put("code", -32603)
                        put("message", "Internal error: ${e.message}")
                    }
                    val errorResp = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", requestId ?: JsonNull)
                        put("error", errorObj)
                    }.toString()

                    call.respondText(
                        text = errorResp,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
            }

            // 处理 OPTIONS 请求（CORS 预检）
            options("/*") {
                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
                call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type, Cache-Control")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
            }

            get("/health") {
                logInfo("健康检查")
                val stats = R2SessionManager.getStats()
                call.respondText(
                    "R2 MCP Server Running\n" +
                    "Active Sessions: ${R2SessionManager.getSessionCount()}\n" +
                    "Session Stats: $stats",
                    ContentType.Text.Plain
                )
            }
        }
        
        logInfo("🚀 MCP 服务器已启动")
    }

    /**
     * 处理 ping 方法 - 连接测试
     */
    private fun handlePing(): JsonElement {
        logInfo("收到 ping 请求")
        return buildJsonObject {
            put("message", "pong")
            put("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * 处理 initialize 方法 - 协议版本协商
     */
    private fun handleInitialize(params: JsonObject?): JsonElement {
        // 1. 获取客户端发来的协议版本
        val clientProtocolVersion = params?.get("protocolVersion")?.jsonPrimitive?.content
        
        // 2. 协商逻辑：如果客户端提供了版本，就原样返回（表示支持）；否则使用默认值
        val negotiatedVersion = clientProtocolVersion ?: "2024-11-05"
        
        logInfo("协议协商: 客户端=$clientProtocolVersion -> 最终使用=$negotiatedVersion")
        
        return buildJsonObject {
            // 必须回传协商后的版本号
            put("protocolVersion", negotiatedVersion)
            
            // 必须声明 capabilities (能力)，否则客户端不会请求工具列表
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", false) // 设为 true 可以在工具列表变更时通知客户端
                })
                // 如果将来支持 logging 或 resources，也在这里添加
            })
            
            // 服务器信息
            put("serverInfo", buildJsonObject {
                put("name", "Radare2 MCP Server")
                put("version", "1.0")
            })
        }
    }

    private fun handleToolsList(): JsonElement {
        val tools = listOf(
            createToolSchema(
                "r2_open_file",
                "🚪 [会话管理] 打开二进制文件。默认执行基础分析 (aa) 以快速识别函数。注意：对于大型文件 (>10MB)，强烈建议将 auto_analyze 设为 false 以免超时。如需深度分析，可后续调用 r2_analyze_file 或使用 r2_run_command 执行 'aaa'。",
                mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "二进制文件的完整路径"),
                    "session_id" to mapOf("type" to "string", "description" to "可选:使用现有会话 ID,如果不提供则自动创建"),
                    "auto_analyze" to mapOf("type" to "boolean", "description" to "是否自动执行基础分析 (aa 命令)。默认为 true。对于大文件 (>10MB) 请设为 false。", "default" to true)
                ),
                listOf("file_path")
            ),
            createToolSchema(
                "r2_analyze_file",
                "⚡ [深度分析] 一次性执行深度分析 (aaa) 并自动释放资源。注意：aaa 会耗时较长，仅用于需要完整分析的场景。对于大文件，建议使用 r2_open_file(auto_analyze=false) + r2_run_command 手动分析。",
                mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "二进制文件的完整路径")
                ),
                listOf("file_path")
            ),
            createToolSchema(
                "r2_run_command",
                "⚙️ [通用命令] 在指定会话中执行任意 Radare2 命令。支持所有 r2 命令，如：pdf（反汇编函数）、afl（列出函数）、iz（列出字符串）、px（十六进制查看）等。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "command" to mapOf("type" to "string", "description" to "Radare2 命令，例如：'pdf @ main', 'afl', 'iz', 'px 100 @ 0x401000'")
                ),
                listOf("session_id", "command")
            ),
            createToolSchema(
                "r2_list_functions",
                "📋 [函数分析] 列出二进制文件中的已识别函数。使用 'afl' 命令。可通过 filter 过滤函数名，防止输出过多。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "filter" to mapOf("type" to "string", "description" to "可选:函数名过滤器（如 'sym.Java' 只显示 Java 相关函数）", "default" to ""),
                    "limit" to mapOf("type" to "integer", "description" to "最大返回数量（默认 500）", "default" to 500)
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_list_strings",
                "📝 [逆向第一步] 列出二进制文件中的字符串。用于快速定位关键逻辑。默认使用 'iz'（数据段）并自动过滤 .eh_frame/.text 等垃圾段。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "mode" to mapOf("type" to "string", "description" to "搜索模式: 'data'（默认，iz，仅数据段）或 'all'（izz，全盘搜索）", "default" to "data"),
                    "min_length" to mapOf("type" to "integer", "description" to "最小字符串长度（默认 5，过滤短字符串）", "default" to 5)
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_get_xrefs",
                "🔗 [逻辑追踪必备] 获取指定地址/函数的交叉引用。查找 \"谁调用了它\"（axt）或 \"它调用了谁\"（axf）。默认限制返回 50 个引用，防止通用函数（如 malloc）的引用风暴。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "address" to mapOf("type" to "string", "description" to "目标地址或函数名（如: 0x401000 或 main）"),
                    "direction" to mapOf("type" to "string", "description" to "引用方向: 'to'（默认，axt，谁调用了它）或 'from'（axf，它调用了谁）", "default" to "to"),
                    "limit" to mapOf("type" to "integer", "description" to "最大返回数量（默认 50）", "default" to 50)
                ),
                listOf("session_id", "address")
            ),
            createToolSchema(
                "r2_get_info",
                "ℹ️ [环境感知] 获取二进制文件的详细信息。包括架构（32/64位）、平台（ARM/x86）、文件类型（ELF/DEX）等。帮助 AI 决定分析策略。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "detailed" to mapOf("type" to "boolean", "description" to "是否显示详细信息（iI），默认 false（i）", "default" to false)
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_decompile_function",
                "🔍 [代码分析] 反编译指定地址的函数为伪代码。使用 'pdc' 命令，将汇编代码转换为类 C 语言的可读代码。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "address" to mapOf("type" to "string", "description" to "函数地址（十六进制格式，如：0x401000 或 main）")
                ),
                listOf("session_id", "address")
            ),
            createToolSchema(
                "r2_disassemble",
                "📜 [汇编分析] 反汇编指定地址的代码。使用 'pd' 命令显示汇编指令。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "address" to mapOf("type" to "string", "description" to "起始地址（十六进制格式，如：0x401000）"),
                    "lines" to mapOf("type" to "integer", "description" to "反汇编行数（默认10行）", "default" to 10)
                ),
                listOf("session_id", "address")
            ),
            createToolSchema(                "r2_test",
                "🧪 [诊断工具] 测试 Radare2 库是否正常工作。返回版本信息和基本功能测试结果。",
                mapOf(),
                listOf()
            ),
            createToolSchema(                "r2_close_session",
                "🔒 [会话管理] 关闭指定的 Radare2 会话，释放资源。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "要关闭的会话 ID")
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_analyze_target",
                "🎯 [智能分析] 执行特定的 Radare2 递归分析策略。请根据分析需求选择最轻量级的策略，避免盲目使用全量分析。\n" +
                "策略说明：\n" +
                "- 'basic' (aa): 基础分析，识别符号和入口点。\n" +
                "- 'blocks' (aab): 仅分析当前函数或地址的基本块结构（修复函数截断问题）。\n" +
                "- 'calls' (aac): 递归分析函数调用目标（发现未识别的子函数）。\n" +
                "- 'refs' (aar): 分析数据引用（识别字符串引用、全局变量）。\n" +
                "- 'pointers' (aad): 分析数据段指针（用于 C++ 虚表、跳转表恢复）。\n" +
                "- 'full' (aaa): 全量深度分析（耗时极长，仅在小文件或必要时使用）。",
                mapOf(
                    "strategy" to mapOf("type" to "string", "enum" to listOf("basic", "blocks", "calls", "refs", "pointers", "full"), "description" to "分析策略模式"),
                    "address" to mapOf("type" to "string", "description" to "可选：指定分析的起始地址或符号（例如 '0x00401000' 或 'sym.main'）。如果不填，默认分析全局或当前位置。")
                ),
                listOf("strategy")
            ),
            createToolSchema(
                "r2_manage_xrefs",
                "🔗 [交叉引用管理] 管理代码和数据的交叉引用(Xrefs)。用于查询'谁调用了函数'、'字符串在哪里被使用'，或手动修复缺失的引用关系。\n" +
                "操作类型说明：\n" +
                "- 'list_to' (axt): 查询引用了目标地址的位置（例如：谁调用了这个函数？）。\n" +
                "- 'list_from' (axf): 查询目标地址引用了哪些位置（例如：这个函数里调用了谁？）。\n" +
                "- 'add_code' (axc): 手动添加一个代码引用（修复未识别的跳转）。\n" +
                "- 'add_call' (axC): 手动添加一个函数调用引用。\n" +
                "- 'add_data' (axd): 手动添加一个数据引用（如指针指向）。\n" +
                "- 'add_string' (axs): 手动添加一个字符串引用。\n" +
                "- 'remove_all' (ax-): 删除指定地址的所有引用（修复错误的分析）。",
                mapOf(
                    "action" to mapOf("type" to "string", "enum" to listOf("list_to", "list_from", "add_code", "add_call", "add_data", "add_string", "remove_all"), "description" to "要执行的操作类型"),
                    "target_address" to mapOf("type" to "string", "description" to "目标地址或符号（例如 '0x00401000', 'sym.main', 'entry0'）。对于添加操作，这是引用指向的目标。"),
                    "source_address" to mapOf("type" to "string", "description" to "源地址（可选）。对于添加操作(add_*)，这是发出引用的位置。如果不填，默认为当前光标位置。")
                ),
                listOf("action", "target_address")
            ),
            createToolSchema(
                "os_list_dir",
                "📁 [文件系统] 列出指定文件夹下的内容。如果遇到权限拒绝（如 /data/data），会自动尝试使用 Root 权限列出。输出包含文件类型（DIR/FILE）和大小。",
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "目标文件夹的绝对路径，例如 /sdcard/ 或 /data/local/tmp/")
                ),
                listOf("path")
            ),
            createToolSchema(
                "os_read_file",
                "📄 [文件系统] 读取指定文件的文本内容。支持系统文件和受保护文件的 Root 读取。包含大文件自动截断保护。",
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "目标文件的绝对路径")
                ),
                listOf("path")
            ),
            createToolSchema(
                "r2_config_manager",
                "⚙️ [配置管理] 管理 Radare2 的分析与显示配置 (eval variables)。\n" +
                "当分析结果不理想、函数截断或需要深度分析时使用。\n" +
                "关键配置参考：\n" +
                "- 流量控制: 'anal.hasnext' (继续分析后续代码), 'anal.jmp.after' (无条件跳转后继续)\n" +
                "- 混淆/大块: 'anal.bb.maxsize' (调整基本块大小限制)\n" +
                "- 引用/字符串: 'anal.strings' (开启字符串引用,默认关闭), 'anal.datarefs' (代码引用数据)\n" +
                "- 边界范围 (anal.in): 'io.maps' (分析所有映射), 'dbg.stack' (分析栈), 'bin.section' (当前段)\n" +
                "- 跳转表: 'anal.jmp.tbl' (开启实验性跳转表分析)",
                mapOf(
                    "action" to mapOf("type" to "string", "enum" to listOf("get", "set", "list"), "description" to "操作类型：get(读取当前值), set(修改值), list(搜索配置项)"),
                    "key" to mapOf("type" to "string", "description" to "配置键名，例如 'anal.strings' 或 'anal.in'"),
                    "value" to mapOf("type" to "string", "description" to "要设置的新值 (仅 set 模式需要)。例如 'true', 'false', 'io.maps'")
                ),
                listOf("action", "key")
            ),
            createToolSchema(
                "r2_analysis_hints",
                "🔧 [分析提示] 管理分析提示 (Analysis Hints)。用于手动修正 R2 的分析错误，或优化反汇编显示。\n" +
                "当反汇编结果看起来不对（如代码被当成数据）、立即数格式难以理解（如需要看 IP 地址/十进制）、或控制流中断时使用。\n" +
                "操作说明：\n" +
                "- 'list' (ah): 列出当前地址的提示。\n" +
                "- 'set_base' (ahi): 修改立即数显示进制 (value='10'十进制, '16'十六进制, 's'字符串, 'i'IP地址)。\n" +
                "- 'set_arch' (aha): 强制指定后续代码的架构 (value='arm', 'x86')。\n" +
                "- 'set_bits' (ahb): 强制指定位数 (value='16', '32', '64')。\n" +
                "- 'override_jump' (ahc): 强制指定 Call/Jmp 的跳转目标地址 (修复间接跳转)。\n" +
                "- 'override_opcode' (ahd): 直接用自定义字符串替换当前指令显示的文本。\n" +
                "- 'remove' (ah-): 清除当前地址的所有提示。",
                mapOf(
                    "action" to mapOf("type" to "string", "enum" to listOf("list", "set_base", "set_arch", "set_bits", "override_jump", "override_opcode", "remove"), "description" to "提示操作类型"),
                    "address" to mapOf("type" to "string", "description" to "可选：目标地址（默认为当前光标位置）。"),
                    "value" to mapOf("type" to "string", "description" to "参数值。例如进制类型('10', 's')、架构名、跳转目标地址或替换的指令字符串。")
                ),
                listOf("action")
            )
        )
        
        return buildJsonObject {
            put("tools", JsonArray(tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                }
            }))
        }
    }

    private fun createToolSchema(
        name: String,
        description: String,
        properties: Map<String, Map<String, Any>>,
        required: List<String>
    ): ToolInfo {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (key, value) ->
                    put(key, buildJsonObject {
                        value.forEach { (k, v) ->
                            when (v) {
                                is String -> put(k, v)
                                is Int -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    })
                }
            })
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        
        return ToolInfo(name, description, schema)
    }

    private suspend fun handleToolCall(params: JsonObject?, onLogEvent: (String) -> Unit): JsonElement {
        if (params == null) {
            return createToolResult(false, error = "Missing params")
        }

        val toolName = params["name"]?.jsonPrimitive?.content 
            ?: return createToolResult(false, error = "Missing tool name")
        
        val arguments = params["arguments"]?.jsonObject 
            ?: return createToolResult(false, error = "Missing arguments")

        logInfo("执行工具: $toolName")
        onLogEvent("执行: $toolName")

        return try {
            val result = when (toolName) {
                "r2_open_file" -> executeOpenFile(arguments, onLogEvent)
                "r2_analyze_file" -> executeAnalyzeFile(arguments, onLogEvent)
                "r2_run_command" -> executeCommand(arguments)
                "r2_list_functions" -> executeListFunctions(arguments)
                "r2_list_strings" -> executeListStrings(arguments)
                "r2_get_xrefs" -> executeGetXrefs(arguments)
                "r2_get_info" -> executeGetInfo(arguments)
                "r2_decompile_function" -> executeDecompileFunction(arguments)
                "r2_disassemble" -> executeDisassemble(arguments)
                "r2_test" -> executeTestR2(arguments)
                "r2_close_session" -> executeCloseSession(arguments)
                "r2_analyze_target" -> executeAnalyzeTarget(arguments)
                "r2_manage_xrefs" -> executeManageXrefs(arguments)
                "r2_config_manager" -> executeConfigManager(arguments)
                "r2_analysis_hints" -> executeAnalysisHints(arguments)
                "os_list_dir" -> executeOsListDir(arguments)
                "os_read_file" -> executeOsReadFile(arguments)
                else -> createToolResult(false, error = "Unknown tool: $toolName")
            }
            
            // 自动修复内容格式（类似 r2.js 的 Hotfix）
            fixContentFormat(result)
        } catch (e: Exception) {
            logError("工具执行异常: $toolName", e.message)
            createToolResult(false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * 创建工具调用结果（符合 MCP 协议规范）
     */
    private fun createToolResult(
        success: Boolean,
        output: String? = null,
        error: String? = null
    ): JsonElement {
        return buildJsonObject {
            put("content", JsonArray(listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", output ?: error ?: "")
                }
            )))
            put("isError", !success)
        }
    }

    /**
     * 自动修复格式 Bug（参考 r2.js 的 Hotfix）
     * 确保 content 数组中的每个元素都是正确的对象格式
     */
    private fun fixContentFormat(result: JsonElement): JsonElement {
        if (result !is JsonObject) return result
        
        val content = result["content"]?.jsonArray ?: return result
        
        val fixedContent = content.map { item ->
            when {
                item is JsonPrimitive && item.isString -> {
                    // 自动修复：纯字符串转为 {type: "text", text: "..."}
                    val text = item.content
                    if (text.length > 30) {
                        logInfo("[自动修复格式] ${text.take(30)}...")
                    }
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                else -> item
            }
        }
        
        return buildJsonObject {
            result.forEach { (key, value) ->
                if (key == "content") {
                    put("content", JsonArray(fixedContent))
                } else {
                    put(key, value)
                }
            }
        }
    }

    private suspend fun executeOpenFile(args: JsonObject, onLogEvent: (String) -> Unit): JsonElement {
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing file_path")
        
        // 读取 auto_analyze 参数，默认 true
        val autoAnalyze = args["auto_analyze"]?.jsonPrimitive?.booleanOrNull ?: true
        
        // 验证文件是否存在
        val file = java.io.File(filePath)
        if (!file.exists()) {
            logInfo("文件不存在或无权限访问，尝试 Root 复制: $filePath")
            // 即使文件不存在，也尝试 Root 复制（可能是权限问题）
            val copyPath = tryRootCopy(filePath)
            if (copyPath != null) {
                logInfo("Root 复制成功，使用副本继续: $copyPath")
                // 使用副本文件
                val copyFile = java.io.File(copyPath)
                if (!copyFile.exists()) {
                    logError("Root 复制后副本文件不存在", copyPath)
                    return createToolResult(false, error = "Failed to create accessible copy of file: $filePath")
                }
                // 继续使用副本文件进行后续操作
                return executeOpenFileWithFile(copyFile, copyPath, autoAnalyze, onLogEvent)
            } else {
                logError("文件不存在且 Root 复制失败", filePath)
                return createToolResult(false, error = "File does not exist or no permission to access: $filePath\n\nPossible solutions:\n• Check if the file path is correct\n• For Android APK analysis, try: classes.dex, classes2.dex, classes3.dex, etc.\n• For native libraries, common extensions: .so, .dll, .dylib\n• For executables: .elf, .exe, .bin\n• Ensure device is rooted for accessing system files\n• Check app permissions for the file location")
            }
        }
        
        // 注意：即使 file.canRead() 返回 false，我们也继续尝试 R2Core.openFile
        // 因为在 Android 中，很多系统文件普通应用无法读取，但 R2 可能可以通过其他方式访问
        // 或者我们可以通过 Root 复制来解决权限问题
        
        // session_id 可选，如果没有则自动创建
        var sessionId = args["session_id"]?.jsonPrimitive?.content
        var session = if (sessionId != null) R2SessionManager.getSession(sessionId) else null
        
        if (session == null) {
            // 创建新会话
            val corePtr = R2Core.initR2Core()
            if (corePtr == 0L) {
                logError("R2 Core 初始化失败")
                return createToolResult(false, error = "Failed to initialize R2 core")
            }
            
            val opened = R2Core.openFile(corePtr, filePath)
            if (!opened) {
                logInfo("文件打开失败，尝试 Root 复制: $filePath")
                // 尝试 Root 复制
                val copyPath = tryRootCopy(filePath)
                if (copyPath != null) {
                    logInfo("使用 Root 复制的副本重试: $copyPath")
                    val copyOpened = R2Core.openFile(corePtr, copyPath)
                    if (copyOpened) {
                        logInfo("Root 复制副本打开成功")
                        // 更新会话路径为副本路径
                        sessionId = R2SessionManager.createSession(copyPath, corePtr)
                        session = R2SessionManager.getSession(sessionId)!!
                        logInfo("创建新会话 (使用副本): $sessionId (原始文件: ${file.absolutePath}, 副本: $copyPath)")
                    } else {
                        R2Core.closeR2Core(corePtr)
                        logError("Root 复制副本也无法打开", copyPath)
                        return createToolResult(false, error = "Failed to open file: $filePath (even after root copy to $copyPath)")
                    }
                } else {
                    R2Core.closeR2Core(corePtr)
                    logError("打开文件失败且 Root 复制失败", filePath)
                    return createToolResult(false, error = "Failed to open file: $filePath\n\nPossible solutions:\n1. Check if file exists and is readable\n2. Ensure device is rooted and has root permission\n3. Try using a different file path\n4. Check if file is a valid binary format (ELF, PE, Mach-O, etc.)")
                }
            } else {
                sessionId = R2SessionManager.createSession(filePath, corePtr)
                session = R2SessionManager.getSession(sessionId)!!
                logInfo("创建新会话: $sessionId (文件: ${file.absolutePath})")
            }
        } else {
            logInfo("使用现有会话: $sessionId (文件: $filePath)")
        }

        // 执行分析（如果启用）
        val analysisResult = if (autoAnalyze) {
            logInfo("执行基础分析 (aa)...")
            val startTime = System.currentTimeMillis()
            val output = R2Core.executeCommand(session.corePtr, "aa")
            val duration = System.currentTimeMillis() - startTime
            logInfo("分析完成，耗时 ${duration}ms")
            "\n[基础分析已完成，耗时 ${duration}ms]\n$output"
        } else {
            "\n[跳过自动分析]"
        }

        val info = R2Core.executeCommand(session.corePtr, "i")
        
        return createToolResult(true, output = "Session: $sessionId\n\nFile: ${file.absolutePath}$analysisResult\n\n=== 文件信息 ===\n$info")
    }

    /**
     * 辅助函数：使用指定的文件对象执行打开操作
     */
    private suspend fun executeOpenFileWithFile(file: java.io.File, filePath: String, autoAnalyze: Boolean, onLogEvent: (String) -> Unit): JsonElement {
        // 注意：即使 file.canRead() 返回 false，我们也继续尝试 R2Core.openFile
        // 因为在 Android 中，很多系统文件普通应用无法读取，但 R2 可能可以通过其他方式访问
        // 或者我们可以通过 Root 复制来解决权限问题
        
        // session_id 可选，如果没有则自动创建
        var sessionId: String
        var session = R2SessionManager.getSessionByFilePath(filePath)
        
        if (session == null) {
            // 创建新会话
            val corePtr = R2Core.initR2Core()
            if (corePtr == 0L) {
                logError("R2 Core 初始化失败")
                return createToolResult(false, error = "Failed to initialize R2 core")
            }
            
            val opened = R2Core.openFile(corePtr, filePath)
            if (!opened) {
                R2Core.closeR2Core(corePtr)
                logError("打开文件失败", filePath)
                return createToolResult(false, error = "Failed to open file: $filePath")
            }
            
            sessionId = R2SessionManager.createSession(filePath, corePtr)
            session = R2SessionManager.getSession(sessionId)!!
            logInfo("创建新会话: $sessionId (文件: ${file.absolutePath})")
        } else {
            sessionId = session.sessionId
            logInfo("使用现有会话: $sessionId (文件: $filePath)")
        }

        // 执行分析（如果启用）
        val analysisResult = if (autoAnalyze) {
            logInfo("执行基础分析 (aa)...")
            val startTime = System.currentTimeMillis()
            val output = R2Core.executeCommand(session.corePtr, "aa")
            val duration = System.currentTimeMillis() - startTime
            logInfo("分析完成，耗时 ${duration}ms")
            "\n[基础分析已完成，耗时 ${duration}ms]\n$output"
        } else {
            "\n[跳过自动分析]"
        }

        val info = R2Core.executeCommand(session.corePtr, "i")
        
        return createToolResult(true, output = "Session: $sessionId\n\nFile: ${file.absolutePath}$analysisResult\n\n=== 文件信息 ===\n$info")
    }

    private suspend fun executeAnalyzeFile(args: JsonObject, onLogEvent: (String) -> Unit): JsonElement {
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing file_path")
        
        // 验证文件是否存在
        val file = java.io.File(filePath)
        if (!file.exists()) {
            logInfo("文件不存在或无权限访问，尝试 Root 复制: $filePath")
            // 即使文件不存在，也尝试 Root 复制（可能是权限问题）
            val copyPath = tryRootCopy(filePath)
            if (copyPath != null) {
                logInfo("Root 复制成功，使用副本继续: $copyPath")
                // 使用副本文件
                val copyFile = java.io.File(copyPath)
                if (!copyFile.exists()) {
                    logError("Root 复制后副本文件不存在", copyPath)
                    return createToolResult(false, error = "Failed to create accessible copy of file: $filePath")
                }
                // 继续使用副本文件进行后续操作
                return executeAnalyzeFileWithFile(copyFile, copyPath, onLogEvent)
            } else {
                logError("文件不存在且 Root 复制失败", filePath)
                return createToolResult(false, error = "File does not exist or no permission to access: $filePath\n\nPossible solutions:\n• Check if the file path is correct\n• For Android APK analysis, try: classes.dex, classes2.dex, classes3.dex, etc.\n• For native libraries, common extensions: .so, .dll, .dylib\n• For executables: .elf, .exe, .bin\n• Ensure device is rooted for accessing system files\n• Check app permissions for the file location")
            }
        }
        
        // 注意：即使 file.canRead() 返回 false，我们也继续尝试分析
        // 因为在 Android 中，很多系统文件普通应用无法读取，但可以通过 Root 复制解决

        logInfo("分析文件: ${file.absolutePath} (${file.length()} bytes)")

        // 检查是否已有会话打开该文件
        val existingSession = R2SessionManager.getSessionByFilePath(file.absolutePath)
        if (existingSession != null) {
            logInfo("文件已被会话 ${existingSession.sessionId} 打开，执行深度分析")
            
            // 在现有会话中执行深度分析
            val startTime = System.currentTimeMillis()
            R2Core.executeCommand(existingSession.corePtr, "aaa")
            val duration = System.currentTimeMillis() - startTime
            
            val info = R2Core.executeCommand(existingSession.corePtr, "i")
            val funcs = R2Core.executeCommand(existingSession.corePtr, "afl~?")
            
            return createToolResult(true, output = "Session: ${existingSession.sessionId}\n\n[复用现有会话]\nFile: ${file.absolutePath}\nSize: ${file.length()} bytes\nFunctions: $funcs\n深度分析耗时: ${duration}ms\n\n$info")
        }

        // 创建 R2 Core 实例
        val corePtr = R2Core.initR2Core()
        if (corePtr == 0L) {
            logError("R2 Core 初始化失败")
            return createToolResult(false, error = "Failed to initialize R2 core (r_core_new returned null)")
        }

        try {
            // 打开文件
            val opened = R2Core.openFile(corePtr, file.absolutePath)
            if (!opened) {
                // 尝试 Root 复制
                val copyPath = tryRootCopy(file.absolutePath)
                if (copyPath != null) {
                    logInfo("使用 Root 复制的副本重试分析: $copyPath")
                    val copyOpened = R2Core.openFile(corePtr, copyPath)
                    if (copyOpened) {
                        logInfo("Root 复制副本打开成功，开始深度分析")
                        // 更新文件路径为副本路径
                        val copyFile = File(copyPath)
                        val sessionId = R2SessionManager.createSession(copyPath, corePtr)

                        // 执行深度分析
                        logInfo("执行深度分析 (aaa)...")
                        val startTime = System.currentTimeMillis()
                        R2Core.executeCommand(corePtr, "aaa")
                        val duration = System.currentTimeMillis() - startTime
                        logInfo("深度分析完成，耗时 ${duration}ms")

                        // 获取文件信息
                        val info = R2Core.executeCommand(corePtr, "i")
                        val funcs = R2Core.executeCommand(corePtr, "afl~?")

                        logInfo("分析完成，Session ID: $sessionId, 函数数量: $funcs")
                        return createToolResult(true, output = "Session: $sessionId\n\n[使用 Root 复制副本]\nOriginal: ${file.absolutePath}\nCopy: $copyPath\nSize: ${copyFile.length()} bytes\nFunctions: $funcs\n深度分析耗时: ${duration}ms\n\n$info")
                    } else {
                        logError("Root 复制副本也无法打开", copyPath)
                    }
                }

                logError("打开文件失败且 Root 复制失败", file.absolutePath)
                // 尝试获取错误详情
                val fileList = try {
                    R2Core.executeCommand(corePtr, "o")
                } catch (e: Exception) {
                    "Cannot get file list: ${e.message}"
                }
                val coreInfo = try {
                    R2Core.executeCommand(corePtr, "i")
                } catch (e: Exception) {
                    "Cannot get info: ${e.message}"
                }
                R2Core.closeR2Core(corePtr)
                return createToolResult(false, 
                    error = "Failed to open file: ${file.absolutePath}\n\n" +
                           "File info:\n" +
                           "  - Exists: ${file.exists()}\n" +
                           "  - Readable: ${file.canRead()}\n" +
                           "  - Size: ${file.length()} bytes\n\n" +
                           "R2 opened files: $fileList\n\n" +
                           "R2 info: $coreInfo\n\n" +
                           "Root copy attempted but failed. Check if device is rooted and su command is available.")
            }

            // 创建会话
            val sessionId = R2SessionManager.createSession(file.absolutePath, corePtr)

            // 执行深度分析
            logInfo("执行深度分析 (aaa)...")
            val startTime = System.currentTimeMillis()
            R2Core.executeCommand(corePtr, "aaa")
            val duration = System.currentTimeMillis() - startTime
            logInfo("深度分析完成，耗时 ${duration}ms")

            // 获取文件信息
            val info = R2Core.executeCommand(corePtr, "i")
            val funcs = R2Core.executeCommand(corePtr, "afl~?")

            logInfo("分析完成，Session ID: $sessionId, 函数数量: $funcs")
            return createToolResult(true, output = "Session: $sessionId\n\nFile: ${file.absolutePath}\nSize: ${file.length()} bytes\nFunctions: $funcs\n深度分析耗时: ${duration}ms\n\n$info")
        } catch (e: Exception) {
            logError("分析过程异常", e.message)
            R2Core.closeR2Core(corePtr)
            return createToolResult(false, error = "Exception during analysis: ${e.message}")
        }
    }

    /**
     * 辅助函数：使用指定的文件对象执行分析操作
     */
    private suspend fun executeAnalyzeFileWithFile(file: java.io.File, filePath: String, onLogEvent: (String) -> Unit): JsonElement {
        // 注意：即使 file.canRead() 返回 false，我们也继续尝试分析
        // 因为在 Android 中，很多系统文件普通应用无法读取，但可以通过 Root 复制解决

        logInfo("分析文件: ${file.absolutePath} (${file.length()} bytes)")

        // 检查是否已有会话打开该文件
        val existingSession = R2SessionManager.getSessionByFilePath(file.absolutePath)
        if (existingSession != null) {
            logInfo("文件已被会话 ${existingSession.sessionId} 打开，执行深度分析")
            
            // 在现有会话中执行深度分析
            val startTime = System.currentTimeMillis()
            R2Core.executeCommand(existingSession.corePtr, "aaa")
            val duration = System.currentTimeMillis() - startTime
            
            val info = R2Core.executeCommand(existingSession.corePtr, "i")
            val funcs = R2Core.executeCommand(existingSession.corePtr, "afl~?")
            
            return createToolResult(true, output = "Session: ${existingSession.sessionId}\n\n[复用现有会话]\nFile: ${file.absolutePath}\nSize: ${file.length()} bytes\nFunctions: $funcs\n深度分析耗时: ${duration}ms\n\n$info")
        }

        // 创建 R2 Core 实例
        val corePtr = R2Core.initR2Core()
        if (corePtr == 0L) {
            logError("R2 Core 初始化失败")
            return createToolResult(false, error = "Failed to initialize R2 core (r_core_new returned null)")
        }

        try {
            // 打开文件
            val opened = R2Core.openFile(corePtr, filePath)
            if (!opened) {
                R2Core.closeR2Core(corePtr)
                logError("打开文件失败", filePath)
                return createToolResult(false, error = "Failed to open file: $filePath")
            }

            // 创建会话
            val sessionId = R2SessionManager.createSession(filePath, corePtr)

            // 执行深度分析
            logInfo("执行深度分析 (aaa)...")
            val startTime = System.currentTimeMillis()
            R2Core.executeCommand(corePtr, "aaa")
            val duration = System.currentTimeMillis() - startTime
            logInfo("深度分析完成，耗时 ${duration}ms")

            // 获取文件信息
            val info = R2Core.executeCommand(corePtr, "i")
            val funcs = R2Core.executeCommand(corePtr, "afl~?")

            logInfo("分析完成，Session ID: $sessionId, 函数数量: $funcs")
            return createToolResult(true, output = "Session: $sessionId\n\nFile: ${file.absolutePath}\nSize: ${file.length()} bytes\nFunctions: $funcs\n深度分析耗时: ${duration}ms\n\n$info")
        } catch (e: Exception) {
            logError("分析过程异常", e.message)
            R2Core.closeR2Core(corePtr)
            return createToolResult(false, error = "Exception during analysis: ${e.message}")
        }
    }

    private suspend fun executeCommand(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val command = args["command"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing command")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("执行命令: $command (Session: ${sessionId.take(16)})")
        
        // 直接使用会话的 core 指针执行命令
        val rawResult = R2Core.executeCommand(session.corePtr, command)
        
        // 使用全局清洗函数防止输出爆炸
        val result = sanitizeOutput(rawResult, maxLines = 1000, maxChars = 20000)
        
        if (result.length > 200) {
            logInfo("命令返回: ${result.length} bytes")
        }
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeListFunctions(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        
        val filter = args["filter"]?.jsonPrimitive?.content ?: ""  // 新增过滤参数
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 500   // 默认限制500个

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        // 使用 afl~keyword 语法进行过滤
        val command = if (filter.isBlank()) "afl" else "afl~$filter"
        
        logInfo("列出函数 (过滤: '$filter', 限制: $limit, Session: ${sessionId.take(16)})")
        
        val rawResult = R2Core.executeCommand(session.corePtr, command)
        
        // 使用全局清洗函数限制输出大小
        val result = sanitizeOutput(rawResult, maxLines = limit, maxChars = 16000)
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeDecompileFunction(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        // 1. 先检查函数大小 (afi 命令获取函数信息)
        val info = R2Core.executeCommand(session.corePtr, "afi @ $address")
        val size = info.lines()
            .find { it.trim().startsWith("size:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toLongOrNull() ?: 0
                   
        if (size > 10000) { // 如果二进制大小超过 10KB，反编译代码会巨大
            logInfo("函数过大 ($address, size: $size bytes)，跳过反编译")
            return createToolResult(true, output = "⚠️ 函数过大 (Size: $size bytes)，反编译可能导致超时或不准确。\n\n建议先使用 r2_disassemble 查看局部汇编，或使用 r2_run_command 执行 'pdf @ $address' 查看函数结构。")
        }

        logInfo("反编译函数: $address (size: $size bytes, Session: ${sessionId.take(16)})")
        
        // 2. 安全才反编译
        val rawCode = R2Core.executeCommand(session.corePtr, "pdc @ $address")
        
        // 3. 使用全局清洗函数限制输出
        val result = sanitizeOutput(rawCode, maxLines = 500, maxChars = 15000)
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeDisassemble(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")
        val lines = args["lines"]?.jsonPrimitive?.intOrNull ?: 10

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("反汇编: $address ($lines 行)")
        
        val result = R2Core.executeCommand(session.corePtr, "pd $lines @ $address")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeGetFunctions(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("获取函数列表 (Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, "afl")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeCloseSession(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.removeSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("关闭会话: $sessionId (文件: ${session.filePath})")
        
        return createToolResult(true, output = "Session closed: $sessionId")
    }
    
    private suspend fun executeTestR2(args: JsonObject): JsonElement {
        logInfo("执行 R2 测试")
        
        return try {
            val testResult = R2Core.testR2()
            logInfo("R2 测试完成")
            createToolResult(true, output = testResult)
        } catch (e: Exception) {
            logError("R2 测试失败", e.message)
            createToolResult(false, error = "R2 test failed: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private suspend fun executeListStrings(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val mode = args["mode"]?.jsonPrimitive?.content ?: "data"
        val minLength = args["min_length"]?.jsonPrimitive?.intOrNull ?: 5 // 默认忽略小于5的
        
        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = when (mode) {
            "all" -> "izz"   // 全盘搜索（慢但全面）
            else -> "iz"      // 数据段字符串（快速）
        }
        
        logInfo("列出字符串 (模式: $mode, 最小长度: $minLength, Session: ${sessionId.take(16)})")
        
        val rawOutput = R2Core.executeCommand(session.corePtr, command)
        
        // 智能清洗：过滤垃圾段和短字符串
        val cleanOutput = rawOutput.lineSequence()
            .filter { line ->
                // 过滤掉垃圾段 (这是最重要的！)
                !line.contains(".eh_frame") && 
                !line.contains(".gcc_except_table") &&
                !line.contains(".text") && // 代码段里的通常是假字符串
                !line.contains("libunwind") // 过滤库报错信息
            }
            .filter { line ->
                // 提取字符串内容部分进行长度检查
                // r2 iz 格式: 000 0x... section type string
                // 简单做法：看行尾长度
                line.trim().length > 20 || // 保留长行 (可能是元数据)
                line.split("ascii", "utf8", "utf16", "utf32").lastOrNull()?.trim()?.length ?: 0 >= minLength
            }
            .joinToString("\n")

        val finalOutput = if (cleanOutput.isBlank()) {
            "No meaningful strings found (filters active: min_len=$minLength, exclude=.text/.eh_frame)"
        } else {
            // 使用全局清洗函数进行截断保护
            sanitizeOutput(cleanOutput, maxLines = 500, maxChars = 16000)
        }
        
        return createToolResult(true, output = finalOutput)
    }

    private suspend fun executeGetXrefs(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")
        
        val direction = args["direction"]?.jsonPrimitive?.content ?: "to"
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50  // 默认限制 50 个引用

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = when (direction) {
            "from" -> "axf @ $address"  // 它调用了谁
            else -> "axt @ $address"     // 谁调用了它
        }
        
        logInfo("获取交叉引用 (地址: $address, 方向: $direction, 限制: $limit, Session: ${sessionId.take(16)})")
        
        val rawResult = R2Core.executeCommand(session.corePtr, command)
        
        // 限制输出数量，防止 malloc/memcpy 等通用函数的引用风暴
        val result = sanitizeOutput(rawResult, maxLines = limit, maxChars = 8000)
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeGetInfo(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        
        val detailed = args["detailed"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = if (detailed) "iI" else "i"
        
        logInfo("获取文件信息 (详细: $detailed, Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, command)
        
        return createToolResult(true, output = result)
    }

    /**
     * 执行 os_list_dir 工具
     */
    private suspend fun executeOsListDir(args: JsonObject): JsonElement {
        val pathStr = args["path"]?.jsonPrimitive?.content ?: "/"
        val dir = java.io.File(pathStr)
        val resultLines = mutableListOf<String>()
        var usedRoot = false

        // --- 阶段 1: 尝试 Java 标准 API (快速，无 Root 开销) ---
        val files = dir.listFiles()
        if (files != null) {
            files.forEach { file ->
                val type = if (file.isDirectory) "[DIR] " else "[FILE]"
                val size = if (file.isFile) String.format("%-8s", "(${file.length()})") else "        "
                resultLines.add("$type $size ${file.name}")
            }
        } else {
            // --- 阶段 2: Java API 失败 (通常是权限问题)，尝试 Root ---
            // 使用 ls -p -l 或类似命令。这里用简单的 ls -p 区分文件夹
            val cmd = "ls -p \"$pathStr\""
            val output = ShellUtils.execCommand(cmd, isRoot = true)

            if (output.isSuccess) {
                usedRoot = true
                output.successMsg.lines().forEach { line ->
                    if (line.isNotBlank()) {
                        val type = if (line.endsWith("/")) "[DIR] " else "[FILE]"
                        val name = line.removeSuffix("/")
                        resultLines.add("$type $name")
                    }
                }
            } else {
                // Root 也失败了
                return createToolResult(false, error = "❌ 无法访问目录: $pathStr\n错误信息: ${output.errorMsg}")
            }
        }

        val header = if (usedRoot) "=== 目录列表 (Root Access) ===\n" else "=== 目录列表 ===\n"
        val body = if (resultLines.isEmpty()) "(目录为空)" else resultLines.joinToString("\n")

        return createToolResult(true, output = header + body)
    }

    /**
     * 执行 os_read_file 工具
     */
    private suspend fun executeOsReadFile(args: JsonObject): JsonElement {
        val pathStr = args["path"]?.jsonPrimitive?.content
        if (pathStr.isNullOrEmpty()) {
            return createToolResult(false, error = "Path is required")
        }

        val file = java.io.File(pathStr)
        var content = ""
        var source = "Standard API"

        // --- 阶段 1: 尝试 Java 读取 ---
        if (file.exists() && file.canRead()) {
            try {
                content = file.readText()
            } catch (e: Exception) {
                // 读取异常，准备进入 Root 尝试
            }
        }

        // --- 阶段 2: 如果内容为空且无法读取，尝试 Root cat ---
        if (content.isEmpty()) {
            val output = ShellUtils.execCommand("cat \"$pathStr\"", isRoot = true)
            if (output.isSuccess) {
                content = output.successMsg
                source = "Root Access"
            } else {
                // 彻底失败
                return createToolResult(false, error = "❌ 读取文件失败: $pathStr\nPermission denied & Root failed.")
            }
        }

        // --- 阶段 3: 大文件截断保护 (关键！) ---
        // 防止读取巨大的 .so 或 .log 文件导致 OOM
        val limit = 50000 // 50KB 限制
        val truncatedNote = if (content.length > limit) {
            content = content.take(limit)
            "\n\n[⚠️ SYSTEM: 文件过大，已截断显示前 50KB 内容]"
        } else ""

        return createToolResult(true, output = "($source)\n$content$truncatedNote")
    }

    /**
     * 执行 r2_analyze_target 工具
     */
    private suspend fun executeAnalyzeTarget(args: JsonObject): JsonElement {
        val strategy = args["strategy"]?.jsonPrimitive?.content ?: "basic"
        val address = args["address"]?.jsonPrimitive?.content

        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        // 构造 R2 命令
        // 如果有地址，就在命令后面加 @地址，否则全局执行
        val addrSuffix = if (!address.isNullOrEmpty()) " @ $address" else ""

        val cmd = when (strategy) {
            "basic" -> "aa"
            "blocks" -> "aab$addrSuffix"
            "calls" -> "aac$addrSuffix"
            "refs" -> "aar$addrSuffix" // aar 通常是全局的，但也可以指定范围
            "pointers" -> "aad$addrSuffix"
            "full" -> "aaa" // 慎用
            else -> "aa"
        }

        logInfo("执行智能分析策略: $strategy (命令: $cmd, 会话: ${sessionId.take(16)})")

        // 1. 执行分析命令
        val startTime = System.currentTimeMillis()
        val analysisOutput = R2Core.executeCommand(session.corePtr, cmd)
        val duration = System.currentTimeMillis() - startTime
        logInfo("分析完成，耗时 ${duration}ms")

        // 2. 获取分析结果反馈 (让 AI 知道发生了什么变化)
        // 统计当前函数数量 (afl~?) 和代码覆盖大小
        val funcCount = R2Core.executeCommand(session.corePtr, "afl~?").trim()
        val codeSize = R2Core.executeCommand(session.corePtr, "?v \$SS").trim()

        // 3. 构造返回消息
        val resultMsg = StringBuilder()
        resultMsg.append("✅ 分析策略 '$strategy' 执行完毕 (Cmd: $cmd, 耗时: ${duration}ms)。\n")
        resultMsg.append("📊 当前状态：\n")
        resultMsg.append("- 已识别函数数: $funcCount\n")
        resultMsg.append("- 代码段大小: $codeSize bytes\n")

        when (strategy) {
            "calls" -> resultMsg.append("💡 提示：如果函数数量增加了，说明发现了新的子函数。")
            "pointers" -> resultMsg.append("💡 提示：请检查数据段是否识别出了新的 xref。")
            "blocks" -> resultMsg.append("💡 提示：函数基本块结构已优化，可能修复了截断问题。")
            "refs" -> resultMsg.append("💡 提示：数据引用已分析，可用于查找字符串和全局变量。")
            "full" -> resultMsg.append("⚠️ 注意：全量分析已完成，可能耗时较长。")
            else -> resultMsg.append("💡 提示：基础分析已完成，识别了符号和入口点。")
        }

        if (analysisOutput.isNotBlank()) {
            resultMsg.append("\n\n=== 分析输出 ===\n$analysisOutput")
        }

        return createToolResult(true, output = resultMsg.toString())
    }

    /**
     * 执行 r2_manage_xrefs 工具
     */
    private suspend fun executeManageXrefs(args: JsonObject): JsonElement {
        val action = args["action"]?.jsonPrimitive?.content ?: "list_to"
        val target = args["target_address"]?.jsonPrimitive?.content ?: ""
        val source = args["source_address"]?.jsonPrimitive?.content

        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        if (target.isEmpty()) {
            return createToolResult(false, error = "必须指定目标地址 (target_address)")
        }

        // 构造源地址后缀，如果没填 source，r2 默认使用当前 seek
        val atSuffix = if (!source.isNullOrEmpty()) " $source" else ""

        logInfo("执行交叉引用管理: $action (目标: $target, 源: ${source ?: "当前位置"}, 会话: ${sessionId.take(16)})")

        // 执行逻辑
        val resultText = when (action) {
            // --- 查询类操作 (使用 JSON 格式获取) ---
            "list_to" -> {
                // axtj: list xrefs TO this address (JSON)
                val json = R2Core.executeCommand(session.corePtr, "axtj $target")
                formatXrefs(json, "引用了 $target 的位置 (Xrefs TO)")
            }
            "list_from" -> {
                // axfj: list xrefs FROM this address (JSON)
                val json = R2Core.executeCommand(session.corePtr, "axfj $target")
                formatXrefs(json, "$target 引用了哪些位置 (Xrefs FROM)")
            }

            // --- 修改类操作 ---
            "add_code" -> runR2Action(session, "axc $target$atSuffix", "已添加代码引用")
            "add_call" -> runR2Action(session, "axC $target$atSuffix", "已添加函数调用引用")
            "add_data" -> runR2Action(session, "axd $target$atSuffix", "已添加数据引用")
            "add_string" -> runR2Action(session, "axs $target$atSuffix", "已添加字符串引用")
            "remove_all" -> runR2Action(session, "ax- $target", "已清除该地址的所有引用")

            else -> "❌ 未知操作: $action"
        }

        return createToolResult(true, output = resultText)
    }

    /**
     * 执行 r2_config_manager 工具
     */
    private suspend fun executeConfigManager(args: JsonObject): JsonElement {
        val action = args["action"]?.jsonPrimitive?.content ?: "get"
        val key = args["key"]?.jsonPrimitive?.content ?: ""
        val value = args["value"]?.jsonPrimitive?.content ?: ""

        if (key.isEmpty()) {
            return createToolResult(false, error = "必须指定配置键名 (key)")
        }

        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("执行配置管理: $action (键: $key, 值: $value, 会话: ${sessionId.take(16)})")

        val resultText = when (action) {
            "get" -> {
                // 命令: e key
                val output = R2Core.executeCommand(session.corePtr, "e $key").trim()
                if (output.isEmpty()) {
                    "⚠️ 未找到配置项: $key"
                } else {
                    "$key = $output"
                }
            }
            "set" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "set 操作需要指定值 (value)")
                }
                // 命令: e key=value
                R2Core.executeCommand(session.corePtr, "e $key=$value")

                // 双重确认：读取修改后的值
                val current = R2Core.executeCommand(session.corePtr, "e $key").trim()
                if (current == value || (value == "true" && current == "true") || (value == "false" && current == "false")) {
                    "✅ 配置已更新: $key = $current"
                } else {
                    "⚠️ 配置更新可能失败，当前值: $key = $current"
                }
            }
            "list" -> {
                // 命令: e? key (搜索相关配置)
                val output = R2Core.executeCommand(session.corePtr, "e? $key")
                "🔎 搜索 '$key' 的结果:\n$output"
            }
            else -> "❌ 未知操作: $action"
        }

        return createToolResult(true, output = resultText)
    }

    /**
     * 执行 r2_analysis_hints 工具
     */
    private suspend fun executeAnalysisHints(args: JsonObject): JsonElement {
        val action = args["action"]?.jsonPrimitive?.content ?: "list"
        val address = args["address"]?.jsonPrimitive?.content ?: ""
        val value = args["value"]?.jsonPrimitive?.content ?: ""

        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = R2SessionManager.getSession(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        // 构造地址后缀
        val addrSuffix = if (address.isNotEmpty()) " @ $address" else ""
        val checkAddr = address

        logInfo("执行分析提示: $action (地址: ${address.ifEmpty { "当前位置" }}, 值: $value, 会话: ${sessionId.take(16)})")

        val resultText = when (action) {
            "list" -> {
                val output = R2Core.executeCommand(session.corePtr, "ah$addrSuffix").trim()
                if (output.isBlank()) {
                    "ℹ️ 该地址没有分析提示。"
                } else {
                    output
                }
            }
            "set_base" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "必须指定进制类型 (value)，如 10, 16, s, i")
                }
                R2Core.executeCommand(session.corePtr, "ahi $value$addrSuffix")
                "✅ 已修改数值显示格式为 '$value'"
            }
            "set_arch" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "必须指定架构 (value)，如 arm, x86")
                }
                R2Core.executeCommand(session.corePtr, "aha $value$addrSuffix")
                "✅ 已强制设置架构为 '$value'"
            }
            "set_bits" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "必须指定位数 (value)，如 32, 64")
                }
                R2Core.executeCommand(session.corePtr, "ahb $value$addrSuffix")
                "✅ 已强制设置位数为 '$value' bits"
            }
            "override_jump" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "必须指定跳转目标地址 (value)")
                }
                R2Core.executeCommand(session.corePtr, "ahc $value$addrSuffix")
                "✅ 已强制覆盖跳转目标为 $value"
            }
            "override_opcode" -> {
                if (value.isEmpty()) {
                    return createToolResult(false, error = "必须指定新的指令字符串 (value)")
                }
                // ahd 需要特殊处理，因为它接受包含空格的字符串
                // 格式: ahd string @ address
                R2Core.executeCommand(session.corePtr, "ahd $value$addrSuffix")
                "✅ 已将指令文本替换为: \"$value\""
            }
            "remove" -> {
                R2Core.executeCommand(session.corePtr, "ah-$addrSuffix")
                "✅ 已清除该地址的分析提示"
            }
            else -> "❌ 未知操作: $action"
        }

        // --- 关键：执行完提示后，立即查看效果 ---
        // pd 1 @ address (打印 1 条指令)
        val previewCmd = if (checkAddr.isNotEmpty()) "pd 1 @ $checkAddr" else "pd 1"
        val preview = R2Core.executeCommand(session.corePtr, previewCmd).trim()

        val finalOutput = "$resultText\n\n🔍 当前效果预览:\n$preview"
        return createToolResult(true, output = finalOutput)
    }

    /**
     * 格式化 Xref JSON 输出，让 AI 更容易读懂
     */
    private fun formatXrefs(jsonStr: String, title: String): String {
        if (jsonStr.trim().isEmpty() || jsonStr == "[]") {
            return "ℹ️ $title: 无数据"
        }

        try {
            val sb = StringBuilder("📊 $title:\n")
            // 使用简单的字符串处理来解析JSON数组
            val items = jsonStr.trim().removePrefix("[").removeSuffix("]").split("},")

            for ((index, item) in items.withIndex()) {
                val cleanItem = item.removePrefix("{").removeSuffix("}").trim()
                if (cleanItem.isEmpty()) continue

                val fields = cleanItem.split(",").associate {
                    val parts = it.split(":", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim().removeSurrounding("\"") to parts[1].trim().removeSurrounding("\"")
                    } else {
                        "" to ""
                    }
                }

                val type = fields["type"] ?: "UNK"
                val from = fields["from"]?.toLongOrNull() ?: 0
                val to = fields["to"]?.toLongOrNull() ?: 0

                // 根据查询类型决定显示哪个地址
                val refAddr = if (title.contains("TO")) from else to
                val hexAddr = "0x%08x".format(refAddr)

                sb.append("- [$type] $hexAddr")

                // 添加额外信息
                fields["opcode"]?.let { opcode ->
                    sb.append(" : ${opcode.trim()}")
                }
                fields["fcn_name"]?.let { fcnName ->
                    sb.append(" (in $fcnName)")
                }

                sb.append("\n")
            }

            return sb.toString()
        } catch (e: Exception) {
            logError("Xref JSON 解析失败", e.message)
            // 如果 JSON 解析失败，直接返回原始文本
            return "⚠️ 解析数据失败，原始返回:\n$jsonStr"
        }
    }

    /**
     * 执行简单的 R2 命令并返回成功消息
     */
    private fun runR2Action(session: R2SessionManager.R2Session, cmd: String, successMsg: String): String {
        R2Core.executeCommand(session.corePtr, cmd)
        return "✅ $successMsg (Cmd: $cmd)"
    }
}
