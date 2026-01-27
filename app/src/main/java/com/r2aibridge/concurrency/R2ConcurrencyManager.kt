package com.r2aibridge.concurrency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 基于文件路径哈希的桶锁并发管理器
 * 使用 16 个 Mutex 桶来减少锁竞争
 */
object R2ConcurrencyManager {
    private const val BUCKET_COUNT = 16
    private val buckets = Array(BUCKET_COUNT) { Mutex() }

    /**
     * 获取文件路径对应的桶锁
     */
    private fun getBucket(filePath: String): Mutex {
        val hash = filePath.hashCode()
        val index = (hash and Int.MAX_VALUE) % BUCKET_COUNT
        return buckets[index]
    }

    /**
     * 在文件锁保护下执行操作
     * @param filePath 文件路径
     * @param block 要执行的挂起函数
     * @return 操作结果
     */
    suspend fun <T> withFileLock(filePath: String, block: suspend () -> T): T {
        val bucket = getBucket(filePath)
        return bucket.withLock {
            block()
        }
    }

    /**
     * 尝试获取文件锁并执行操作
     * @param filePath 文件路径
     * @param block 要执行的挂起函数
     * @return 操作结果，如果无法获取锁则返回 null
     */
    suspend fun <T> tryWithFileLock(filePath: String, block: suspend () -> T): T? {
        val bucket = getBucket(filePath)
        return if (bucket.tryLock()) {
            try {
                block()
            } finally {
                bucket.unlock()
            }
        } else {
            null
        }
    }
}
