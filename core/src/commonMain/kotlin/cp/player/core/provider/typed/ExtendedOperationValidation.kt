package cp.player.core.provider.typed

import cp.player.sdk.*

/** Validate before creating a provider task, so malformed mutations cannot reach an adapter. */
internal fun validateExtendedRequest(request: Any) {
    val values = when (request) {
        is Bytes -> {
            require(request.value.isEmpty())
            return
        }
        is PageRequest -> request.values()
        is CursorRequest -> request.values()
        is IdRequest -> request.values()
        is UserRequest -> request.values()
        is PagedIdRequest -> request.values()
        is SearchRequest -> request.values().also {
            require(request.keyword.isNotBlank() && request.keyword.length <= 4096)
        }
        is CommentQuery -> request.values().also {
            require(request.type in setOf("music", "song", "mv", "playlist", "album", "dj", "video", "event"))
            require(request.sortType in 1..3)
        }
        is AlbumPageRequest -> request.values().also { require(request.area.length in 1..32) }
        is AlbumChartRequest -> request.values().also {
            require(request.area.length in 1..32 && request.kind in setOf("new", "hot"))
            request.year?.let { require(it in 1..9999) }
            request.month?.let { require(it in 1..12) }
        }
        is UserCursorRequest -> request.values()
        is PlaylistOrderRequest -> request.values().also { validateOrder(request.playlistIds) }
        is PlaylistTrackOrderRequest -> request.values().also { validateOrder(request.trackIds) }
        is PlaylistCoverRequest -> request.values()
        is ImageUploadRequest -> request.values().also {
            validateImage(request.filename, request.mimeType, request.content)
        }
        is PlaylistImageUploadRequest -> request.values().also {
            validateImage(request.filename, request.mimeType, request.content)
        }
        is PlaylistImportRequest -> request.values().also {
            require(request.name.isNotBlank() && request.name.length <= 256)
            require(listOf(request.tracks.isNotEmpty(), request.text != null, request.links.isNotEmpty()).count { it } == 1)
            request.text?.let { require(it.isNotBlank()) }
            request.links.forEach(::validateHttpUrl)
        }
        is CloudUploadPrepareRequest -> request.values().also {
            validateFilename(request.filename)
            require(request.sizeBytes > 0 && request.bitrate > 0)
            require(Regex("[0-9a-fA-F]{32}").matches(request.md5))
        }
        is CloudUploadChunkRequest -> request.values().also {
            require(request.content.size in 1..MAX_UPLOAD_CHUNK_BYTES)
            require(request.offset in 0..(MAX_SAFE_INTEGER - request.content.size))
        }
        is CloudUploadCompleteRequest -> request.values()
        is DjRankingRequest -> request.values().also { require(request.kind in setOf("new", "hot")) }
        is PhoneBindingRequest -> request.values().also {
            validatePhone(request.phone, request.countryCode, request.captcha)
            request.password?.let { require(it.isNotBlank() && it.length <= 256) }
        }
        is PhoneReplacementRequest -> request.values().also {
            validatePhone(request.phone, request.countryCode, request.captcha)
            require(request.oldCaptcha.isNotBlank() && request.oldCaptcha.length <= 128)
        }
        is MediaQualityRequest -> request.values().also {
            require(request.type in setOf("song", "audio", "mv", "video"))
        }
        is ResourceMessageRequest -> request.values().also {
            require(request.userIds.size in 1..100)
            require(request.type in setOf("song", "album", "playlist"))
        }
        is DeleteCommentRequest -> request.values().also { require(request.commentId > 0) }
        is MessageHistoryRequest -> request.values()
        is SendMessageRequest -> request.values().also {
            require(request.userIds.size in 1..100 && request.text.isNotBlank())
        }
        is ListenInvitationRequest -> request.values()
        is ListenRoomRequest -> request.values()
        is ListenHeartbeatRequest -> request.values().also { validatePlayStatus(request.playStatus) }
        is ListenCommandRequest -> request.values().also { validateListenCommand(request.command) }
        is ListenQueueRequest -> request.values().also {
            require(request.action in setOf("REPLACE", "ADD", "DELETE"))
        }
        is VideoBrowseRequest -> request.values()
        is VideoResolveRequest -> request.values().also { require(request.resolution in 1..16384) }
        is SubscriptionRequest -> request.values()
        is DjProgramsRequest -> request.values()
        is CalendarRequest -> request.values().also {
            val start = request.startAtEpochMs
            val end = request.endAtEpochMs
            if (start != null && end != null) require(start <= end)
        }
        is EventPageRequest -> request.values()
        is UserEventRequest -> request.values()
        is ForwardEventRequest -> request.values()
        is ShareResourceRequest -> request.values()
        is PlaylistUpdateRequest -> request.values().also {
            require(request.name != null || request.description != null || request.tags != null)
            request.name?.let { require(it.isNotBlank() && it.length <= 256) }
            request.tags?.let { tags ->
                require(tags.size <= 20 && tags.all { it.isNotBlank() && it.length <= 128 })
            }
        }
        is UserProfileUpdateRequest -> request.values().also {
            require(request.values().values.any { it != null })
            request.nickname?.let { require(it.isNotBlank() && it.length <= 256) }
            request.gender?.let { require(it in 0..2) }
            request.province?.let { require(it >= 0) }
            request.city?.let { require(it >= 0) }
        }
        is FollowUserRequest -> request.values()
        is CloudImportRequest -> request.values()
        is CloudMatchRequest -> request.values()
        is ArtistBrowseRequest -> request.values().also {
            require(request.type in setOf(-1, 1, 2, 3) && request.initial.length <= 16)
        }
        is HistoryTracksRequest -> request.values().also {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(request.date))
        }
        is TrackAvailabilityRequest -> request.values().also { require(request.bitrate > 0) }
        else -> error("Unknown typed request")
    }
    validateExtendedValues(values)
    (values["limit"] as? Int)?.let { require(it in 1..MAX_ITEMS) }
    (values["offset"] as? Int)?.let { require(it >= 0) }
}

internal const val MAX_UPLOAD_CHUNK_BYTES = 1024 * 1024

private fun validateOrder(ids: List<String>) {
    require(ids.size in 1..MAX_ITEMS && ids.distinct().size == ids.size)
}

private fun validateFilename(filename: String) {
    require(filename.isNotBlank() && filename.length <= 256)
    require(filename !in setOf(".", "..") && filename.none { it in "/\\:*?\"<>|" || it.code < 32 })
}

private fun validateImage(filename: String, mimeType: String, content: ByteArray) {
    validateFilename(filename)
    require(mimeType in setOf("image/jpeg", "image/png", "image/webp"))
    require(content.size in 1..MAX_UPLOAD_CHUNK_BYTES)
}

private fun validatePhone(phone: String, countryCode: String, captcha: String) {
    require(Regex("[0-9]{4,20}").matches(phone) && Regex("[0-9]{1,4}").matches(countryCode))
    require(captcha.isNotBlank() && captcha.length <= 128)
}

/** Bounds apply to the whole result through WireWriter as well as each nested value. */
internal fun validateExtendedResult(values: Map<String, Any?>, request: Map<String, Any?>) {
    validateExtendedValues(values)
    val items = values["items"] as? List<*>
    items?.let {
        val limit = request["limit"] as? Int ?: MAX_ITEMS
        require(it.size <= limit)
        (values["total"] as? Long)?.let { total -> require(total >= it.size) }
    }
    (values["intervalMs"] as? Long)?.let { require(it > 0) }
    if (values["inRoom"] == true) require(values["room"] != null)
    if (values.containsKey("url")) validateMediaResource(MediaResource.fromValues(values))
    if (values.containsKey("action")) validateListenCommand(ListenCommand.fromValues(values))
    (values["comments"] as? List<*>)?.let {
        require(it.size <= (request["limit"] as? Int ?: MAX_ITEMS))
        require((values["totalCount"] as Long) >= it.size)
        require((values["sortType"] as Int) in 1..3)
    }
    if ("chunkSize" in values) {
        val chunkSize = values["chunkSize"] as Int
        require(chunkSize in 1..MAX_UPLOAD_CHUNK_BYTES)
        require((values["offset"] as Long) <= (request["sizeBytes"] as Long))
    }
    if ("uploadId" in values && "uploadId" in request) require(values["uploadId"] == request["uploadId"])
    if ("complete" in values && request["content"] is ByteArray) {
        require(values["offset"] == (request["offset"] as Long) + (request["content"] as ByteArray).size)
        require(values["complete"] == request["last"])
    }
    if ("taskId" in values) {
        require(values["status"] in setOf("pending", "running", "completed", "failed"))
        if ("id" in request) require(values["taskId"] == request["id"])
    }
    if (items?.firstOrNull().let { it is Map<*, *> && "available" in it && "label" in it }) {
        val ids = items!!.map { (it as Map<*, *>)["id"] }
        require(ids.distinct().size == ids.size)
    }
}

private fun validatePlayStatus(value: String) {
    require(value in setOf("PLAY", "PAUSE"))
}

private fun validateListenCommand(command: ListenCommand) {
    require(command.action in setOf("PLAY", "PAUSE", "PROGRESS", "GOTO"))
    validatePlayStatus(command.playStatus)
    if (command.action == "GOTO") require(command.trackId != null)
}

private fun validateExtendedValues(values: Map<String, Any?>, depth: Int = 0) {
    require(depth <= 12)
    if ("action" in values && "positionMs" in values && "sequence" in values)
        validateListenCommand(ListenCommand.fromValues(values))
    val startsAt = values["startsAtEpochMs"] as? Long
    val endsAt = values["endsAtEpochMs"] as? Long
    if (startsAt != null && endsAt != null) require(startsAt <= endsAt)
    for ((name, value) in values) {
        when (value) {
            is String -> {
                require(value.length <= if (name in setOf("description", "signature")) 65536 else 16384)
                if (name == "id" || name.endsWith("Id")) validateId(value)
                if (name.endsWith("Url") || name == "url") validateHttpUrl(value)
                if (name in setOf("title", "nickname", "type", "action", "playStatus"))
                    require(value.isNotBlank() && value.length <= 4096)
                if (name == "type") require(value.length <= 32)
                if (name == "cursor") require(value.length <= 4096)
                if (name in setOf("uploadId", "taskId")) validateId(value)
            }
            is Long -> require(value in (if (name == "birthdayEpochMs") -MAX_SAFE_INTEGER else 0)..MAX_SAFE_INTEGER)
            is Int -> if (name !in setOf("type", "area")) require(value >= 0)
            is ByteArray -> require(value.size <= MAX_UPLOAD_CHUNK_BYTES)
            is List<*> -> {
                require(value.size <= MAX_ITEMS)
                for (item in value) {
                    if (item is String) {
                        require(item.length <= 16384)
                        if (name.endsWith("Ids")) validateId(item)
                        if (name == "imageUrls") validateHttpUrl(item)
                    } else if (item is Map<*, *>) {
                        validateExtendedValues(valueObject(item), depth + 1)
                    }
                }
            }
            is Map<*, *> -> validateExtendedValues(valueObject(value), depth + 1)
        }
    }
}

private fun validateMediaResource(value: MediaResource) {
    validateHttpUrl(value.url)
    require(value.actualQuality.isNotBlank() && value.actualQuality.length <= 128)
    require(value.headers.size <= 64)
    value.headers.forEach {
        require(it.name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
        require(it.value.length <= 8192 && it.value.none { c -> c == '\r' || c == '\n' || c == '\u0000' })
    }
    require(value.headers.map { it.name.lowercase() }.distinct().size == value.headers.size)
    value.bitrate?.let { require(it > 0) }
}
