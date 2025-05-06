package ai.pipecat.client.openai_realtime_webrtc

import kotlinx.serialization.Serializable

@Serializable
@ConsistentCopyVisibility
internal data class OpenAIConversationItemCreate private constructor(
    val type: String,
    val item: Item
) {
    @Serializable
    data class Item(
        val type: String,
        val role: String,
        val content: List<Content>
    ) {
        @Serializable
        data class Content(
            val type: String,
            val text: String
        )

        companion object {
            fun message(role: String, text: String) = Item(
                type = "message",
                role = role,
                content = listOf(Content(type = when (role) {
                    "assistant" -> "text"
                    else -> "input_text"
                }, text = text))
            )
        }
    }

    companion object {
        fun of(item: Item) = OpenAIConversationItemCreate(
            type = "conversation.item.create",
            item = item
        )
    }
}
