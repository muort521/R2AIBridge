package com.r2aibridge

import java.io.BufferedReader
import java.io.InputStreamReader

object ShellUtils {
    data class CommandResult(
        val isSuccess: Boolean,
        val successMsg: String,
        val errorMsg: String
    )

    /**
     * 执行 Shell 命令
     * @param command 命令内容
     * @param isRoot 是否需要 Root 权限
     */
    fun execCommand(command: String, isRoot: Boolean): CommandResult {
        return try {
            val cmd = if (isRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
            val process = Runtime.getRuntime().exec(cmd)

            // 读取标准输出
            val successMsg = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            // 读取错误输出
            val errorMsg = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }

            val exitCode = process.waitFor()

            CommandResult(
                isSuccess = exitCode == 0,
                successMsg = successMsg,
                errorMsg = errorMsg
            )
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "Unknown Shell Error")
        }
    }
}