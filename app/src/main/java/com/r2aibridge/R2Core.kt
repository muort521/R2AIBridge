package com.r2aibridge

import android.util.Log

/**
 * JNI wrapper for Radare2 core functionality
 */
object R2Core {
    private const val TAG = "R2Core"
    
    init {
        try {
            // 按依赖顺序加载 radare2 库
            val libs = arrayOf(
                "r_util",      // 基础工具库（最底层）
                "r_socket",    // Socket 库
                "r_cons",      // Console 库
                "r_config",    // 配置库
                "r_io",        // IO 库
                "r_muta",      // Crypto 库
                "r_flag",      // Flag 库
                "r_reg",       // Register 库
                "r_syscall",   // Syscall 库
                "r_search",    // Search 库
                "r_magic",     // Magic 库
                "r_bp",        // Breakpoint 库
                "r_esil",      // ESIL 库
                "r_arch",      // Architecture 库
                "r_asm",       // Assembler 库
                "r_anal",      // Analysis 库
                "r_bin",       // Binary 库
                "r_egg",       // Egg 库
                "r_lang",      // Language 库
                "r_fs",        // Filesystem 库
                "r_debug",     // Debug 库
                "r_core"       // Core 库（最顶层）
            )
            
            for (lib in libs) {
                try {
                    System.loadLibrary(lib)
                    Log.d(TAG, "Loaded: lib$lib.so")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Failed to load lib$lib.so: ${e.message}")
                }
            }
            
            // 加载我们的 JNI 桥接库
            System.loadLibrary("r2aibridge")
            Log.i(TAG, "R2 libraries loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load R2 libraries", e)
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
