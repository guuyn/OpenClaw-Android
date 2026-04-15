package ai.openclaw.android.domain.agent

import android.util.Log
import java.util.regex.Pattern

/**
 * Routes messages to the appropriate agent based on:
 * 1. @agentId syntax (explicit routing)
 * 2. Keyword matching
 * 3. Default agent fallback
 */
class AgentRouter(
    private val configManager: AgentConfigManager
) {

    companion object {
        private const val TAG = "AgentRouter"
        // Matches @agentId at start or after whitespace
        private val AT_MENTION_PATTERN = Pattern.compile("(?:^|\\s)@(\\w+)")
    }

    /**
     * Route a message to the appropriate agent ID.
     * @param message The user's message
     * @return The agent ID that should handle this message
     */
    fun route(message: String): String {
        // 1. Check for @agentId mention
        val mentionedId = extractAtMention(message)
        if (mentionedId != null) {
            if (configManager.hasAgent(mentionedId)) {
                Log.d(TAG, "Routed to '$mentionedId' via @mention")
                return mentionedId
            } else {
                Log.w(TAG, "Agent '$mentionedId' not found, falling back to default")
            }
        }

        // 2. Keyword matching
        val keywordMatch = matchByKeywords(message)
        if (keywordMatch != null) {
            Log.d(TAG, "Routed to '$keywordMatch' via keyword match")
            return keywordMatch
        }

        // 3. Default agent
        val defaultId = configManager.getDefaultAgent().id
        Log.d(TAG, "Routed to default agent '$defaultId'")
        return defaultId
    }

    /**
     * Check if a message explicitly mentions an agent.
     */
    fun getExplicitMention(message: String): String? {
        return extractAtMention(message)
    }

    /**
     * Get the best matching agent for a message (without using default fallback).
     * Returns null if no match.
     */
    fun findBestMatch(message: String): String? {
        val mentionedId = extractAtMention(message)
        if (mentionedId != null && configManager.hasAgent(mentionedId)) {
            return mentionedId
        }
        return matchByKeywords(message)
    }

    private fun extractAtMention(message: String): String? {
        val matcher = AT_MENTION_PATTERN.matcher(message)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    private fun matchByKeywords(message: String): String? {
        val lowerMessage = message.lowercase()
        val keywordIndex = configManager.getKeywordIndex()

        var bestMatch: String? = null
        var bestScore = 0

        for ((keyword, agentId) in keywordIndex) {
            if (lowerMessage.contains(keyword)) {
                // Score by keyword length (longer = more specific)
                val score = keyword.length
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = agentId
                }
            }
        }

        return bestMatch
    }
}
