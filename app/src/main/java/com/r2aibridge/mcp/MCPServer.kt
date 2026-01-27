package com.r2aibridge.mcp

import android.util.Log
import com.r2aibridge.R2Core
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

object MCPServer {
    
    private const val TAG = "MCPServer"
    
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
                        call.respondText(
                            text = json.encodeToString(MCPErrorResponse.serializer(), 
                                MCPErrorResponse(
                                    id = null,
                                    error = MCPError(-32700, "Empty request body")
                                )
                            ),
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
                        "tools/list" -> handleToolsList()
                        "tools/call" -> {
                            val toolName = request.params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                            val toolLogMsg = "🔧 工具调用: $toolName | $clientIp"
                            onLogEvent(toolLogMsg)
                            handleToolCall(request.params, onLogEvent)
                        }
                        else -> {
                            logError("未知方法", method)
                            call.respondText(
                                text = json.encodeToString(MCPErrorResponse.serializer(),
                                    MCPErrorResponse(
                                        id = request.id,
                                        error = MCPError(-32601, "Method not found: ${request.method}")
                                    )
                                ),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.OK
                            )
                            return@post
                        }
                    }
                    
                    val response = MCPResponse(id = request.id, result = result)
                    val responseJson = json.encodeToString(MCPResponse.serializer(), response)
                    
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
                    
                    call.respondText(
                        text = json.encodeToString(MCPErrorResponse.serializer(),
                            MCPErrorResponse(
                                id = requestId,
                                error = MCPError(-32603, "Internal error: ${e.message}")
                            )
                        ),
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
     * 处理 initialize 方法 - 协议版本协商
     */
    private fun handleInitialize(params: JsonObject?): JsonElement {
        // 提取客户端请求的协议版本
        val clientProtocolVersion = params?.get("protocolVersion")?.jsonPrimitive?.content
        
        // 协议版本协商：优先使用客户端版本，否则使用默认版本
        val negotiatedVersion = clientProtocolVersion ?: "2024-11-05"
        
        logInfo("初始化协议版本: $negotiatedVersion")
        
        return buildJsonObject {
            put("protocolVersion", negotiatedVersion)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", false)
                })
            })
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
                "r2_open_file" -> executeOpenFile(arguments)
                "r2_analyze_file" -> executeAnalyzeFile(arguments)
                "r2_run_command" -> executeCommand(arguments)
                "r2_list_functions" -> executeListFunctions(arguments)
                "r2_list_strings" -> executeListStrings(arguments)
                "r2_get_xrefs" -> executeGetXrefs(arguments)
                "r2_get_info" -> executeGetInfo(arguments)
                "r2_decompile_function" -> executeDecompileFunction(arguments)
                "r2_disassemble" -> executeDisassemble(arguments)
                "r2_test" -> executeTestR2(arguments)
                "r2_close_session" -> executeCloseSession(arguments)
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

    private suspend fun executeOpenFile(args: JsonObject): JsonElement {
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing file_path")
        
        // 读取 auto_analyze 参数，默认 true
        val autoAnalyze = args["auto_analyze"]?.jsonPrimitive?.booleanOrNull ?: true
        
        // 验证文件是否存在
        val file = java.io.File(filePath)
        if (!file.exists()) {
            logError("文件不存在", filePath)
            return createToolResult(false, error = "File does not exist: $filePath")
        }
        if (!file.canRead()) {
            logError("文件不可读", filePath)
            return createToolResult(false, error = "Cannot read file: $filePath")
        }
        
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
                R2Core.closeR2Core(corePtr)
                logError("打开文件失败", filePath)
                return createToolResult(false, error = "Failed to open file: $filePath (r2_core_file_open returned false)")
            }
            
            sessionId = R2SessionManager.createSession(filePath, corePtr)
            session = R2SessionManager.getSession(sessionId)!!
            logInfo("创建新会话: $sessionId (文件: ${file.absolutePath})")
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

    private suspend fun executeAnalyzeFile(args: JsonObject): JsonElement {
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing file_path")
        
        // 验证文件是否存在
        val file = java.io.File(filePath)
        if (!file.exists()) {
            logError("文件不存在", filePath)
            return createToolResult(false, error = "File does not exist: $filePath")
        }
        if (!file.canRead()) {
            logError("文件不可读", filePath)
            return createToolResult(false, error = "Cannot read file: $filePath (permission denied)")
        }

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
                logError("打开文件失败", file.absolutePath)
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
                           "Suggestion: Check if file is a valid binary format (ELF, PE, Mach-O, etc.)")
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
}
