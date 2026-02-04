package com.r2aibridge.mcp

import android.util.Log
import com.r2aibridge.R2Core
import java.util.concurrent.ConcurrentHashMap

/**
 * Radare2 会话管理器
 * 单例模式，负责管理所有的 R2 Core 会话
 */
object R2SessionManager {
    
    private const val TAG = "R2AI"
    
    /**
     * 会话数据类：存储文件路径和 R2 Core 指针
     */
    data class R2Session(
        val sessionId: String,
        val filePath: String,
        val corePtr: Long,
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessedAt: Long = System.currentTimeMillis()
    )
    
    // 使用线程安全的 Map 存储会话
    private val sessions = ConcurrentHashMap<String, R2Session>()
    
    /**
     * 创建新会话
     */
    fun createSession(filePath: String, corePtr: Long): String {
        val sessionId = "session_${System.currentTimeMillis()}"
        val session = R2Session(
            sessionId = sessionId,
            filePath = filePath,
            corePtr = corePtr
        )
        sessions[sessionId] = session
        Log.i(TAG, "会话已创建: $sessionId (文件: $filePath, core: 0x${corePtr.toString(16)})")
        return sessionId
    }
    
    /**
     * 获取会话
     */
    fun getSession(sessionId: String): R2Session? {
        val session = sessions[sessionId]
        session?.lastAccessedAt = System.currentTimeMillis()
        return session
    }
    
    /**
     * 删除会话并释放资源
     */
    fun removeSession(sessionId: String): R2Session? {
        val session = sessions.remove(sessionId)
        if (session != null) {
            try {
                R2Core.closeR2Core(session.corePtr)
                Log.i(TAG, "会话已关闭: $sessionId (文件: ${session.filePath})")
            } catch (e: Exception) {
                Log.e(TAG, "关闭会话失败: $sessionId", e)
            }
        }
        return session
    }
    
    /**
     * 获取所有会话 (返回列表)
     */
    fun getAllSessions(): Map<String, R2Session> {
        return sessions
    }
    
    /**
     * 获取所有会话 (返回列表)
     */
    fun getAllSessionsList(): List<R2Session> {
        return sessions.values.toList()
    }
    
    /**
     * 获取会话数量
     */
    fun getSessionCount(): Int {
        return sessions.size
    }
    
    /**
     * 检查会话是否存在
     */
    fun hasSession(sessionId: String): Boolean {
        return sessions.containsKey(sessionId)
    }
    
    /**
     * 根据文件路径查找会话
     * @param filePath 文件路径
     * @return 打开该文件的会话，如果没有则返回 null
     */
    fun getSessionByFilePath(filePath: String): R2Session? {
        return sessions.values.find { it.filePath == filePath }
    }
    
    /**
     * 检查文件是否已被某个会话打开
     * @param filePath 文件路径
     * @return 是否已打开
     */
    fun hasOpenedFile(filePath: String): Boolean {
        return sessions.values.any { it.filePath == filePath }
    }
    
    /**
     * 清理所有会话
     */
    fun closeAllSessions() {
        val sessionIds = sessions.keys.toList()
        sessionIds.forEach { sessionId ->
            removeSession(sessionId)
        }
        Log.i(TAG, "所有会话已清理")
    }
    
    /**
     * 清理超时会话（超过指定时间未访问）
     * @param timeoutMs 超时时间（毫秒），默认 30 分钟
     */
    fun cleanupTimeoutSessions(timeoutMs: Long = 30 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val timeoutSessions = sessions.values.filter { session ->
            (now - session.lastAccessedAt) > timeoutMs
        }
        
        timeoutSessions.forEach { session ->
            removeSession(session.sessionId)
            Log.i(TAG, "超时会话已清理: ${session.sessionId} (闲置: ${(now - session.lastAccessedAt) / 1000}秒)")
        }
        
        if (timeoutSessions.isNotEmpty()) {
            Log.i(TAG, "清理了 ${timeoutSessions.size} 个超时会话")
        }
    }
    
    /**
     * 获取会话统计信息
     */
    fun getStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val sessions = getAllSessionsList()
        
        return mapOf(
            "total" to sessions.size,
            "active" to sessions.count { (now - it.lastAccessedAt) < 5 * 60 * 1000 }, // 5分钟内活跃
            "oldest" to (sessions.minByOrNull { it.createdAt }?.let { 
                (now - it.createdAt) / 1000 
            } ?: 0L),
            "totalMemory" to (sessions.size * 8L) // 粗略估算
        )
    }
    
    /**
     * 打印会话列表（调试用）
     */
    fun printSessions() {
        val now = System.currentTimeMillis()
        Log.i(TAG, "=== 当前活跃会话 (${sessions.size}) ===")
        sessions.values.forEach { session ->
            val age = (now - session.createdAt) / 1000
            val idle = (now - session.lastAccessedAt) / 1000
            Log.i(TAG, "  - ${session.sessionId.take(16)}... | ${session.filePath.split("/").last()} | 存活: ${age}s | 闲置: ${idle}s")
        }
    }
}
