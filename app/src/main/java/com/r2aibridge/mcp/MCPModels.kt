package com.r2aibridge.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class MCPRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,  // 支持 string、number 或 null
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class MCPResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement
)

@Serializable
data class MCPError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class MCPErrorResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val error: MCPError
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolInfo>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject
)

@Serializable
data class ToolCallResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)
