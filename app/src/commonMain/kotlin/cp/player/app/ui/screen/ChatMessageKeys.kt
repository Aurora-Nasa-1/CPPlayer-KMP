package cp.player.app.ui.screen

import cp.player.core.model.Message

/**
 * Build saveable, stable keys for the chat LazyColumn.
 *
 * Provider message IDs are opaque and may be duplicated by an upstream page. Compose keys therefore
 * combine the raw ID with immutable message identity fields, then add an occurrence ordinal only
 * for completely identical duplicate records. The key never depends on the row's current position,
 * so inserting or removing a different message does not invalidate every later row.
 */
internal fun stableChatMessageKeys(messages: List<Message>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return messages.map { message ->
        val base = buildString {
            append(message.id.length).append(':').append(message.id)
            append('|').append(message.fromUserId)
            append('|').append(message.time)
            append('|').append(message.text.length).append(':').append(message.text)
            append('|').append(if (message.isMe) '1' else '0')
        }
        val occurrence = occurrences[base] ?: 0
        occurrences[base] = occurrence + 1
        "$base|$occurrence"
    }
}

/** Only a strict append may animate; replacement, deletion or reordering must not animate stale rows. */
internal fun isStrictlyAppendedChatList(previous: List<String>, current: List<String>): Boolean =
    previous.isNotEmpty() &&
        current.size > previous.size &&
        current.subList(0, previous.size) == previous
