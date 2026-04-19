package ai.openclaw.android.domain

/**
 * AgentResponse — structured response from the LLM.
 *
 * The LLM outputs this as JSON so the client can decide how to deliver
 * the response (voice, rich text, or both) based on device capabilities.
 */
data class AgentResponse(
    val type: ResponseType = ResponseType.TEXT,
    val voiceText: String? = null,
    val richContent: RichContent? = null,
    val fallbackText: String
)

enum class ResponseType { TEXT, VOICE, BOTH }

/**
 * RichContent — structured content types the LLM can request.
 * Maps to A2UI card types or Compose components.
 */
sealed class RichContent {
    /** List of items (e.g. search results, todo items) */
    data class ListCard(val title: String, val items: List<String>) : RichContent()

    /** Info card with title and body text */
    data class InfoCard(val title: String, val body: String) : RichContent()

    /** Code block with language hint */
    data class CodeBlock(val language: String, val code: String) : RichContent()

    /**
     * Parse RichContent from JSON object (used by AgentResponse parser).
     */
    companion object {
        fun fromJson(type: String?, data: Map<String, Any>?): RichContent? {
            if (type == null || data == null) return null
            return when (type) {
                "list" -> ListCard(
                    title = data["title"]?.toString() ?: "",
                    items = (data["items"] as? List<*>)?.map { it.toString() } ?: emptyList()
                )
                "card" -> InfoCard(
                    title = data["title"]?.toString() ?: "",
                    body = data["body"]?.toString() ?: ""
                )
                "code" -> CodeBlock(
                    language = data["language"]?.toString() ?: "",
                    code = data["code"]?.toString() ?: ""
                )
                else -> null
            }
        }
    }
}
