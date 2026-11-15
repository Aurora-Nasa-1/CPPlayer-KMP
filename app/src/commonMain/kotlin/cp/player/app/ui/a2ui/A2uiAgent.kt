package cp.player.app.ui.a2ui

import cp.player.app.AppModel
import cp.player.app.i18n.AiStrings
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import cp.player.core.playback.QueueItem
import cp.player.core.util.currentTimeMillis
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class A2uiTrackRef(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mediaId", mediaId)
        put("title", title)
        put("artist", artist)
        album?.let { put("album", it) }
        coverUrl?.let { put("coverUrl", it) }
        put("durationMs", durationMs)
    }

    companion object {
        fun from(track: TrackSummary): A2uiTrackRef = A2uiTrackRef(
            mediaId = AppModel.toMediaId(track.id),
            title = track.name,
            artist = track.artist,
            album = track.album,
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
        )

        fun from(item: QueueItem): A2uiTrackRef = A2uiTrackRef(
            mediaId = item.mediaId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            coverUrl = item.coverUrl,
            durationMs = item.durationMs,
        )
    }
}

/**
 * 官方 Basic Catalog 的按钮变体。
 *
 * ⚠️ 线上取值只有这三个：`default` / `primary` / `borderless`。
 * 写成 `"secondary"` 这类看起来合理的名字**不会报错**，而是被 Catalog 当成未知状态、
 * 直接渲染成「错误」占位按钮 —— 这一点是出图核对时发现的，不要凭直觉加变体名。
 */
internal enum class ButtonVariant(val wireValue: String) {
    Default("default"),
    Primary("primary"),
    Borderless("borderless"),
}

internal data class A2uiAgentContext(
    val prompt: String,
    val nowPlaying: A2uiTrackRef?,
    val queue: List<A2uiTrackRef>,
) {
    val allTracks: List<A2uiTrackRef>
        get() = (listOfNotNull(nowPlaying) + queue).distinctBy { it.mediaId }.take(12)

    companion object {
        fun fromCurrentPlayer(prompt: String): A2uiAgentContext {
            val state = AppModel.playback.state.value
            return A2uiAgentContext(
                prompt = prompt,
                nowPlaying = state.currentTrack?.let(A2uiTrackRef::from),
                queue = state.queue.map(A2uiTrackRef::from).take(12),
            )
        }
    }
}

internal object A2uiAgent {
    fun systemPrompt(context: A2uiAgentContext, strings: AiStrings): String {
        val tracksJson = buildJsonArray {
            context.allTracks.forEach { add(it.toJson()) }
        }
        return """
            你是 CPPlayer 的 A2UI v0.9 界面编排器。根据用户的听歌场景，生成一个可直接操作的 A2UI 界面。

            硬性规则：
            1. 只输出 A2UI v0.9 JSON 消息，一条消息一个 JSON 对象，不要 Markdown 代码围栏，不要解释文字。
            2. 第一条必须是 createSurface，surfaceId 自己生成，catalogId 必须精确等于：
               $A2UI_CATALOG_ID
            3. 第二条必须是 updateComponents，使用官方 Basic Catalog V1 的组件：Text、Column、Row、Card、Divider、Button、Image。
            4. 不要发明组件、属性或事件名。根组件 id 用 "root"。
            5. 按钮只能发送以下事件：
               play_track（context.mediaId）、play_tracks（context.mediaIds 数组）、
               enqueue_track（context.mediaId）、enqueue_all（context.mediaIds 数组）、
               search_similar（context.query）、set_eq_preset（context.preset）、
               send_prompt（context.prompt）。
            6. mediaId 只能使用下面提供的真实曲目，绝对不能编造歌曲、歌手、封面或 id。
            7. ${strings.interfaceLanguageDirective}
            8. 如果没有可播放曲目，只提供 search_similar 按钮，不要伪造曲目。

            当前播放与队列（JSON）：
            $tracksJson

            可用组件属性示例：
            {"id":"root","component":"Card","child":"content"}
            {"id":"content","component":"Column","children":["title","body"]}
            {"id":"title","component":"Text","text":"示例标题","variant":"h3"}
            {"id":"body","component":"Text","text":"示例正文","variant":"body"}
            {"id":"play","component":"Button","child":"play_label","variant":"primary",
             "action":{"event":{"name":"play_track","context":{"mediaId":"真实 mediaId"}}}}
            {"id":"play_label","component":"Text","text":"播放"}
        """.trimIndent()
    }

    suspend fun localSurface(context: A2uiAgentContext, strings: AiStrings): List<String> {
        val searched = runCatching {
            val result = AppModel.musicRepository.search(context.prompt, type = 1)
            (result as? BackendResult.Success)?.data?.songs.orEmpty()
        }.getOrDefault(emptyList())

        val tracks = if (searched.isNotEmpty()) {
            searched.take(6).map(A2uiTrackRef::from)
        } else {
            context.queue.take(6)
        }

        return synthMessages(
            prompt = context.prompt,
            nowPlaying = context.nowPlaying,
            tracks = tracks,
            usedSearch = searched.isNotEmpty(),
            strings = strings,
        )
    }

    fun djMessages(
        track: A2uiTrackRef,
        queue: List<A2uiTrackRef>,
        strings: AiStrings,
    ): List<String> {
        val surfaceId = "cp_dj_${currentTimeMillis()}_${track.mediaId.hashCode().toUInt()}"
        val components = SurfaceComponents(strings)

        // 版式刻意只用**一层**信息层级：
        // 1. 当前曲目 = 封面 + 标题列（唯一的主视觉）；
        // 2. 主动作只留一个 `primary`，其余动作走 borderless，避免两个填充按钮互相抢主次；
        // 3. 队列是「接下来的曲目」，不再给每行套 Card —— 弹层本身已经是容器，
        //    再嵌一层描边会在手机上出现三层边框（出图实测过）。
        val eyebrow = components.text("eyebrow", strings.djDeskTitle, "caption")
        val heroTitle = components.text("hero_title", track.title, "h4")
        val heroArtist = components.text("hero_artist", track.artist, "body")
        val heroMeta = components.text(
            "hero_meta",
            listOfNotNull(track.album, formatDuration(track.durationMs))
                .joinToString(" · ")
                .ifBlank { strings.djNowPlayingFallback },
            "caption",
        )
        val heroText = components.column("hero_text", listOf(heroTitle, heroArtist, heroMeta))
        val heroChildren = mutableListOf<String>()
        if (!track.coverUrl.isNullOrBlank()) {
            heroChildren += components.image(
                "hero_cover",
                track.coverUrl,
                track.title,
                variant = "mediumFeature",
            )
        }
        heroChildren += heroText
        val hero = components.row("hero", heroChildren)

        // 通栏主操作：Button 本身按内容宽度收缩，套一层 Row 并用 `weight` 把它拉满。
        // `weight` 只对 Row/Column 的直接子节点生效，这是官方 Catalog 里唯一的「填满」手段。
        val play = components.row(
            "play_row",
            listOf(
                components.button(
                    "play",
                    strings.djPlayNow,
                    "play_track",
                    mapOf("mediaId" to track.mediaId),
                    variant = ButtonVariant.Primary,
                    weight = 1,
                ),
            ),
        )
        val secondary = components.row(
            "secondary_actions",
            listOf(
                components.button(
                    "next",
                    strings.djPlayNext,
                    "enqueue_track",
                    mapOf("mediaId" to track.mediaId),
                    variant = ButtonVariant.Borderless,
                ),
                components.button(
                    "similar",
                    strings.djFindSimilar,
                    "search_similar",
                    mapOf("query" to "${track.title} ${track.artist}"),
                    variant = ButtonVariant.Borderless,
                ),
            ),
            justify = "start",
        )
        // 解读入口是「同一件事的延伸」，不是第二个主操作：用 borderless，
        // 与上面那两颗次级动作同一挡位，避免一屏出现三个填充按钮。
        val askAi = components.button(
            "ask_ai",
            strings.djAskAi,
            "send_prompt",
            mapOf(
                "prompt" to strings.djAskAiPrompt(track.title, track.artist),
            ),
            variant = ButtonVariant.Borderless,
        )
        val eqTitle = components.text("eq_title", strings.djSoundTitle, "h5")
        val eqActions = components.row(
            "eq_actions",
            listOf(
                components.button(
                    "eq_vocal",
                    strings.djEqVocal,
                    "set_eq_preset",
                    mapOf("preset" to "vocal"),
                    variant = ButtonVariant.Default,
                ),
                components.button(
                    "eq_night",
                    strings.djEqNight,
                    "set_eq_preset",
                    mapOf("preset" to "night"),
                    variant = ButtonVariant.Default,
                ),
                components.button(
                    "eq_flat",
                    strings.djEqFlat,
                    "set_eq_preset",
                    mapOf("preset" to "flat"),
                    variant = ButtonVariant.Default,
                ),
            ),
        )

        val children = mutableListOf(eyebrow, hero, play, secondary, askAi)
        val upcoming = queue.filterNot { it.mediaId == track.mediaId }.take(4)
        if (upcoming.isNotEmpty()) {
            children += components.divider("queue_divider")
            children += components.text("queue_title", strings.djQueueTitle, "h5")
            upcoming.forEachIndexed { index, item ->
                children += components.queueRow("queue_$index", item)
            }
        }
        children += components.divider("eq_divider")
        children += eqTitle
        children += eqActions

        // 根就是 Column：外层弹层已经是一张 surface，再包 Card 只会多一圈描边。
        components.column("root", children)

        return messages(surfaceId, components.entries)
    }

    private fun synthMessages(
        prompt: String,
        nowPlaying: A2uiTrackRef?,
        tracks: List<A2uiTrackRef>,
        usedSearch: Boolean,
        strings: AiStrings,
    ): List<String> {
        val surfaceId = "cp_synth_${currentTimeMillis()}_${prompt.hashCode().toUInt()}"
        val components = SurfaceComponents(strings)
        val children = mutableListOf<String>()

        children += components.text("title", strings.djSynthTitle(prompt), "h3")
        children += components.text(
            "intro",
            if (usedSearch) strings.djSynthSearchedIntro else strings.djSynthContextIntro,
            "body",
        )

        if (nowPlaying != null) {
            children += components.divider("now_divider")
            children += components.text("now_title", strings.djSynthNowTitle, "h5")
            children += components.trackRow("now", nowPlaying)
        }

        if (tracks.isNotEmpty()) {
            children += components.divider("tracks_divider")
            children += components.text("tracks_title", strings.djSynthRecommendedTitle, "h5")
            tracks.forEachIndexed { index, track ->
                children += components.trackRow("track_$index", track)
            }
        } else {
            children += components.divider("empty_divider")
            children += components.text(
                "empty",
                strings.djSynthEmptyTracks,
                "body",
            )
            children += components.button(
                "search",
                strings.djSynthSearchAction,
                "search_similar",
                mapOf("query" to prompt),
                variant = ButtonVariant.Primary,
            )
        }

        val content = components.column("content", children)
        components.card("root", content)
        return messages(surfaceId, components.entries)
    }

    private fun messages(surfaceId: String, components: List<JsonObject>): List<String> {
        val create = buildJsonObject {
            put("version", A2UI_PROTOCOL_VERSION)
            putJsonObject("createSurface") {
                put("surfaceId", surfaceId)
                put("catalogId", A2UI_CATALOG_ID)
            }
        }.toString()
        val update = buildJsonObject {
            put("version", A2UI_PROTOCOL_VERSION)
            putJsonObject("updateComponents") {
                put("surfaceId", surfaceId)
                putJsonArray("components") {
                    components.forEach { add(it) }
                }
            }
        }.toString()
        return listOf(create, update)
    }
}

private class SurfaceComponents(private val strings: AiStrings) {
    val entries = mutableListOf<JsonObject>()

    fun text(id: String, text: String, variant: String = "body"): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Text")
            put("text", text)
            put("variant", variant)
        }
        return id
    }

    fun image(
        id: String,
        url: String,
        description: String,
        variant: String = "smallFeature",
    ): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Image")
            put("url", url)
            put("description", description)
            put("fit", "cover")
            put("variant", variant)
        }
        return id
    }

    fun row(id: String, children: List<String>, justify: String = "start"): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Row")
            putJsonArray("children") { children.forEach { add(it) } }
            put("justify", justify)
            put("align", "center")
        }
        return id
    }

    fun column(id: String, children: List<String>): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Column")
            putJsonArray("children") { children.forEach { add(it) } }
        }
        return id
    }

    fun card(id: String, child: String): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Card")
            put("child", child)
        }
        return id
    }

    fun divider(id: String): String {
        entries += buildJsonObject {
            put("id", id)
            put("component", "Divider")
        }
        return id
    }

    fun button(
        id: String,
        label: String,
        event: String,
        context: Map<String, Any?>,
        variant: ButtonVariant = ButtonVariant.Default,
        weight: Int? = null,
    ): String {
        val labelId = "${id}_label"
        text(labelId, label, "body")
        entries += buildJsonObject {
            put("id", id)
            put("component", "Button")
            put("child", labelId)
            put("variant", variant.wireValue)
            weight?.let { put("weight", it) }
            putJsonObject("action") {
                putJsonObject("event") {
                    put("name", event)
                    putJsonObject("context") {
                        context.forEach { (key, value) ->
                            when (value) {
                                is String -> put(key, value)
                                is List<*> -> putJsonArray(key) {
                                    value.filterIsInstance<String>().forEach { add(it) }
                                }
                                is Number -> put(key, value)
                                is Boolean -> put(key, value)
                                null -> put(key, null as String?)
                                else -> put(key, value.toString())
                            }
                        }
                    }
                }
            }
        }
        return id
    }

    fun trackRow(id: String, track: A2uiTrackRef): String {
        val textChildren = mutableListOf<String>()
        textChildren += text("${id}_title", track.title, "body")
        textChildren += text(
            "${id}_meta",
            listOfNotNull(track.artist, track.album, formatDuration(track.durationMs))
                .joinToString(" · "),
            "caption",
        )
        val textColumn = column("${id}_text", textChildren)
        val rowChildren = mutableListOf<String>()
        if (!track.coverUrl.isNullOrBlank()) {
            rowChildren += image("${id}_cover", track.coverUrl, track.title)
        }
        rowChildren += textColumn
        val infoRow = row("${id}_info", rowChildren)

        val buttons = row(
            "${id}_actions",
            listOf(
                button(
                    "${id}_play",
                    strings.djPlay,
                    "play_track",
                    mapOf("mediaId" to track.mediaId),
                    variant = ButtonVariant.Primary,
                ),
                button(
                    "${id}_queue",
                    strings.djEnqueue,
                    "enqueue_track",
                    mapOf("mediaId" to track.mediaId),
                ),
                button(
                    "${id}_similar",
                    strings.djFindSimilar,
                    "search_similar",
                    mapOf("query" to "${track.title} ${track.artist}"),
                ),
            ),
        )
        val cardContent = column("${id}_content", listOf(infoRow, buttons))
        return card("${id}_card", cardContent)
    }

    /**
     * 「队列接下来」的一行：信息 + 两枚 borderless 动作，不套 Card。
     *
     * 队列行与推荐曲目行的区别是**容器**：推荐区是页面主体，用 Card 分区；
     * 队列行位于同一个弹层内，再套 Card 就是三层描边（弹层 → Card → 行 → 按钮）。
     */
    fun queueRow(id: String, track: A2uiTrackRef): String {
        val title = text("${id}_title", track.title, "body")
        val meta = text(
            "${id}_meta",
            listOfNotNull(track.artist, formatDuration(track.durationMs).ifBlank { null })
                .joinToString(" · "),
            "caption",
        )
        val info = column("${id}_text", listOf(title, meta))
        val actions = row(
            "${id}_actions",
            listOf(
                button(
                    "${id}_play",
                    strings.djPlay,
                    "play_track",
                    mapOf("mediaId" to track.mediaId),
                    variant = ButtonVariant.Borderless,
                ),
                button(
                    "${id}_queue",
                    strings.djEnqueue,
                    "enqueue_track",
                    mapOf("mediaId" to track.mediaId),
                    variant = ButtonVariant.Borderless,
                ),
            ),
            justify = "end",
        )
        return row("${id}_row", listOf(info, actions), justify = "spaceBetween")
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
