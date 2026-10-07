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

    override val messageNotify: MessageNotifyStrings = object : MessageNotifyStrings {
        override val guideTitle = "Messages stay quiet by default"
        override val guideBody = "To be notified about someone, right-click (desktop) or long-press " +
            "(phone) a conversation and turn on new-message notifications. Nobody is watched by default."
        override val guideConfirm = "Got it"

        override val menuEnable = "Notify me about new messages"
        override val menuDisable = "Stop notifying about new messages"
        override val sheetTitle = "New message notifications"
        override val sheetBody = "When on, you are only notified about new messages from this person. Off by default."

        override val settingsTitle = "Message notifications"
        override val settingsSubtitle = "Which contacts may raise a system notification"
        override val masterLabel = "Allow direct-message notifications"
        override val masterHint = "Nothing is polled while this is off. Only works while the app is running"
        override val subscribedSection = "Contacts you follow"
        override val subscribedEmpty = "No contact is followed yet"
        override val unsupportedPlatform = "System notifications are not supported on this platform"
        override val permissionMissing = "System notification permission is off, so you will not be notified"
        override val grantPermission = "Grant permission"

        override val trayShowWindow = "Show CPPlayer"
        override val trayExit = "Quit CPPlayer"

        override val closeDialogTitle = "Close window"
        override val closeDialogMessage = "Quit CPPlayer, or keep it in the tray to receive message notifications?"
        override val closeDialogMinimize = "Keep in tray"
        override val closeDialogExit = "Quit"
        override val closeDialogDontAsk = "Don't ask again, remember my choice"

        override val closeBehaviorLabel = "When closing the window"
        override val closeBehaviorAsk = "Ask every time"
        override val closeBehaviorHint = "Only \u201Ckeep in tray\u201D lets you keep receiving message notifications after closing"
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

        override val fluidBackground = "Fluid background"
        override val fluidBackgroundNote =
            "A slowly drifting gradient behind the player, inspired by Apple Music. Colors follow the " +
                "current theme and artwork palette; Android 13 and below falls back to a static gradient. " +
                "Turn it off to save power."

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

        override val sectionLastPlayback = "Last playback"
        override val keepLastPlayback = "Keep last playback"
        override val keepLastPlaybackNote =
            "Restore your last queue and position on launch, without auto-playing; " +
                "press play to resume where you left off"

        override val sectionFade = "Fade in / out"
        override val fadeIn = "Fade in on start"
        override val fadeInNote = "Ramp volume up from silence when a new track begins"
        override val fadeOut = "Fade out before end"
        override val fadeOutNote = "Lower the volume near the end so the next track does not cut in"
        override val fadeDuration = "Transition length"
        override fun fadeDurationSeconds(seconds: String) = "$seconds sec"

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

    override val audioEffect: AudioEffectStrings = object : AudioEffectStrings {
        override val screenTitle = "Audio effects"
        override val entrySubtitle = "Parametric equalizer, channel balance and loudness leveling"
        override val unsupportedNote =
            "This platform's audio engine only exposes volume — it has no equalizer or effect " +
                "processing available, so nothing on this page can be adjusted on desktop."

        override val sectionEqualizer = "Parametric equalizer"
        override val equalizerEnabled = "Enable equalizer"
        override val equalizerEnabledNote =
            "Shapes each band using the curve below; when off, audio passes through untouched"
        override val equalizerPreset = "Preset"
        override val equalizerPresetNote =
            "Pick a preset as a starting point, then fine-tune any band individually"
        override val presetFlat = "Flat"
        override val presetPop = "Pop"
        override val presetRock = "Rock"
        override val presetVocal = "Vocal"
        override val presetBassBoost = "Bass boost"
        override val presetTrebleBoost = "Treble boost"
        override val presetCustom = "Custom"

        override fun bandTitle(index: Int) = "Band ${index + 1}"
        override fun bandSubtitle(index: Int, hz: String, gainDb: String) = "$hz · $gainDb"
        override val bandFrequency = "Frequency"
        override val bandGain = "Gain"
        override fun gainLabel(gainDb: String) = "$gainDb dB"

        override val sectionMixer = "Mixer"
        override val balance = "Channel balance"
        override val balanceNote =
            "Left/right volume ratio — only one side is attenuated, so the overall level stays the same"
        override val balanceCenter = "Center"
        override val balanceLeft = "Left"
        override val balanceRight = "Right"

        override val sectionLeveling = "Volume leveling"
        override val levelingEnabled = "Loudness leveling"
        override val levelingEnabledNote =
            "Pulls loud passages down and quiet ones up, so the volume stops jumping around"
        override val levelingTarget = "Target loudness"
        override val levelingTargetNote =
            "Higher values are louder overall; it applies before the volume slider rather than replacing it"
        override val preventClipping = "Prevent clipping"
        override val preventClippingNote = "Caps peak levels to avoid distortion from clipping"

        override val levelingNote =
            "Loudness leveling and clipping prevention both apply before the volume control, so " +
                "they don't duplicate the volume slider. The equalizer and mixer alter the audio " +
                "signal itself — turn effects off when you want the original audio."
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

    override val streamOutput: StreamOutputStrings = object : StreamOutputStrings {
        override val screenTitle = "Local Stream Output"

        override val sectionMain = "Local stream output"
        override val enabled = "Enable local stream output"
        override val enabledNote =
            "Serve the current track as an HTTP stream so receivers can pull it by track"
        override val audioOutput = "Audio output"

        override val outputLocal = "Play locally"
        override val outputRemoteOnly = "Stream only"
        override fun outputNote(local: Boolean) =
            if (local) "Audio plays on this device" else "This device stays silent — audio only plays on the receiver"

        override val bindScope = "Bind scope"
        override val bindScopeNote = "Decides which devices can reach this service"
        override val scopeLocalhost = "This device only"
        override val scopeLan = "LAN"
        override val warningLanNoToken =
            "Currently bound to the LAN with no access token: every request to the media and data " +
                "endpoints will be rejected. Regenerate the access token below first."

        override val sectionPort = "Port"
        override val port = "Stream output port"
        override val portNote = "The port receivers connect to when pulling a stream. Takes effect immediately"
        override val portRestartNote =
            "Changing the port restarts the listener, which briefly interrupts streams in progress."
        override fun portEmpty() = "The port can't be empty"
        override fun portNotNumber() = "The port must be a number"
        override fun portOutOfRange(from: Int, to: Int) = "The port must be between $from and $to"

        override val sectionToken = "Access token"
        override val regenerateToken = "Regenerate access token"
        override val regenerateTokenNote = "Every connected device stops working immediately"
        override val regenerateConfirmTitle = "Regenerate the access token?"
        override val regenerateConfirmMessage =
            "The old token stops working immediately. Every connected receiver and third-party app " +
                "has to switch to the new one, or its requests will fail with 401."
        override val regenerateLabel = "Regenerate"
        override val tokenRegenerated = "Access token regenerated — connected devices need to be reconfigured"
        override val tokenStorageNote =
            "The token is stored in plain text in ~/.cpplayer/integration.json — don't share it."
        override val currentToken = "Current token"
        override val tokenAutoGenerated = "Generated automatically when enabled"

        override val sectionEndpoint = "Where receivers pull from"
        override val endpointNote =
            "Receivers pass ?mediaId=… when pulling a track; CPPlayer parses it and forwards the bytes."
        override val statusDisabled = "The service is off."
        override fun statusStartFailed(error: String) = "Failed to start: $error"
        override fun statusRunning(url: String) = "Running · $url"
        override val statusStarting = "Starting…"
    }

    override val integration: IntegrationStrings = object : IntegrationStrings {
        override val screenTitle = "Push & Integrations"

        override val sectionPush = "Push to a receiver"
        override val receiverAddress = "Receiver address"
        override val receiverAddressNote =
            "Address of the app that receives the push — CPPlayer connects out to it"
        override val autoPush = "Push automatically on track change"
        override val autoPushNote = "Replaces the receiver's queue and starts playing when a new track begins"
        override val testConnection = "Test connection"
        override fun testProbing() = "Requesting…"
        override fun testIdle(endpoint: String) = "Request $endpoint on the receiver"
        override val pushCurrentTrack = "Push the current track"
        override val pushCurrentTrackNote = "Replaces the receiver's queue with this one track and plays it"
        override val pushCurrentQueue = "Push the current queue"
        override val pushCurrentQueueNote = "Hands the whole queue to the receiver, which plays it in order"

        override val sectionThirdParty = "Third-party access"
        override val allowStream = "Allow third parties to pull the audio stream"
        override val allowStreamNote = "The GET /stream that receivers need to pull a track"
        override val allowApi = "Allow third parties to read source data"
        override val allowApiNote =
            "Search, tracks, and playback state (/api/v1/…). Off by default because it widens exposure"
        override val apiSwitchNote =
            "Turning off \"read source data\" makes the whole data API return 403, but stream pulling " +
                "keeps working. Both switches take effect immediately."

        override val sectionEndpoint = "Endpoints"
        override val configFileNote =
            "Third-party apps read ~/.cpplayer/integration.json to get the address and token. " +
                "That file holds the token in plain text — don't share it."
        override val streamDisabledNote =
            "Local stream output is currently off, so the addresses above aren't reachable yet. " +
                "Turn it on under \"Local Stream Output\" first."

        override fun addressEmpty() = "The receiver address can't be empty"
        override fun addressScheme() = "It must start with http:// or https://"
        override fun addressNoHost(example: String) = "Missing a host name, for example $example"
        override val pushNever = "No push yet"
        override val pushOk = "Last push succeeded"
        override fun pushFailed(reason: String) = "Last push failed: $reason"
    }

    override val standby: StandbyStrings = object : StandbyStrings {
        override val screenTitle = "LAN Devices"

        override val sectionSelf = "This device"
        override val deviceName = "Name"
        override val devicePlatform = "Platform"
        override val deviceId = "Device ID"
        override val deviceVersion = "Version"
        override val discoveryStatus = "Discovery status"
        override val discoveryFailed = "Failed to start"
        override val listening = "Listening"
        override val notStarted = "Not started"

        override val sectionVisible = "Visible on the LAN"
        override val visible = "Visible on the LAN"
        override val visibleNote =
            "Keeps broadcasting this device's beacon and listening for others while the app runs — " +
                "the prerequisite for seamless sync and handoff. Turn it off to disappear from the LAN."

        override val beaconSent = "Beacons sent"
        override fun beaconSentRounds(count: Long) = "$count rounds"
        override val beaconReceived = "Beacons received"
        override val beaconInvalid = "Unrecognized beacons"
        override val lastReceived = "Last received"
        override fun discoveryStartFailed(error: String) = "Device discovery failed to start: $error"

        override val sectionAutoSync = "Auto sync (LAN)"
        override val autoSync = "Auto sync listening history"
        override val autoSyncNote =
            "Two devices exchange their listening history. Once on, it just happens — no manual steps."
        override val syncNow = "Sync now"
        override val neverSynced = "Never synced"
        override val syncService = "Sync service"
        override fun syncListeningPort(port: Int) = "Listening (port $port)"
        override val lastSync = "Last sync"
        override val syncWarning =
            "⚠️ Once on, any device on the same LAN can read and write this device's listening " +
                "history. That's the only thing that syncs — no accounts, credentials, playlists, or " +
                "favorites. Turn it off on office and other non-private networks. Device pairing and " +
                "authentication are the next step."
        override val syncEnabledNote =
            "Off by default. Once on there are no manual steps: as long as both devices are on the " +
                "same network with CPPlayer open, their listening histories merge in both directions — " +
                "regardless of which is newer or who you use in turn."

        override val sectionKeepAlive = "Stay online"
        override val aggressiveStandby = "Aggressive keep-alive"
        override val aggressiveStandbyNote =
            "Keeps Wi-Fi alive with the screen off so discovery and handoff can still land"
        override val notEnabled = "Off"
        override val inEffect = "In effect"
        override val notInEffect = "Not in effect (the system may have refused)"
        override val keepAliveHint =
            ". Without it the system may drop multicast packets once the screen is off."

        override val sectionLanDevices = "LAN devices"
        override val discovering =
            "Listening, but no other device found yet. As long as the other side is running " +
                "CPPlayer too (it needn't be on any particular screen), it shows up here within " +
                "about half a minute."
        override val discoveryNotStarted = "Device discovery isn't started."
        override val onlineDevices = "Online devices"
        override fun onlineCount(count: Int) = if (count == 1) "1 device" else "$count devices"
        override val sectionOnline = "Online — click to move the current playback there"
        override val localNotPlaying = " · nothing playing here"
        override val sectionOffline = "Offline"
        override fun justOnline(address: String, port: Int) = "$address:$port · here a moment ago"
        override val troubleshooting =
            "If you can't find a device, check in this order: ① both devices run a recent version of " +
                "CPPlayer (older builds have no discovery, so beacons aren't recognized); " +
                "② \"Visible on the LAN\" is on for both, and \"Beacons received\" above is climbing — " +
                "if it's stuck at 0 this device isn't receiving packets: check the firewall's inbound " +
                "rules (Windows asks on first listen, and a refusal is permanent); " +
                "③ same router and same subnet (guest networks and AP isolation separate devices); " +
                "④ if \"Beacons received\" is above 0 but the list stays empty while \"Unrecognized " +
                "beacons\" climbs, the other side isn't running the same version; " +
                "⑤ machines with several network adapters (VPN, virtual adapters) may need a few tries."
        override val handoffPrereq =
            "Handoff needs the other side running \"Auto sync\" (its sync service listening) and signed " +
                "in to the same music source — only \"which track, from which second\" is handed " +
                "over; each side fetches its own stream URL. If either condition fails you get an " +
                "explicit failure and this device keeps playing, never silence."
        override val handoffSecurityNote =
            "Pairing and authentication are still missing: neither handoff nor sync is authenticated " +
                "yet, so the port should stay on private networks. Pairing (PIN / QR code) is next."
        override val keepAliveReality =
            "This cannot exempt the app from being reclaimed. The system reclaims background " +
                "processes by priority, regardless of process size or implementation language. The " +
                "only reliable way to stay resident is a foreground service, so this switch just nudges " +
                "\"how long the system feels like keeping you\" in your favor."
        override val keepAliveRecommend =
            "Also turn on \"Playback & Quality → Battery optimization whitelist\" — the two together " +
                "are what block vendor ROM background cleanup."

        override val handoffNoTrack = "Nothing is playing right now, so there's nothing to hand over"
        override fun handoffStarting(device: String) = "Moving playback to $device…"
        override fun handoffDone(device: String) = "Moved playback to $device (paused here, position kept)"
        override fun handoffFailed(reason: String) = "Handoff failed: $reason — still playing here"
        override fun handoffTakenOver(device: String, track: String) = "Took over playback from $device: $track"
        override fun handoffTakeOverFailed(device: String, reason: String) =
            "$device tried to hand over playback, but taking over failed: $reason"
        override val peerFallbackName = "The other device"
        override val noResponse = "No response from the device"
        override val cannotPlayTrack = "This device can't play that track (not signed in, or it doesn't exist)"
        override val startTimedOut = "Playback didn't start on this device in time"

        override val pickerTitle = "Move playback to another device"
        override fun pickerNowPlaying(track: String) = "Now playing: $track"
        override val pickerNoTrack = "Nothing is playing right now"
        override fun pickerDiscoveryFailed(error: String) = "Device discovery failed to start: $error"
        override val pickerSearching =
            "Searching for devices on the LAN…\nAny device running a recent CPPlayer shows up here; " +
                "handoff also needs the other side running \"Auto sync\" (Settings → LAN Devices), " +
                "or it won't be able to take over."
        override val handoffToLabel = "Move playback here"
        override val pickerHint =
            "Tap to move the current playback — including the whole queue — to that device. " +
                "This device pauses and keeps its position. The other side must be signed in to the " +
                "same music source."
    }

    override val player: PlayerStrings = object : PlayerStrings {
        override val share = "Share"
        override val addToPlaylist = "Add to playlist"
        override val downloaded = "Downloaded"
        override val download = "Download"
        override val disliked = "Marked as not interested"
        override val dislike = "Not interested"
        override val listenTogether = "Listen together"
        override val transferDevice = "Move to device"
        override val play = "Play"
        override val removeFromQueue = "Remove from queue"
        override val saveAsPlaylist = "Save as playlist"
        override val locateCurrent = "Jump to current"
        override val clearQueue = "Clear queue"
        override val more = "More"

        override val songInfo = "Track info"
        override val lyricInfo = "Lyrics info"
        override val audioFormat = "Audio format"
        override val infoSource = "Source"
        override val infoFormat = "Format"
        override val wordLevelLyrics = "Word-level lyrics"
        override val yes = "Yes"
        override val no = "No"
        override val infoTranslation = "Translation"
        override val infoPhonetic = "Phonetic"
        override val infoCodec = "Codec"
        override val infoSampleRate = "Sample rate"
        override val infoBitDepth = "Bit depth"
        override val infoBitrate = "Bitrate"
        override val infoChannels = "Channels"
        override val unknownAlbum = "Unknown album"

        // 分享文本是**逐行**拼的（要进剪贴板），所以每行的「标签：值」都在文案层，
        // 英文的标签与冒号之间是同一个空格，中文没有 —— 不能在调用方拼。
        override fun shareTrack(name: String, artist: String, album: String, duration: String, id: String) =
            "Title: $name\nArtist: $artist\nAlbum: $album\nDuration: $duration\nTrack ID: $id"
        override fun shareLyrics(source: String, format: String, wordLevel: String, extra: String) =
            "Lyrics info\nSource: $source\nFormat: $format\nWord-level lyrics: $wordLevel" + extra
        override fun shareAudio(
            codec: String?,
            sampleRate: String?,
            bitDepth: String?,
            bitrate: String?,
            channels: String?,
        ) = buildString {
            append("Audio format")
            codec?.let { append("\nCodec: $it") }
            sampleRate?.let { append("\nSample rate: $it Hz") }
            bitDepth?.let { append("\nBit depth: $it bit") }
            bitrate?.let { append("\nBitrate: $it kbps") }
            channels?.let { append("\nChannels: $it") }
        }

        override val sleepTimer = "Sleep timer"
        override fun sleepTimerAfterTrack() = "Sleep timer · after this track"
        override fun sleepTimerRemaining(minutes: Long) = "Sleep timer · $minutes min"

        override val collapse = "Collapse"
        override val translation = "Translation"
        override val queue = "Queue"
        override val repeat = "Repeat"
        override val shuffle = "Shuffle"
        override val like = "Like"
        override val unlike = "Remove like"
        override fun sleepRemainingLabel(remaining: String) = "$remaining left"
        override val sleepEndsWithTrack = "Ends with this track"

        override fun queueCount(size: Int) = "$size tracks · up next"
        override val queueUpNext = "Up next"
        override val queueSaved = "Queue saved as a playlist"
        override val queueSaveFailed = "Couldn't save the playlist"
        override val queueClearTitle = "Clear the play queue"
        override fun queueClearMessage(size: Int) =
            "Clear all $size tracks from the queue?"
        override val queueEmptyLabel = "Queue"
        override val queueReorderHint = "Press and drag to reorder"
        override val coverContentDescription = "Cover"

        override val previousTrack = "Previous track"
        override val nextTrack = "Next track"
        override fun playOrPause(playing: Boolean) = if (playing) "Pause" else "Play"

        override val commentsLoadFailed = "Couldn't load comments"
        override val retry = "Retry"
        override val noComments = "No comments yet"
    }

    override val library: LibraryStrings = object : LibraryStrings {
        override val playAll = "Play all"
        override val addToQueue = "Add to queue"
        override val downloadAll = "Download all"
        override val sharePlaylist = "Share playlist"
        override val favoritePlaylist = "Favorite playlist"
        override val unfavoritePlaylist = "Remove favorite"
        override val deletePlaylist = "Delete playlist"
        override val removeFromPlaylist = "Remove from playlist"
        override val removeFromCloud = "Delete from cloud drive"
        override val removeSelected = "Remove"

        override fun removeSelectedMessage(name: String, count: Int) =
            "Remove $count selected tracks from \"$name\"?"
        override fun deletePlaylistMessage(name: String) =
            "Delete \"$name\"? This can't be undone."
        override fun unfavoritePlaylistMessage(name: String) =
            "Remove \"$name\" from favorites? You can add it back later."

        override val creatorOnlyHint = "Only the playlist owner can add tracks"
        override val favorited = "Added to favorites"
        override val unfavorited = "Removed from favorites"
        override val queuedToPlay = "Added to the play queue"
        override val playNext = "Will play next"
        override val operationFailed = "Couldn't complete the action"
        override val importFromPlaylist = "Import from playlist"

        override val songDetails = "Track details"
        override val paidType = "Payment"
        override val paidFree = "Free"
        override val paidPurchased = "Purchased"
        override val paidLowQualityFree = "Free (low quality)"
        override val paidUnknown = "Unknown"
        override val publishDate = "Release date"
        override val commentCount = "Comments"
        override val maxBitrate = "Max bitrate"
        override val releaseDateFormat = "yyyy-MM-dd"

        override fun shareTrack(name: String, artist: String, album: String, duration: String, extra: String) =
            "Title: $name\nArtist: $artist\nAlbum: $album\nDuration: $duration" + extra
        override fun songShareLine(label: String, value: String) = "\n$label: $value"

        override val sourceNowPlaying = "Now playing"
        override val loadTrackInfoFailed = "Couldn't load the track info"

        override fun selectedCount(count: Int) = "$count selected"
        override val exitSelection = "Exit selection"
        override val selectAll = "Select all"

        override val sortDefault = "Default order"
        override val sortByName = "By title"
        override val sortByArtist = "By artist"
        override val sort = "Sort"
        override val moreOptions = "More options"
        override fun trackCountLabel(count: Int, duration: String) = "$count tracks • $duration"
        override fun playlistCountLabel(count: Int, duration: String) = "$count • $duration"

        override val playlistEmpty = "No tracks in this playlist yet"
        override val loadFailedRetry = "Couldn't load — tap to retry"
        override val retry = "Retry"

        override val tabPlaylists = "Playlists"
        override val tabDownloads = "Downloads"
        override val myMusic = "My music"
        override fun greeting(name: String) = "Hello, $name"
        override fun librarySubtitle(playlists: Int, liked: Int) =
            "$playlists playlists • $liked liked tracks"
        override fun countPlaylist(n: String) = n to "Playlists"
        override fun countLiked(n: String) = n to "Liked"
        override fun countDownload(n: String) = n to "Downloaded"
        override val libraryTitle = "Library"
        override fun libraryPlaylistsCount(n: Int) = "$n playlists"
        override val libraryOffline = "Offline and local content"
        override val newPlaylist = "New playlist"
        override val insights = "Listening insights"
        override val createPlaylist = "Create playlist"
        override val createPlaylistNote = "Collect the music you love"
        override val recentPlays = "Recently played"
        override val recentPlaysNote = "Pick up where you left off"
        override val offlineDownloads = "Offline downloads"
        override val offlineDownloadsNote = "Manage downloaded content"
        override val cloudDrive = "Cloud drive"
        override val cloudDriveNote = "Online music library"
        override val storageManage = "Storage"
        override val storageManageNote = "Cache and logs"
        override val aboutCpPlayer = "About CPPlayer"
        override val preferences = "Preferences"
        override val appearance = "Appearance"
        override val playback = "Playback"
        override val providers = "Sources"
        override val syncingLibrary = "Syncing your library"
        override val syncingLibraryNote = "Loading your playlists"
        override val libraryLoadFailed = "Couldn't load your library"
        override val noPlaylists = "No playlists yet"
        override val noPlaylistsNote = "Sign in to sync favorites and playlists you create"
        override val myPlaylists = "My playlists"
        override fun myPlaylistsCount(n: Int) = if (n == 1) "1 playlist" else "$n playlists"
        override val seeAll = "See all"
        override val downloading = "Downloading"
        override val completed = "Done"
        override val localMedia = "On this device"
        override val noDownloads =
            "No downloads yet. Tap Download in a track menu or on a playlist, and it'll show up here."
        override val openDownloads = "Open downloads"

        override val play = "Play"
        override val shuffle = "Shuffle"
        override val add = "Add"
    }

    override val album: AlbumStrings = object : AlbumStrings {
        override val fallbackTitle = "Album"
        override val loadFailed = "Couldn't load the album"
        override val loading = "Loading the album"
        override val loadingNote = "Reading the album from the current source"
        override val notOpened = "This album isn't open"
        override val notOpenedNote = "Open an album from the Albums page first"
        override val tracks = "Tracks"
        override val noPlayableTracks = "No playable tracks in this album"
        override val emptyTracks = "No tracks"
        override val emptyTracksNote = "Try another album, or switch source in settings"
        override fun trackCount(n: Int) = if (n == 1) "1 track" else "$n tracks"
        override fun releasedYear(year: String) = "Released $year"
        override fun albumMeta(trackCount: Int, duration: String) = "$trackCount • $duration"
        override val retry = "Retry"
        override val shufflePlay = "Shuffle"
        override val liked = "Added to favorites"
        override val unliked = "Removed from favorites"
    }

    override val playlistSheet: PlaylistSheetStrings = object : PlaylistSheetStrings {
        override val noPlaylists = "No playlists available"
        override val addTracksTitle = "Add tracks"
        override val importFrom = "Import from playlist"
        override val addFromQueue = "Add from the play queue"
        override val selectTracks = "Select tracks"
        override fun fromSource(name: String) = "From $name"
        override val deselectAll = "Deselect all"
        override val selectAll = "Select all"
        override val noTracksFound = "No tracks found"
        override fun addCount(count: Int) = if (count == 1) "Add 1 track" else "Add $count tracks"
        override fun trackCount(n: Int) = if (n == 1) "1 track" else "$n tracks"
        override val playlistFallbackTitle = "Playlist"

        override val addToPlaylistTitle = "Add to playlist"
        override fun addedTracks(count: Int) =
            if (count == 1) "Added 1 track" else "Added $count tracks"
        override val addToPlaylistFailed = "Couldn't add to the playlist"
        override val createPlaylistFailed = "Couldn't create the playlist"
        override val newPlaylistNamePlaceholder = "New playlist name"
        override val create = "Create playlist"
        override val createShort = "Create"
        override val creating = "Creating…"
        override val noPlaylistsHint = "No playlists yet — create one above"

        override val createDialogTitle = "New playlist"
        override val playlistNamePlaceholder = "Playlist name"
        override fun created(name: String) = "Created \"$name\""
        override val cancel = "Cancel"
    }
    override val account = AccountStringsEn
    override val downloads = DownloadStringsEn
    override val insights = InsightStringsEn
    override val social = SocialStringsEn
    override val lyricsPlugin = LyricsPluginStringsEn
    override val wall = WallStringsEn

}
