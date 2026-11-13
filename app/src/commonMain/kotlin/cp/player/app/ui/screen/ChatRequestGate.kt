package cp.player.app.ui.screen

/** Identity of a conversation request; numeric peer IDs are only provider-local. */
internal data class ChatSessionIdentity(
    val providerId: String,
    val sourceGeneration: Int,
    val myUid: Long,
    val peerUid: Long,
)

/** Tracks which conversation request is still allowed to update a reused chat model. */
internal class ChatRequestGate {
    private val lock = Any()
    private var generation = 0L
    private var currentSession: ChatSessionIdentity? = null

    fun begin(session: ChatSessionIdentity): Long = synchronized(lock) {
        currentSession = session
        generation += 1L
        generation
    }

    fun currentToken(session: ChatSessionIdentity): Long? = synchronized(lock) {
        generation.takeIf { currentSession == session }
    }

    fun isCurrent(session: ChatSessionIdentity, token: Long): Boolean = synchronized(lock) {
        currentSession == session && generation == token
    }

    fun invalidate() = synchronized(lock) {
        currentSession = null
        generation += 1L
    }
}
