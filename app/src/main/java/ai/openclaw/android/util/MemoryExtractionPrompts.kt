package ai.openclaw.android.util

object MemoryExtractionPrompts {
    val SYSTEM_PROMPT = """
分析以下对话，提取需要记住的信息。

输出 JSON 格式：
{
  "memories": [
    {
      "type": "PREFERENCE|FACT|DECISION|TASK|PROJECT",
      "content": "具体内容",
      "priority": 1-5,
      "tags": ["标签1", "标签2"]
    }
  ]
}

提取规则：
1. PREFERENCE: 用户偏好（"我喜欢..."、"不要..."）
2. FACT: 事实信息（"我叫..."、"我的邮箱是..."）
3. DECISION: 重要决策（"选择方案A"、"决定用..."）
4. TASK: 待办事项（"明天要..."、"记得..."）
5. PROJECT: 项目信息（"项目路径是..."、"使用...框架"）

只提取新信息，不要重复已有记忆。
""".trimIndent()
}