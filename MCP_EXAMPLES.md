# MCP 请求示例

本文件包含了测试 Radare2 AI Bridge 服务的 MCP 请求示例。

## 基础 URL
```
http://<设备IP>:5050/mcp
```

## 1. 列出所有可用工具

### 请求
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list"
}
```

### 响应
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "r2_analyze_file",
        "description": "分析二进制文件，加载文件并执行自动分析",
        "inputSchema": {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "要分析的文件路径"
            }
          },
          "required": ["file_path"]
        }
      }
      // ... 其他工具
    ]
  }
}
```

## 2. 分析二进制文件

### 请求
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "r2_analyze_file",
    "arguments": {
      "file_path": "/sdcard/Download/binary.elf"
    }
  }
}
```

### 响应
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "success": true,
    "output": "Session: session_1234567890\narch     arm\nbits     64\n..."
  }
}
```

## 3. 执行 Radare2 命令

### 请求 - 打印反汇编
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "r2_execute_command",
    "arguments": {
      "session_id": "session_1234567890",
      "command": "pdf"
    }
  }
}
```

### 请求 - 显示字符串
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "r2_execute_command",
    "arguments": {
      "session_id": "session_1234567890",
      "command": "izz"
    }
  }
}
```

### 请求 - 显示导入表
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "r2_execute_command",
    "arguments": {
      "session_id": "session_1234567890",
      "command": "ii"
    }
  }
}
```

## 4. 反汇编指定地址

### 请求
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "r2_disassemble",
    "arguments": {
      "session_id": "session_1234567890",
      "address": "0x00001000",
      "lines": 20
    }
  }
}
```

### 响应
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "result": {
    "success": true,
    "output": "0x00001000  mov x0, x1\n0x00001004  bl 0x2000\n..."
  }
}
```

## 5. 获取函数列表

### 请求
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "r2_get_functions",
    "arguments": {
      "session_id": "session_1234567890"
    }
  }
}
```

### 响应
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "result": {
    "success": true,
    "output": "0x00001000    1 main\n0x00001100    2 printf\n..."
  }
}
```

## 6. 关闭会话

### 请求
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "tools/call",
  "params": {
    "name": "r2_close_session",
    "arguments": {
      "session_id": "session_1234567890"
    }
  }
}
```

### 响应
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "result": {
    "success": true,
    "output": "Session closed"
  }
}
```

## 错误响应示例

### 工具调用失败
```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "result": {
    "success": false,
    "error": "Invalid session_id"
  }
}
```

### 方法不存在
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "error": {
    "code": -32601,
    "message": "Method not found: unknown/method"
  }
}
```

### 内部错误
```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "error": {
    "code": -32603,
    "message": "Internal error: Failed to open file"
  }
}
```

## 使用 curl 测试

### 健康检查
```bash
curl http://192.168.1.100:5050/health
```

### 列出工具
```bash
curl -X POST http://192.168.1.100:5050/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### 分析文件
```bash
curl -X POST http://192.168.1.100:5050/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":2,
    "method":"tools/call",
    "params":{
      "name":"r2_analyze_file",
      "arguments":{"file_path":"/sdcard/Download/binary.elf"}
    }
  }'
```

### 执行命令
```bash
curl -X POST http://192.168.1.100:5050/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"r2_execute_command",
      "arguments":{
        "session_id":"session_1234567890",
        "command":"afl"
      }
    }
  }' | jq .
```

## 使用 Python 测试

```python
import requests
import json

BASE_URL = "http://192.168.1.100:5050/mcp"

def send_mcp_request(method, params=None):
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": method
    }
    if params:
        payload["params"] = params
    
    response = requests.post(BASE_URL, json=payload)
    return response.json()

# 列出工具
result = send_mcp_request("tools/list")
print(json.dumps(result, indent=2))

# 分析文件
result = send_mcp_request("tools/call", {
    "name": "r2_analyze_file",
    "arguments": {
        "file_path": "/sdcard/Download/binary.elf"
    }
})
print(json.dumps(result, indent=2))

# 提取 session_id
session_id = result["result"]["output"].split("\n")[0].split(": ")[1]

# 获取函数列表
result = send_mcp_request("tools/call", {
    "name": "r2_get_functions",
    "arguments": {
        "session_id": session_id
    }
})
print(result["result"]["output"])

# 关闭会话
result = send_mcp_request("tools/call", {
    "name": "r2_close_session",
    "arguments": {
        "session_id": session_id
    }
})
print(result["result"]["output"])
```

## 常用 Radare2 命令

以下是可以通过 `r2_execute_command` 工具执行的常用 Radare2 命令：

- `aaa` - 完整分析
- `afl` - 列出函数
- `pdf` - 打印当前函数的反汇编
- `pdf @ <address>` - 打印指定地址的函数反汇编
- `s <address>` - 跳转到指定地址
- `px 100` - 以十六进制打印 100 字节
- `izz` - 列出所有字符串
- `ii` - 列出导入
- `ie` - 列出导出
- `iS` - 列出段
- `fs symbols; f` - 列出所有符号
- `?v <expression>` - 计算表达式
- `afr` - 查找引用
- `axt <address>` - 查找到指定地址的交叉引用
- `axf <address>` - 查找从指定地址的交叉引用
