#include <jni.h>
#include <string>
#include <android/log.h>
#include <cstdlib>

#define LOG_TAG "R2AIBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations - minimal radare2 API
struct r_core_t;

// External C functions from libr_core.so
extern "C" {
    r_core_t* r_core_new();
    void r_core_free(r_core_t*);
    bool r_core_file_open(r_core_t*, const char*, int, unsigned long long);
    char* r_core_cmd_str(r_core_t*, const char*);
    int r_core_cmd0(r_core_t*, const char*);
}

/**
 * Initialize R2 core
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_r2aibridge_R2Core_initR2Core(JNIEnv* env, jobject /* this */) {
    r_core_t* core = r_core_new();
    if (!core) {
        LOGE("Failed to create R2 core");
        return 0;
    }

    LOGI("R2 Core initialized: %p", core);
    return reinterpret_cast<jlong>(core);
}

/**
 * Execute a radare2 command using direct API
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_r2aibridge_R2Core_executeCommand(
        JNIEnv* env,
        jobject /* this */,
        jlong corePtr,
        jstring command) {
    
    if (corePtr == 0) {
        return env->NewStringUTF("ERROR: R2 core not initialized");
    }

    r_core_t* core = reinterpret_cast<r_core_t*>(corePtr);
    const char* cmd = env->GetStringUTFChars(command, nullptr);
    
    LOGI("Executing command: %s", cmd);

    // Execute command and get result
    char* result = r_core_cmd_str(core, cmd);
    
    env->ReleaseStringUTFChars(command, cmd);

    if (!result) {
        return env->NewStringUTF("");
    }

    jstring jresult = env->NewStringUTF(result);
    
    // Free result using standard free
    free(result);

    return jresult;
}

/**
 * Open a file in R2 core
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_r2aibridge_R2Core_openFile(
        JNIEnv* env,
        jobject /* this */,
        jlong corePtr,
        jstring filePath) {
    
    if (corePtr == 0) {
        LOGE("Invalid core pointer");
        return JNI_FALSE;
    }

    r_core_t* core = reinterpret_cast<r_core_t*>(corePtr);
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    
    LOGI("Opening file: %s", path);

    // 先配置 r2
    r_core_cmd0(core, "e io.cache=true");
    r_core_cmd0(core, "e anal.strings=true");
    
    // 使用多种方式尝试打开文件
    // 方式1: 使用 oo+ 命令（以读写模式打开）
    std::string open_cmd = std::string("oo+ ") + path;
    char* result1 = r_core_cmd_str(core, open_cmd.c_str());
    
    bool success = false;
    
    if (result1 && strlen(result1) > 0) {
        std::string result_str(result1);
        if (result_str.find("Cannot open") == std::string::npos && 
            result_str.find("ERROR") == std::string::npos) {
            LOGI("File opened with oo+: %s", result1);
            success = true;
        }
        free(result1);
    }
    
    // 方式2: 如果失败，尝试只读模式
    if (!success) {
        open_cmd = std::string("o ") + path;
        char* result2 = r_core_cmd_str(core, open_cmd.c_str());
        
        if (result2) {
            // 检查是否成功打开（检查文件列表）
            char* files = r_core_cmd_str(core, "o");
            if (files && strlen(files) > 0) {
                LOGI("File opened with o: %s", files);
                success = true;
                free(files);
            }
            free(result2);
        }
    }
    
    env->ReleaseStringUTFChars(filePath, path);

    if (success) {
        LOGI("File opened successfully, running analysis...");
        // 自动分析
        r_core_cmd0(core, "aaa");
        LOGI("Analysis complete");
    } else {
        LOGE("All methods failed to open file: %s", path);
        // 获取错误信息
        char* error = r_core_cmd_str(core, "o");
        if (error) {
            LOGE("Current opened files: %s", error);
            free(error);
        }
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * Close R2 core
 */
extern "C" JNIEXPORT void JNICALL
Java_com_r2aibridge_R2Core_closeR2Core(
        JNIEnv* env,
        jobject /* this */,
        jlong corePtr) {
    
    if (corePtr == 0) {
        return;
    }

    r_core_t* core = reinterpret_cast<r_core_t*>(corePtr);
    LOGI("Closing R2 core: %p", core);
    
    r_core_free(core);
}

/**
 * Test R2 functionality
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_r2aibridge_R2Core_testR2(JNIEnv* env, jobject /* this */) {
    LOGI("Testing R2 libraries...");
    
    std::string result = "R2 Test Results:\n";
    
    // Test 1: Create core
    r_core_t* core = r_core_new();
    if (!core) {
        result += "FAILED: r_core_new() returned null\n";
        return env->NewStringUTF(result.c_str());
    }
    result += "OK: r_core_new() succeeded\n";
    
    // Test 2: Execute simple command
    char* version = r_core_cmd_str(core, "?V");
    if (version) {
        result += "OK: r_core_cmd_str() works, version: ";
        result += version;
        result += "\n";
        free(version);
    } else {
        result += "FAILED: r_core_cmd_str() returned null\n";
    }
    
    // Test 3: Try to open a simple command
    char* help = r_core_cmd_str(core, "?");
    if (help) {
        result += "OK: Help command works (";
        result += std::to_string(strlen(help));
        result += " bytes)\n";
        free(help);
    } else {
        result += "FAILED: Help command returned null\n";
    }
    
    r_core_free(core);
    result += "OK: r_core_free() completed\n";
    
    LOGI("R2 test complete");
    return env->NewStringUTF(result.c_str());
}
