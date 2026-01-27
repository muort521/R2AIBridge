package com.r2aibridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * JNI wrapper for Radare2 core functionality
 */
object R2Core {
    private const val TAG = "R2Core"
    private var isInitialized = false
    
    /**
     * 智能加载器：自动扫描并加载所有 radare2 库
     * 无需手动维护列表，支持任意增删 .so 文件
     * 
     * @param context Application context for getting native library directory
     */
    fun loadLibraries(context: Context) {
        if (isInitialized) {
            Log.i(TAG, "Libraries already loaded, skipping...")
            return
        }
        
        try {
            // 1. 获取 App 原生库安装目录
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            if (!libDir.exists() || !libDir.isDirectory) {
                Log.e(TAG, "Native lib dir not found: ${libDir.absolutePath}")
                return
            }
            
            Log.i(TAG, "Scanning native libs in: ${libDir.absolutePath}")
            
            // 2. 扫描所有以 libr 开头的 .so 文件
            // 过滤逻辑（双保险）：
            // 1. 必须是 .so 文件
            // 2. 必须以 "libr" 开头（涵盖 libr_xxx 和可能的新格式）
            // 3. ⛔ 显式排除桥接库 r2aibridge（必须最后加载）
            // 4. ⛔ 显式排除 C++ 运行时（虽然 libc 开头本来就不匹配，但写上更放心）
            val r2Libs = libDir.listFiles { file ->
                val name = file.name
                name.endsWith(".so") && 
                name.startsWith("libr") && 
                !name.contains("r2aibridge") && 
                !name.contains("c++_shared")
            }?.toMutableList() ?: mutableListOf()
            
            Log.i(TAG, "Found ${r2Libs.size} radare2 libraries. Starting smart load...")
            
            // 3. 自旋加载循环（解决依赖顺序问题）
            var loadedCount = 0
            var pass = 0
            val maxPasses = r2Libs.size * 2 // 防止死循环的安全阈值
            
            while (r2Libs.isNotEmpty() && pass < maxPasses) {
                val iterator = r2Libs.iterator()
                var progressMadeInThisPass = false
                
                while (iterator.hasNext()) {
                    val file = iterator.next()
                    // 将 "libr_core.so" 转换为 "r_core"（System.loadLibrary 需要的格式）
                    val libName = file.name.removePrefix("lib").removeSuffix(".so")
                    
                    try {
                        System.loadLibrary(libName)
                        // 如果这一行没报错，说明加载成功
                        iterator.remove() // 从待办列表中移除
                        progressMadeInThisPass = true
                        loadedCount++
                        Log.v(TAG, "✓ Loaded: $libName (pass $pass)")
                    } catch (e: UnsatisfiedLinkError) {
                        // 依赖未满足，暂时跳过，留给下一轮
                        // Log.v(TAG, "⊙ Skipped: $libName (deps not ready)")
                    } catch (e: Throwable) {
                        Log.e(TAG, "✗ Fatal error loading $libName", e)
                        iterator.remove() // 致命错误，移除避免死循环
                    }
                }
                
                if (!progressMadeInThisPass && r2Libs.isNotEmpty()) {
                    // 如果一整轮下来一个都没加载成功，说明发生了死锁或缺库
                    Log.e(TAG, "❌ Dependency deadlock! Remaining libs cannot be loaded:")
                    r2Libs.forEach { Log.e(TAG, "   - ${it.name}") }
                    break
                }
                
                pass++
            }
            
            // 4. 最后加载我们的 JNI 桥接库
            // 这时候所有 r2 依赖都应该准备就绪了
            try {
                System.loadLibrary("r2aibridge")
                Log.i(TAG, "✅ Success! Loaded $loadedCount r2 libs + bridge in $pass passes")
                isInitialized = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load libr2aibridge.so. Missing dependencies:")
                Log.e(TAG, e.message ?: "Unknown error")
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Smart load failed", e)
            throw e
        }
    }

    /**
     * Initialize a new Radare2 core instance
     * @return Native pointer to RCore, or 0 if failed
     */
    external fun initR2Core(): Long

    /**
     * Execute a Radare2 command
     * @param corePtr Native pointer to RCore
     * @param command Radare2 command string
     * @return Command output or error message
     */
    external fun executeCommand(corePtr: Long, command: String): String

    /**
     * Open a binary file for analysis
     * @param corePtr Native pointer to RCore
     * @param filePath Full path to the binary file
     * @return true if successful, false otherwise
     */
    external fun openFile(corePtr: Long, filePath: String): Boolean

    /**
     * Close and free a Radare2 core instance
     * @param corePtr Native pointer to RCore
     */
    external fun closeR2Core(corePtr: Long)
    
    /**
     * Test if R2 libraries are loaded and working
     * @return Test result string with version info
     */
    external fun testR2(): String
}
