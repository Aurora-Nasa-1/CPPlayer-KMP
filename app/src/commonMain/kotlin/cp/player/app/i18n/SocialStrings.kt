package cp.player.app.i18n

/**
 * 社交相关文案：一起听房间、私信、消息列表、评论。
 *
 * 按「谁在显示」再切成四个子分组——[SocialStrings.Together] / [Chat] / [Messages] /
 * [Comment]——它们各自的词互不复用（「正在载入对话」和「正在载入消息」是两个页面上的
 * 两句话），拆开比塞一个平铺列表好找。
 *
 * ⚠️ 与既有分组的边界：
 * - 页面标题「一起听」复用 [PlayerStrings.listenTogether]（更多面板里的入口也是它，
 *   同一条文案不能有两种译法）；
 * - 「重试」复用 [PlayerStrings.retry]，评论加载失败复用 [PlayerStrings.commentsLoadFailed]。
 *
 * ⚠️ 一起听的**剩余时间**文案（[Together.ttlExpired] 等）刻意带上前导的 ` · `：
 * 房间页那一行是 `成员 2 人 · 你是房主 · 剩余约 29 分钟`，分隔符是**中文排版**的一部分，
 * 英文侧同样用 ` · ` 保持视觉一致，所以留在文案里而不是留在调用方的字符串拼接里。
 */
interface SocialStrings {
    val together: Together
    val chat: Chat
    val messages: Messages
    val comment: Comment

    /** 一起听（房间页 + 小播放器那条）。 */
    interface Together {
        // —— 状态 1：音源不支持 ——
        val unsupported: String
        val unsupportedNote: String

        // —— 状态 2：不在房间 ——
        val sectionCreate: String
        val createRoom: String
        val creating: String
        val createRoomNote: String
        val oneRoomNote: String

        val sectionJoin: String
        val inviteTitle: String
        val invitePlaceholder: String
        val join: String
        val inviteNote: String

        val sectionOther: String
        val refreshRoomState: String

        // —— 状态 3：在房间 ——
        fun roomId(id: String): String
        fun memberCount(count: Int): String
        val ownerTag: String

        val ttlUnknown: String
        val ttlExpired: String
        val ttlImminent: String
        fun ttlLeft(minutes: Long): String

        /** 小播放器那条用的紧凑措辞（与上面几条**不是**同一句，别合并）。 */
        val ttlShortUnknown: String
        val ttlShortExpired: String
        val ttlShortImminent: String
        fun ttlShortLeft(minutes: Long): String

        /** @param seconds 轮询间隔（秒）。 */
        fun pollNote(seconds: Long): String

        val sectionRoom: String
        val refresh: String

        val sectionInvite: String
        val shareInvite: String
        val copyInvite: String
        val shareInviteNote: String
        val copyInviteNote: String
        val qrHint: String
        val noLinkWarning: String
        val inviteUnavailableNote: String

        // —— 退出（破坏性操作，走二次确认）——
        val sectionLeave: String
        val leaveRoom: String
        val leaveRoomNote: String
        val leaveConfirmTitle: String
        val leaveConfirmMessage: String

        /** 小播放器那条向右箭头的无障碍说明。 */
        val openRoom: String
    }

    /** 一对一私信（[cp.player.app.ui.screen.ChatScreen] / 双栏右栏）。 */
    interface Chat {
        /** 对方昵称缺失时路由页标题的回落值。 */
        val titleFallback: String
        val loginRequired: String
        val accountBound: String
        val loading: String
        val empty: String
        val emptyHint: String
        val inputPlaceholder: String
        val send: String
        val sendFailed: String
        val emptyMessage: String

        /** 拉取历史失败且服务端没给原因时的兜底（会显示为空态的说明文字）。 */
        val loadFailed: String
    }

    /** 消息列表（最近联系人，[cp.player.app.ui.screen.MessagesScreen] / 双栏左栏）。 */
    interface Messages {
        val title: String
        val loginRequired: String

        /** 整页（宽）用的说明；双栏左栏太窄，走 [accountBound] 那句短的。 */
        val loginRequiredNote: String
        val accountBound: String

        val loading: String
        val loadingNote: String
        val empty: String
        val emptyHint: String

        val selectConversation: String
        val selectConversationNote: String

        val unknownUser: String
        val noPreview: String
        val loadFailed: String
    }

    interface Comment {
        val title: String
        val like: String
        val unlike: String
    }
}

object SocialStringsZh : SocialStrings {
    override val together: SocialStrings.Together = object : SocialStrings.Together {
        override val unsupported = "当前音源不支持一起听。"
        override val unsupportedNote =
            "一起听依赖音源自身的房间协议。切换到支持该能力的音源后，本页才会出现操作入口。"

        override val sectionCreate = "创建房间"
        override val createRoom = "创建一起听房间"
        override val creating = "处理中…"
        override val createRoomNote = "创建后把邀请链接发给对方"
        override val oneRoomNote =
            "一个账号同时只能在一个房间里。若你已经在一个房间中，请先退出再建房 —— " +
                "建房会顶掉当前房间，而房间一旦结束就无法恢复。"

        override val sectionJoin = "加入房间"
        override val inviteTitle = "邀请链接或房号"
        override val invitePlaceholder = "粘贴对方发来的邀请链接"
        override val join = "加入"
        // ⚠️ 原文里的 `**无法区分**` 是 Markdown 标记，`Text` 不解析会把星号原样显示 ——
        // 这里改用「」表强调（见 I18N.md §5.8）。
        override val inviteNote =
            "邀请链接里同时带着房间号与邀请人 id，缺一不可。" +
                "若链接无效或房间已结束，服务端只返回一个笼统的错误 —— " +
                "我们「无法区分」「房间不存在」与「邀请不是给你的」，所以这里不会给出更具体的原因。"

        override val sectionOther = "其他"
        override val refreshRoomState = "刷新房间状态"

        override fun roomId(id: String) = "房间号：$id"
        override fun memberCount(count: Int) = "成员 $count 人"
        override val ownerTag = " · 你是房主"

        override val ttlUnknown = " · 时长未知"
        override val ttlExpired = " · 已到有效期，建议重新创建"
        override val ttlImminent = " · 即将到期"
        override fun ttlLeft(minutes: Long) = " · 剩余约 $minutes 分钟"

        override val ttlShortUnknown = "时长未知"
        override val ttlShortExpired = "已到期"
        override val ttlShortImminent = "即将到期"
        override fun ttlShortLeft(minutes: Long) = "剩余 $minutes 分"

        override fun pollNote(seconds: Long) =
            "房间状态每 $seconds 秒同步一次 —— " +
                "网易云不向第三方推送，这个延迟由协议决定，不是网络慢。"

        override val sectionRoom = "房间"
        override val refresh = "刷新"

        override val sectionInvite = "邀请对方"
        override val shareInvite = "分享邀请链接"
        override val copyInvite = "复制邀请链接"
        override val shareInviteNote = "用任意聊天工具发给对方"
        override val copyInviteNote = "已复制到剪贴板，粘贴给朋友即可"
        override val qrHint = "或让对方直接扫描这个二维码加入："
        override val noLinkWarning =
            "暂时拿不到邀请链接：房间信息还没同步到，或账号资料未就绪。刷新一下试试。"
        override val inviteUnavailableNote =
            "网易云没有「发出邀请」的接口 —— 官方客户端那条邀请走的是站内 IM，第三方调不到。" +
                "所以邀请必然是「你主动把链接给对方」这一步，做不到点一下推进对方收件箱。"

        override val sectionLeave = "退出"
        override val leaveRoom = "退出房间"
        override val leaveRoomNote = "退出后房间立即结束，无法恢复"
        override val leaveConfirmTitle = "退出一起听房间？"
        // 同上：`**无法恢复**` 是 Markdown，改成「」。
        override val leaveConfirmMessage =
            "退出后房间立即结束且「无法恢复」。如果你是房主，对方也会同时断开。"

        override val openRoom = "进入一起听房间"
    }

    override val chat: SocialStrings.Chat = object : SocialStrings.Chat {
        override val titleFallback = "私信"
        override val loginRequired = "登录后可发送私信"
        override val accountBound = "私信与账号绑定"
        override val loading = "正在载入对话"
        override val empty = "还没有聊过"
        override val emptyHint = "在下面输入第一句话吧"
        override val inputPlaceholder = "说点什么…"
        override val send = "发送"
        override val sendFailed = "发送失败，请检查登录状态"
        override val emptyMessage = "（空消息）"
        override val loadFailed = "读取私信失败"
    }

    override val messages: SocialStrings.Messages = object : SocialStrings.Messages {
        override val title = "消息"
        override val loginRequired = "登录后查看私信"
        override val loginRequiredNote = "消息与账号绑定，先在「账号与登录」里登录当前音源"
        override val accountBound = "消息与账号绑定"
        override val loading = "正在载入消息"
        override val loadingNote = "正在从当前音源读取最近联系人"
        override val empty = "还没有消息"
        override val emptyHint = "在歌手或用户主页点「发私信」就能开始聊天"
        override val selectConversation = "选择左侧会话"
        override val selectConversationNote = "从左边挑一个联系人，右边就是和他的对话"
        override val unknownUser = "未知用户"
        override val noPreview = "（没有消息内容）"
        override val loadFailed = "读取消息失败"
    }

    override val comment: SocialStrings.Comment = object : SocialStrings.Comment {
        override val title = "评论"
        override val like = "点赞"
        override val unlike = "取消点赞"
    }
}

object SocialStringsEn : SocialStrings {
    override val together: SocialStrings.Together = object : SocialStrings.Together {
        override val unsupported = "The current music source doesn't support Listen together."
        override val unsupportedNote =
            "Listen together relies on the room protocol of the source itself. " +
                "Actions show up here only after you switch to a source that supports it."

        override val sectionCreate = "Create a room"
        override val createRoom = "Create a Listen together room"
        override val creating = "Working…"
        override val createRoomNote = "Send the invite link to the other person once it's created"
        override val oneRoomNote =
            "An account can only be in one room at a time. If you're already in one, leave it " +
                "before creating a new one — creating pushes out the current room, and once a " +
                "room ends it can't be restored."

        override val sectionJoin = "Join a room"
        override val inviteTitle = "Invite link or room ID"
        override val invitePlaceholder = "Paste the invite link you received"
        override val join = "Join"
        override val inviteNote =
            "An invite link carries both the room ID and the inviter's user ID — neither can be " +
                "missing. If the link is invalid or the room has ended, the server returns only " +
                "a generic error, so we \"cannot tell\" whether the room doesn't exist or the " +
                "invite wasn't meant for you. That's why no more specific reason is given here."

        override val sectionOther = "Other"
        override val refreshRoomState = "Refresh room state"

        override fun roomId(id: String) = "Room ID: $id"
        override fun memberCount(count: Int) =
            if (count == 1) "1 member" else "$count members"
        override val ownerTag = " · you're the host"

        override val ttlUnknown = " · length unknown"
        override val ttlExpired = " · expired — consider creating a new room"
        override val ttlImminent = " · expiring soon"
        override fun ttlLeft(minutes: Long) = " · about $minutes min left"

        override val ttlShortUnknown = "unknown length"
        override val ttlShortExpired = "expired"
        override val ttlShortImminent = "expiring soon"
        override fun ttlShortLeft(minutes: Long) = "$minutes min left"

        // "NetEase Cloud Music" 是**品牌名**，中文侧写作「网易云」—— 有意的，别当成漏译改掉。
        override fun pollNote(seconds: Long) =
            "Room state syncs every $seconds seconds — NetEase Cloud Music doesn't push to " +
                "third parties, so this delay comes from the protocol, not a slow network."

        override val sectionRoom = "Room"
        override val refresh = "Refresh"

        override val sectionInvite = "Invite the other person"
        override val shareInvite = "Share invite link"
        override val copyInvite = "Copy invite link"
        override val shareInviteNote = "Send it through any chat app"
        override val copyInviteNote = "Copied to the clipboard — paste it to your friend"
        override val qrHint = "Or have them scan this QR code to join:"
        override val noLinkWarning =
            "The invite link isn't available yet: the room info hasn't synced, or your account " +
                "profile isn't ready. Try refreshing."
        // "IM" 是站内即时通讯的通用说法，刻意保留英文缩写，别当漏译改掉。
        override val inviteUnavailableNote =
            "NetEase Cloud Music has no \"send invite\" endpoint — the official client's invite " +
                "goes through its in-app IM, which third parties can't call. So an invite always " +
                "means you hand the link to the other person yourself; there's no way to push " +
                "it into their inbox with a single tap."

        override val sectionLeave = "Leave"
        override val leaveRoom = "Leave room"
        override val leaveRoomNote = "The room ends immediately and can't be restored"
        override val leaveConfirmTitle = "Leave the Listen together room?"
        override val leaveConfirmMessage =
            "The room ends the moment you leave and \"cannot be restored\". If you're the host, " +
                "the other person is disconnected as well."

        override val openRoom = "Open the Listen together room"
    }

    override val chat: SocialStrings.Chat = object : SocialStrings.Chat {
        override val titleFallback = "Direct message"
        override val loginRequired = "Sign in to send direct messages"
        override val accountBound = "Direct messages are tied to your account"
        override val loading = "Loading conversation"
        override val empty = "No messages yet"
        override val emptyHint = "Type the first line below"
        override val inputPlaceholder = "Say something…"
        override val send = "Send"
        override val sendFailed = "Couldn't send — check that you're signed in"
        override val emptyMessage = "(empty message)"
        override val loadFailed = "Couldn't load direct messages"
    }

    override val messages: SocialStrings.Messages = object : SocialStrings.Messages {
        override val title = "Messages"
        override val loginRequired = "Sign in to view direct messages"
        override val loginRequiredNote =
            "Messages are tied to your account — sign in to the current source under " +
                "\"Account & Sign-in\" first"
        override val accountBound = "Messages are tied to your account"
        override val loading = "Loading messages"
        override val loadingNote = "Reading recent contacts from the current source"
        override val empty = "No messages yet"
        override val emptyHint =
            "Tap \"Send message\" on an artist or user profile to start chatting"
        override val selectConversation = "Select a conversation on the left"
        override val selectConversationNote =
            "Pick a contact on the left and the conversation with them shows up here"
        override val unknownUser = "Unknown user"
        override val noPreview = "(no message content)"
        override val loadFailed = "Couldn't load messages"
    }

    override val comment: SocialStrings.Comment = object : SocialStrings.Comment {
        override val title = "Comments"
        override val like = "Like"
        override val unlike = "Unlike"
    }
}
