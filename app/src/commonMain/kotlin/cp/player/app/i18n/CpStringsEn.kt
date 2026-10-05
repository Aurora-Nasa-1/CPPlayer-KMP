package cp.player.app.i18n

/**
 * English strings.
 *
 * This is deliberately **not** the fallback locale: `CpStringsZh` is (see
 * [cp.player.app.i18n.AppLanguage] for why Chinese is the fallback).
 *
 * Every member here must mirror [CpStringsZh] — both implement [CpStrings], so a missing
 * member is a compile error rather than a silent fallback to another language.
 */
object CpStringsEn : CpStrings {
    override val language: LanguageStrings = object : LanguageStrings {
        override val screenTitle = "Language"
        override val optionSystem = "Follow system"
        override val optionSystemNote = "Use the system language; falls back to Simplified Chinese when unsupported"
        override val optionZhHans = "Simplified Chinese"
        override val optionEnglish = "English"
        override val current = "Current"
        override val applyNote = "Takes effect immediately — no restart required."
    }

    override val settings: SettingsStrings = object : SettingsStrings {
        override val screenTitle = "Settings"
        override val groupGeneral = "General"
        override val groupAccountProvider = "Accounts & Sources"
        override val groupConnectivity = "Connections & Integrations"
        override val groupOther = "Other"

        override val itemLanguage = CpTextPair(
            title = "Language",
            subtitle = "Follow the system, or pin to Simplified Chinese / English",
        )
        override val itemAppearance = CpTextPair(
            title = "Appearance & Theme",
            subtitle = "Theme mode, color source, and pure black background",
        )
        override val itemPlayback = CpTextPair(
            title = "Playback & Quality",
            subtitle = "Default audio quality and sleep timer",
        )
        override val itemStorage = CpTextPair(
            title = "Downloads & Storage",
            subtitle = "Download folder, song cache, and image cache",
        )
        override val itemShortcuts = CpTextPair(
            title = "Keyboard Shortcuts",
            subtitle = "View and customize desktop shortcuts",
        )
        override val itemAccount = CpTextPair(
            title = "Accounts & Sign-in",
            subtitle = "Sign in to sources, switch, and manage saved accounts",
        )
        override val itemProviders = CpTextPair(
            title = "Music Sources",
            subtitle = "Import, switch, or remove source modules",
        )
        override val itemStreamOutput = CpTextPair(
            title = "Local Stream Output",
            subtitle = "Serve audio streams over HTTP",
        )
        override val itemIntegration = CpTextPair(
            title = "Push & Integrations",
            subtitle = "Push to receivers and expose third-party APIs",
        )
        override val itemStandby = CpTextPair(
            title = "LAN Devices",
            subtitle = "Other CPPlayer apps on the same network: discover each other and auto-sync listening history",
        )
        override val itemAbout = CpTextPair(
            title = "About & Support",
            subtitle = "Version, updates, and project support",
        )
        override val itemDiagnostics = CpTextPair(
            title = "Diagnostics",
            subtitle = "View API call status, logs, and fallback info",
        )
        override val itemRenderTuning = CpTextPair(
            title = "Render Backend",
            subtitle = "Display backend and vsync; adjust if you see tearing or stutter",
        )
        override val itemOnboarding = CpTextPair(
            title = "Replay Onboarding",
            subtitle = "Walk through first-time setup again",
        )
    }

    override val common: CommonStrings = object : CommonStrings {
        override val confirm = "Confirm"
        override val dismiss = "Cancel"
        override val apply = "Apply"
        override val unsavedBlocked = "Not saved · fix the problem above first"
        override val unsavedHint = "Not saved · press Apply to keep it"
    }

    override val appearance: AppearanceStrings = object : AppearanceStrings {
        override val screenTitle = "Appearance & Theme"
        override val sectionLook = "Look"
        override val sectionFont = "Typography"

        override val themeMode = "Theme mode"
        override val themeModeSystem = "Follow system"
        override val themeModeLight = "Light"
        override val themeModeDark = "Dark"

        override val colorSource = "Color source"
        override val colorSourcePlatform = "Follow system"
        override val colorSourceCover = "Follow artwork"
        override val colorSourceFixed = "Fixed palette"
        override val colorSourcePlatformOn = "Taken from the system accent color (Android wallpaper / Windows accent)"
        override val colorSourcePlatformOff = "Not supported on this platform — falls back to the fixed palette"
        override val colorSourceCoverNote =
            "Taken from the current track's artwork; falls back to the system wallpaper when nothing is playing"
        override val colorSourceFixedNote = "Always uses the built-in palette, independent of content and system"

        override val pureBlack = "Pure black"
        override val pureBlackNote = "Use a pure black background in the dark theme — easier on OLED screens"
        override val autoHideBottomBar = "Auto-hide bottom bar"
        override val autoHideBottomBarNote =
            "Collapse the bottom navigation bar when scrolling up, bring it back when scrolling down"
        override val coverFlight = "Cover flight animation"
        override val coverFlightNote =
            "Transition that flies the artwork into the player or detail page when you tap a track or " +
                "playlist cover. Turn it off for a snappier tap."

        override val fontRoundness = "Font roundness"
        override fun fontRoundnessNote(defaultRoundness: Int) =
            "The ROND variable axis of Google Sans Flex: 0 is square, 100 is roundest. " +
                "Android 16 and above default to 100; other platforms default to $defaultRoundness."
        override val roundnessDefaultTag = " · Default"
        override val resetPlatformDefault = "Restore platform default"
        override val resetPlatformDefaultNote = "Clear the custom value and go back to this platform's default"
        override val roundnessNote =
            "Roundness applies immediately to Latin glyphs across the whole interface. " +
                "Chinese glyphs come from the system fallback font and are not affected."
    }

    override val storage: StorageStrings = object : StorageStrings {
        override val screenTitle = "Downloads & Storage"

        override val sectionDownload = "Downloads"
        override val downloadedMusic = "Downloaded music"
        override val downloadedMusicEmpty = "Nothing downloaded yet — use the button above or download from search"
        override fun downloadedMusicSummary(count: Int, bytes: String) = "$count tracks · $bytes"
        override val downloadDir = "Download folder"
        override val downloadDirAndroid = "On Android, downloads always go to the app's private folder"
        override val downloadDirDefault = "Default download folder"
        override val openDir = "Open folder"
        override val openDirNote = "View the downloaded files in your file manager"
        override val dirUnset = "No download folder set"
        override fun openDirFailed(dir: String) = "Couldn't open folder: $dir"

        override val sectionSongCache = "Song cache"
        override val cachedSongs = "Cached songs"
        override val cachedSongsEmpty = "Nothing cached — lossless tracks are cached as you play them"
        override fun cachedSongsSummary(count: Int, bytes: String, capacity: String) =
            "$count tracks · $bytes of $capacity"
        override val cacheCapacity = "Capacity limit"
        override val cacheCapacityNote = "Lowering the limit trims the least recently played tracks right away"
        override val clearStaleCache = "Clear tracks unplayed for 30 days"
        override val clearStaleCacheNote = "Only drops what you haven't listened to lately"
        override val clearSongCache = "Clear song cache"
        override val clearSongCacheNote = "Deletes every local copy; downloaded music is not affected"
        override val clearSongCacheConfirmTitle = "Clear the song cache?"
        override fun clearSongCacheConfirmMessage(count: Int, bytes: String) =
            "This deletes $count cached tracks (about $bytes). Downloaded music is not affected; " +
                "these lossless tracks will be cached again next time you play them."
        override val openCacheDir = "Open cache folder"
        override val openCacheDirNote = "Inspect or back up the cached files in your file manager"
        override val openCacheDirFailed = "Couldn't open the cache folder"

        override val sectionApiCache = "API cache"
        override val apiCacheEntries = "Cache entries"
        override val apiCacheEmpty = "Nothing cached"
        override fun apiCacheEntryCount(count: Int) = "$count entries"
        override fun apiCacheHitRate(rate: Int) = " · $rate% hit rate this session"
        override val clearApiCache = "Clear API cache"
        override val clearApiCacheNote =
            "Read cache for track and playlist metadata; entries are fetched from the source again after clearing"

        override val sectionImageCache = "Image cache"
        override val imageCache = "Image cache"
        override val imageCacheMeasuring = "Measuring…"
        override fun imageCacheUsage(bytes: String) = "$bytes used"
        override val clearImageCache = "Clear image cache"
        override val clearImageCacheNote = "Frees the space used by covers and other images; downloads are kept"

        override val noteAndroid =
            "Changing the download folder only affects future downloads — existing files stay put. " +
                "Clearing any cache never deletes downloaded music."
        override val noteDesktop =
            "Changing the download folder only affects future downloads — existing files stay put; " +
                "move them yourself after opening the folder. Clearing any cache never deletes downloaded music."
        override val dirUpdated = "Download folder updated — applies to future downloads only"
    }

    override val songCache: SongCacheStrings = object : SongCacheStrings {
        override val screenTitle = "Song cache"
        override val unsupported =
            "This platform doesn't use a disk song cache. Lossless streaming is cached on desktop only: " +
                "the desktop engine can't seek in network FLAC, so the track has to land on disk first."
        override val measuring = "Measuring the cache…"
        override val empty = "Nothing cached yet. Lossless tracks are cached locally as you play them."
        override val measuringShort = "Measuring…"
        override val emptyShort = "Nothing cached"
        override fun summary(count: Int, bytes: String) = "$count tracks · $bytes"
        override fun summaryCapped(count: Int, bytes: String, capacity: String) =
            "$count tracks · $bytes of $capacity"
        override val searchPlaceholder = "Search title, artist, or quality"
        override val clearSearch = "Clear search"
        override val unknownTrack = "Unknown track"
        override fun noMatch(query: String) = "No cached track matches \"$query\"."
        override fun deleteEntry(name: String) = "Delete the cached copy of \"$name\""
        override fun deleted(name: String, freed: String) =
            "Deleted the cached copy of \"$name\", freeing $freed"
        override val deleteFailed = "Couldn't delete it — the file may be playing right now"

        override val timeUnknown = "Time unknown"
        override val timeJustNow = "Just now"
        // 英文里数量词在名词之后（"5 min ago"），中文在之前（"5 分钟前"）——
        // 所以这五条必须是文案而不是在调用方拼。
        override fun timeMinutesAgo(minutes: Long) = "$minutes min ago"
        override fun timeHoursAgo(hours: Long) = "$hours hr ago"
        override fun timeDaysAgo(days: Long) = "$days d ago"
        override fun timeMonthsAgo(months: Long) = "$months mo ago"
    }

    override val playback: PlaybackStrings = object : PlaybackStrings {
        override val screenTitle = "Playback & Quality"

        override val sectionQuality = "Quality"
        override val defaultQuality = "Default quality"
        override val defaultQualityNote =
            "Preferred quality for streaming over Wi-Fi and unmetered networks; falls back when the source can't provide it"
        override val meteredQuality = "Quality on mobile data"
        override val meteredQualityNote =
            "Applies on cellular and hotspots; takes effect from the next track after you switch networks"

        override val sectionLyrics = "Lyrics"
        override val lyricsSource = "Lyrics source"
        override val lyricsSourceNote =
            "AMLL is a word-by-word lyrics database (better translations and romanization). " +
                "With AMLL first, a miss falls back to the source's lyrics. Applies on the next refresh."
        override val lyricsProviderOnly = "Source API only"
        override val lyricsAmllFirst = "AMLL first"
        override val lyricsAmllOnly = "AMLL only"

        override val sectionSleepTimer = "Sleep timer"
        override val sleepTimer = "Sleep timer"
        override val sleepAfterTrack = "Pause after the current track"
        override fun sleepRemaining(minutes: Long) = "$minutes min left"
        override val sleepOff = "Off"

        override val sectionBackground = "Background playback"
        override val batteryWhitelist = "Battery optimization whitelist"
        override val batteryWhitelistOn = "Added — background playback is protected while the screen is off"
        override val batteryWhitelistOff = "Not enabled — the system may kill background playback once the screen is off. Tap to request"
        override val vendorNote =
            "Some vendor systems (MIUI/HyperOS, HarmonyOS, ColorOS and others) also need CPPlayer " +
                "allowed under \"Autostart management\"."
        override val sharedTimerNote =
            "This opens the same dialog as the sleep timer on the player screen, so both stay in sync."

        override val timerDialogTitle = "Sleep timer"
        override fun timerActiveAfterTrack() = "Currently: pausing after this track"
        override fun timerActiveInMinutes(minutes: Long) = "Currently: pausing in ${minutes + 1} min"
        override val timerPrompt = "Pause playback in how many minutes?"
        override fun timerMinutesChip(minutes: Int) = "$minutes min"
        override val timerAfterTrackChip = "After this track"
        override val timerCancel = "Cancel timer"
        override val timerClose = "Close"
    }

    override val shortcuts: ShortcutStrings = object : ShortcutStrings {
        override val screenTitle = "Keyboard Shortcuts"
        override val note =
            "Shortcuts work on any screen, except while a text field has focus " +
                "(letter and number keys still type as usual). Tap any row to record a new key."
        override val unbound = "Not bound"

        override val categoryPlayback = "Playback controls"
        override val categoryMode = "Playback mode & favorites"
        override val categoryNavigation = "Navigation & window"

        override val keySpace = "Space"
        override val keyEnter = "Enter"
        override val keyBackspace = "Backspace"

        override val actionPlayPause = "Play / Pause"
        override val actionPlayPauseHint = "Toggle playback for the current track"
        override val actionPrevTrack = "Previous track"
        override val actionPrevTrackHint = "Jump to the previous track in the queue"
        override val actionNextTrack = "Next track"
        override val actionNextTrackHint = "Jump to the next track in the queue"
        override val actionSeekBackward = "Back 5 seconds"
        override val actionSeekForward = "Forward 5 seconds"
        override val seekHint = "Only works on seekable tracks (ignored when the duration is unknown)"
        override val actionToggleFavorite = "Favorite / Unfavorite"
        override val actionToggleFavoriteHint = "Favorite the current track; needs a signed-in account"
        override val actionToggleShuffle = "Shuffle"
        override val actionToggleShuffleHint = "Turn shuffling on / off for the current queue"
        override val actionCycleRepeat = "Cycle repeat mode"
        override val actionCycleRepeatHint = "Off → Repeat all → Repeat one → Off"
        override val actionBack = "Back"
        override val actionBackHint =
            "Same as the back button in the title bar: leaves the current page or panel, and does nothing at the top"
        override val actionOpenSettings = "Open settings"
        override val actionOpenSettingsHint = "Return to the main screen from anywhere and open settings"

        override val resetAll = "Restore all defaults"
        override val resetAllNote = "Discard every custom key and go back to factory settings"
        override val resetAllConfirmTitle = "Restore all defaults?"
        override val resetAllConfirmMessage = "All custom shortcuts will be lost and reset to their default keys."
        override val resetLabel = "Restore"

        override val recorderTitle = "Set shortcut"
        override fun recorderTarget(label: String) = "\"$label\""
        override val recorderWaiting = "Press a new key combination…"
        override val recorderWaitingHint = "Press the combination you want, for example Ctrl + Shift + K"
        override val recorderRecordedHint = "Press again to pick a different combination"
        override fun recorderConflict(others: String) =
            "Heads up: \"$others\" already uses this combination, and both will be active after saving."
        override val recorderSave = "Save"
        override val recorderReset = "Restore default"
        override val recorderClear = "Clear"
    }

    override val quality: QualityStrings = object : QualityStrings {
        override fun labelOf(level: String) = when (level) {
            "standard" -> "Standard"
            "higher" -> "High"
            "exhigh" -> "Very high"
            "lossless" -> "Lossless"
            "hires" -> "Hi-Res"
            "jymaster" -> "Master"
            "sky" -> "Immersive"
            "jyeffect" -> "Effects"
            // Unknown levels pass through: they're identifiers from the music source, not UI copy.
            else -> level
        }
    }
}
