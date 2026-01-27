package com.r2aibridge.mcp

import android.util.Log
import com.r2aibridge.R2Core
import com.r2aibridge.concurrency.R2ConcurrencyManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

object MCPServer {
    
    private const val TAG = "MCPServer"
    
    // 会话数据类：存储文件路径和 R2 Core 指针
    private data class R2Session(
        val filePath: String,
        val corePtr: Long,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    private val r2Sessions = mutableMapOf<String, R2Session>()
    private val sseClients = mutableListOf<Channel<String>>()
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
                        JsonPrimitive("/sse - Server-Sent Events endpoint"),
                        JsonPrimitive("/health - Health check")
                    )))
                }
                
                call.respondText(
                    text = json.encodeToString(JsonObject.serializer(), info),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                )
            }

            // SSE 端点（用于流式通信）
            get("/sse") {
                val clientIp = call.request.local.remoteHost
                val logMsg = "📡 SSE连接: $clientIp"
                logInfo(logMsg)
                onLogEvent(logMsg)
                
                call.response.header("Content-Type", "text/event-stream")
                call.response.header("Cache-Control", "no-cache")
                call.response.header("Connection", "keep-alive")
                
                val channel = Channel<String>(Channel.UNLIMITED)
                sseClients.add(channel)
                
                try {
                    // 发送初始端点信息
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write("event: endpoint\n")
                        write("data: /messages\n\n")
                        flush()
                        
                        // 保持连接
                        for (message in channel) {
                            write("event: message\n")
                            write("data: $message\n\n")
                            flush()
                        }
                    }
                } finally {
                    sseClients.remove(channel)
                    channel.close()
                    val disconnectMsg = "📡 SSE断开: $clientIp"
                    logInfo("SSE 连接已断开")
                    onLogEvent(disconnectMsg)
                }
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
                call.respondText(
                    "R2 MCP Server Running\n" +
                    "Active Sessions: ${r2Sessions.size}\n" +
                    "SSE Clients: ${sseClients.size}",
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
                "📋 [函数分析] 列出二进制文件中的所有已识别函数。使用 'afl' 命令，返回函数地址、大小和名称。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID")
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_list_strings",
                "📝 [逆向第一步] 列出二进制文件中的所有字符串。用于快速定位关键逻辑（如 \"Password\", \"Error\", \"http://\"）。默认使用 'iz'（数据段字符串），可选 'izzz'（全盘搜索）。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "mode" to mapOf("type" to "string", "description" to "搜索模式: 'data'（默认，iz，仅数据段）或 'all'（izzz，全盘搜索）", "default" to "data")
                ),
                listOf("session_id")
            ),
            createToolSchema(
                "r2_get_xrefs",
                "🔗 [逻辑追踪必备] 获取指定地址/函数的交叉引用。查找 \"谁调用了它\"（axt）或 \"它调用了谁\"（axf）。用于分析控制流和函数调用关系。",
                mapOf(
                    "session_id" to mapOf("type" to "string", "description" to "会话 ID"),
                    "address" to mapOf("type" to "string", "description" to "目标地址或函数名（如: 0x401000 或 main）"),
                    "direction" to mapOf("type" to "string", "description" to "引用方向: 'to'（默认，axt，谁调用了它）或 'from'（axf，它调用了谁）", "default" to "to")
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
        var session = if (sessionId != null) r2Sessions[sessionId] else null
        
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
            
            sessionId = "session_${System.currentTimeMillis()}"
            session = R2Session(filePath, corePtr)
            r2Sessions[sessionId] = session
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

        return R2ConcurrencyManager.withFileLock(filePath) {
            // 创建 R2 Core 实例
            val corePtr = R2Core.initR2Core()
            if (corePtr == 0L) {
                logError("R2 Core 初始化失败")
                return@withFileLock createToolResult(false, error = "Failed to initialize R2 core (r_core_new returned null)")
            }

            try {
                // 打开文件（自动分析）
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
                    return@withFileLock createToolResult(false, 
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
                val sessionId = "session_${System.currentTimeMillis()}"
                r2Sessions[sessionId] = R2Session(file.absolutePath, corePtr)

                // 获取文件信息
                val info = R2Core.executeCommand(corePtr, "i")
                val funcs = R2Core.executeCommand(corePtr, "afl~?")

                logInfo("分析完成，Session ID: $sessionId, 函数数量: $funcs")
                createToolResult(true, output = "Session: $sessionId\n\nFile: ${file.absolutePath}\nSize: ${file.length()} bytes\nFunctions: $funcs\n\n$info")
            } catch (e: Exception) {
                logError("分析过程异常", e.message)
                R2Core.closeR2Core(corePtr)
                return@withFileLock createToolResult(false, error = "Exception during analysis: ${e.message}")
            }
        }
    }

    private suspend fun executeCommand(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val command = args["command"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing command")

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("执行命令: $command (Session: ${sessionId.take(16)})")
        
        // 直接使用会话的 core 指针执行命令
        val result = R2Core.executeCommand(session.corePtr, command)
        
        if (result.length > 200) {
            logInfo("命令返回: ${result.length} bytes")
        }
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeListFunctions(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("列出函数 (Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, "afl")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeDecompileFunction(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("反编译函数: $address (Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, "pdc @ $address")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeDisassemble(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")
        val lines = args["lines"]?.jsonPrimitive?.intOrNull ?: 10

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("反汇编: $address ($lines 行)")
        
        val result = R2Core.executeCommand(session.corePtr, "pd $lines @ $address")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeGetFunctions(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("获取函数列表 (Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, "afl")
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeCloseSession(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")

        val session = r2Sessions.remove(sessionId)
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        logInfo("关闭会话: $sessionId (文件: ${session.filePath})")
        
        // 释放 R2 Core
        R2Core.closeR2Core(session.corePtr)
        
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
        
        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = when (mode) {
            "all" -> "izzz"  // 全盘搜索（慢但全面）
            else -> "iz"     // 数据段字符串（快速）
        }
        
        logInfo("列出字符串 (模式: $mode, Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, command)
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeGetXrefs(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        
        val address = args["address"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing address")
        
        val direction = args["direction"]?.jsonPrimitive?.content ?: "to"

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = when (direction) {
            "from" -> "axf @ $address"  // 它调用了谁
            else -> "axt @ $address"     // 谁调用了它
        }
        
        logInfo("获取交叉引用 (地址: $address, 方向: $direction, Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, command)
        
        return createToolResult(true, output = result)
    }

    private suspend fun executeGetInfo(args: JsonObject): JsonElement {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return createToolResult(false, error = "Missing session_id")
        
        val detailed = args["detailed"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = r2Sessions[sessionId]
            ?: return createToolResult(false, error = "Invalid session_id: $sessionId")

        val command = if (detailed) "iI" else "i"
        
        logInfo("获取文件信息 (详细: $detailed, Session: ${sessionId.take(16)})")
        
        val result = R2Core.executeCommand(session.corePtr, command)
        
        return createToolResult(true, output = result)
    }
}
