package ai.openclaw.android.domain.session

class TokenCounter {
    /**
     * 粗略估算 token 数量
     * 中文约 1.5 字/token，英文约 0.25 词/token
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        val nonChinese = text.length - chineseChars
        
        return (chineseChars * 0.67 + nonChinese * 0.25).toInt().coerceAtLeast(1)
    }
    
    // 精确计数（使用模型自带的 tokenizer，如果可用）
    fun countExact(text: String, tokenizer: Any?): Int {
        // 这里简化实现，实际应用中会调用具体的tokenizer
        return tokenizer?.let { 
            // 在实际实现中，这里会调用tokenizer的编码方法
            estimate(text) 
        } ?: estimate(text)
    }
}