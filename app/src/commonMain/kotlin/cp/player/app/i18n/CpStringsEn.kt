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
        override val isolationTitle = "Provider isolation"
        override val isolationIntro = "Each provider keeps its own sign-in state, cache and account list. " +
            "Switch providers and you will see that provider's accounts, favorites and playlists."
        override val isolationSectionAccounts = "Sign-in state"
        override val isolationRefreshProfile = "Refresh the profile when switching providers"
        override val isolationRefreshProfileNote = "Sign-in state still switches (Cookies are stored per provider); " +
            "this only skips refetching the nickname and avatar right away"
        override val isolationCurrentAccounts = "Accounts for the active provider"
        override val isolationUnknownProvider = "Unknown provider"
        override val isolationSaved = "Sign-in state saved"
        override val isolationNotSignedIn = "Not signed in"
        override fun isolationAccountCount(count: Int) = "$count accounts"
        override val isolationSwitchProvider = "Switch provider"
        override val isolationSwitchProviderNote = "Providers never leak sign-in state or cache into each other"
        override val isolationSectionCache = "Cache"
        override val isolationStorage = "Storage and cleanup"
        override val isolationStorageNote = "Artwork cache, download folders and other per-provider data are managed here"
        override val isolationSectionDanger = "Danger zone"
        override val isolationClearConfirm = "Tap again to confirm"
        override val isolationClearSession = "Clear this provider's sign-in state"
        override val isolationClearNote = "Clears only this provider; saved accounts remain and can be switched back in one tap"
        override fun isolationProfileNote(name: String) =
            "Profile: $name. Clearing the sign-in state does not touch downloaded music."
        override val groupGeneral = "General"
        override val groupAccountProvider = "Accounts & Sources"
        override val groupConnectivity = "Connections & Integrations"
        override val groupOther = "Other"

        override val itemLanguage = CpTextPair(
            title = "Language",
            subtitle = "Follow the system, or pin to Simplified Chinese / English",
        )
        override val itemAppearance = CpTextPair(
            title = "Interface Settings",
            subtitle = "Theme, language, motion, and navigation",
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
        override val itemExternalAccess = CpTextPair(
            title = "External Access",
            subtitle = "Serve audio streams and data APIs, and push to receivers",
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
        override val save = "Save"
        override val apply = "Apply"
        override val refresh = "Refresh"
        override val unsavedBlocked = "Not saved · fix the problem above first"
        override val unsavedHint = "Not saved · press Apply to keep it"
        override val back = "Back"
        override val close = "Close"
        override val search = "Search"
        override val clearSearch = "Clear search"
        override val initializingBackend = "Initializing backend…"
    }

    override val navigation = NavigationStringsEn

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
        override val settingsSubtitle = "Direct-message alerts and close behavior"
        override val masterLabel = "Allow direct-message notifications"
        override val masterHint = "Nothing is polled while this is off. Only works while the app is running"
        override val subscribedSection = "Contacts you follow"
        override val subscribedEmpty = "No contact is followed yet"
        override val unsupportedPlatform = "System notifications are not supported on this platform"
        override val permissionMissing = "System notification permission is off, so you will not be notified"
        override val grantPermission = "Grant permission"

        override val trayShowWindow = "Show CPPlayer"
        override val trayExit = "Quit CPPlayer"
        override val trayMiniMode = "Mini player"
        override val trayNothingPlaying = "Nothing playing"
        override val trayPlay = "Play"
        override val trayPause = "Pause"
        override val trayPrevious = "Previous track"
        override val trayNext = "Next track"

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
        override val screenTitle = "Interface Settings"
        override val sectionLook = "Look"
        override val sectionMotion = "Motion and visual effects"
        override val sectionContent = "Content and list presentation"
        override val sectionMobile = "Mobile player layout"
        override val sectionFont = "Typography"

        override val themeMode = "Theme mode"
        override val themeModeSystem = "Follow system"
        override val themeModeLight = "Light"
        override val themeModeDark = "Dark"
        override val themeModeSystemNote = "Switch automatically with the system day/night state"
        override val themeModeLightNote = "Always use the bright, clean light theme"
        override val themeModeDarkNote = "Always use the dark theme, easier on the eyes at night"
        override val sectionDarkEnhancements = "Dark display enhancements"
        override val pureBlackDetail = "With pure black enabled, the app background drops from dark gray to true black (#000000) in dark mode. OLED pixels stay off, which improves contrast and saves power."

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
        override val predictiveBack = "Predictive back gesture"
        override val predictiveBackNote =
            "Preview the previous screen in real time while swiping back from the screen edge"
        override val predictiveBackUnsupportedNote =
            "In-app predictive back preview requires Android 14+ (Android 13 only supports back-to-home via Developer options)"
        override val coverFlight = "Cover flight animation"
        override val coverFlightNote =
            "Transition that flies the artwork into the player or detail page when you tap a track or " +
                "playlist cover. Turn it off for a snappier tap."

        override val fluidBackground = "Fluid background"
        override val fluidBackgroundNote =
            "A slowly drifting gradient behind the player, inspired by Apple Music. Colors follow the " +
                "current theme and artwork palette. Turn it off to save power."

        override val fluidBackgroundFps = "Fluid background frame rate"
        override val fluidBackgroundDarkOverlay = "Dim fluid backgrounds in dark mode"
        override val fluidBackgroundDarkOverlayNote = "Dim playback fluid backgrounds in dark mode (15% on Android 13, 20% elsewhere)"
        override val desktopPlayerControls = "Desktop player"
        override val desktopPlayerVolume = "Show volume slider"
        override val desktopPlayerVolumeNote = "Show the volume slider below the desktop playback controls"
        override val fluidBackgroundFpsNote =
            "Sets the maximum animation rate. Higher rates look smoother but use more power."

        override val showSongAlbum = "Show album name"
        override val showSongAlbumNote =
            "Display the album name in track list items; off by default for a cleaner look"

        override val songSwipeActions = "Swipe songs for queue actions"
        override val songSwipeActionsNote =
            "Swipe right to play next, swipe left to add to queue"

        override val fontRoundness = "Font roundness"
        override val fontRoundnessRounded = "Rounded"
        override val fontRoundnessSquare = "Square"

        override fun themeSummary(mode: String, color: String, pureBlack: Boolean) =
            "$mode${if (pureBlack) " · Pure black on" else ""} · $color"

        override val playerStyle = "Player style"
        override val playerStyleNote = "Switch the player page layout"
        override val playerStyleThreePane = "Three-pane player"
        override val playerStyleStandard = "Standard player"
        override val playerStyleThreePaneNote = "Swipe between lyrics, player and comments"
        override val playerStyleStandardNote = "Artwork, lyrics and controls on one page"
        override val mobilePreferences = "Player settings"
        override val mobilePreferencesNote = "Lyrics translation, immersive mode and reset"
        override val playerSettingsTitle = "Player settings"
        override val playerSettingsHeroNote = "Lyrics and player appearance"
        override val playerLyricsPresentation = "Lyrics display"
        override val playerLyricsTranslation = "Show lyric translation"
        override val playerLyricsTranslationNote = "Show the translation under each line"
        override val playerLyricsImmersive = "Immersive lyrics"
        override val playerLyricsImmersiveToggle = "Auto-hide controls"
        override val playerLyricsImmersiveNote = "Hide the control bars after you stop interacting"
        override val playerLyricsImmersiveTimeout = "Idle delay"
        override val playerLyricsImmersiveTimeoutNote = "How long to wait before hiding the controls"
        override fun playerLyricsImmersiveSeconds(seconds: Int) = "$seconds sec"
        override val playerResetSection = "Reset"
        override val playerReset = "Restore player defaults"
        override val playerResetNote = "Reset lyric presentation and immersive lyric options"
        override val sectionPreview = "Layout preview"
        override val mobilePreview = "Mobile layout preview (412×892)"
        override val mobilePreviewOn = "Currently in the compact mobile layout; tap to restore the desktop window"
        override val mobilePreviewOff = "Switch to the 412×892 compact mobile layout with bottom navigation and mobile top bar"
    }

    override val fontPacks: FontPackStrings = object : FontPackStrings {
        override val sectionTitle = "Font packs"
        override val intro = "Each font can be individually toggled on or off. When disabled or not downloaded, " +
            "the system font is used instead. On desktop all three ship inside the installer; " +
            "on Android the CJK fonts can be downloaded on demand."
        override val unsupported = "This Android version cannot apply variable font weights (Android 8.0 " +
            "or newer is required), so Chinese and Japanese will keep using the system font."
        override val googleSansTitle = "Google Sans Flex (Latin / digits)"
        override val googleSansNote = "Primary font for Latin letters, digits and symbols; supports variable roundness"
        override val japaneseTitle = "Noto Sans JP (Japanese)"
        override val japaneseNote = "Kana and Japanese kanji forms for Japanese lyrics and titles"
        override val misansTitle = "MiSans (Chinese)"
        override val misansNote = "Chinese, full-width punctuation and other scripts, with weights 300–700"
        override val stateInstalled = "Installed"
        override val stateMissing = "Not downloaded"
        override fun stateDownloading(percent: Int) = "Downloading $percent%"
        override fun stateFailed(reason: String) = "Download failed: $reason"
        override val actionDownload = "Download"
        override val actionRetry = "Retry"
        override val actionDelete = "Delete"
        override val deleteConfirm =
            "After deleting, Chinese / Japanese fall back to the system font. You can download again anytime."
        override val cancel = "Cancel"
        override val sourceNote = "Fonts are downloaded from this project's GitHub releases and stored in the " +
            "app's private storage; they are removed when the app is uninstalled."
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
        override val cachedSongsEmpty = "Nothing cached — tracks are cached as you play them"
        override fun cachedSongsSummary(count: Int, bytes: String, capacity: String) =
            "$count tracks · $bytes of $capacity"
        override val cacheCapacity = "Capacity limit"
        override val cacheCapacityNote = "Lowering the limit trims the least recently played tracks right away"
        override val customCapacity = "Custom capacity"
        override val customCapacityDialogTitle = "Custom Cache Limit"
        override val customCapacityDialogNote = "Enter a custom capacity limit. Lowering the limit trims older tracks right away."
        override fun customCapacityPlaceholderGb() = "e.g. 10"
        override fun customCapacityPlaceholderMb() = "e.g. 2048"
        override fun customCapacityPreview(bytes: String) = "Capacity: $bytes"
        override val customCapacityInvalid = "Enter a valid capacity (128 MB – 1024 GB)"
        override val customCapacityUnitGb = "GB"
        override val customCapacityUnitMb = "MB"
        override fun customCapacitySubtitle(capacity: String) = "Current custom limit: $capacity. Click to edit"
        override fun customCapacityCurrent(capacity: String) = "Current limit: $capacity. Click to set any size"
        override val cacheDir = "Cache folder"
        override val migrateCacheDir = "Migrate cache folder"
        override val migrateCacheDirNote = "Change song cache folder and move existing cached files"
        override val migrateCacheDirConfirmTitle = "Migrate Cache Folder"
        override fun migrateCacheDirConfirmMessage(target: String) =
            "Are you sure you want to migrate song cache to:\n$target\n\nExisting cached files will be copied automatically."
        override val confirmMigrate = "Migrate"
        override val cacheDirMigrating = "Migrating cache folder..."
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
        override val unsupported = "This platform does not support audio caching."
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

        override val convertAll = "Convert all to downloads"
        override val converting = "Converting…"
        override val convertAllNote =
            "Turns the complete tracks in the cache into real downloads without fetching them again. " +
                "Only entries with a known source and quality are converted; tracks already downloaded are skipped."
        override fun convertEntry(name: String) = "Convert the cached copy of \"$name\" into a download"
        override fun converted(count: Int, skipped: Int) =
            if (skipped > 0) "Converted $count cached tracks to downloads, skipped $skipped"
            else "Converted $count cached tracks to downloads"
        override val nothingToConvert =
            "Nothing to convert — older cache entries without source or quality info can't be converted"
        override val convertFailed = "Conversion failed, please try again later"

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

        override val sectionAudioEngineAndroid = "Playback engine"
        override val sectionAudioOutputDesktop = "Audio output mode"
        override val engineMedia3 = "System Media3 (default)"
        override val engineFlick = "FlickEngine (VIP)"
        override val outputShared = "Shared (default)"
        override val outputExclusive = "Exclusive (VIP)"
        override val activeKernel = "Active engine"
        override val wasapiMode = "WASAPI output mode"
        override val kernelVipInactive = "License not activated — fell back to the system engine"
        override val kernelFlickActive = "FlickEngine low-latency passthrough · applies from the next track"
        override val kernelMedia3 = "System Media3: steadier compatibility and power use; audiophile features unavailable"
        override val kernelExclusiveActive = "Exclusive audio device: other apps are muted during playback"
        override val kernelShared = "Shared audio device: other apps can play at the same time"
        override val engineDetailAndroid = "Oboe passthrough, USB DAC and DSD"
        override val engineDetailDesktop = "Exclusive mode, Bit-Perfect and DSD"
        override val engineDetailAndroidNote = "Oboe low-latency passthrough · UAC 2.0 USB DAC bit-perfect output · DSD64/128 software decode"
        override val engineDetailDesktopNote = "WASAPI exclusive · Bit-Perfect sample-rate adaptation · DSD64/128 software decode"
        override val engineDetailDesktopUnsupportedNote = "This platform has no WASAPI exclusive path — shared output only"
        override val featureDetails = "Details"
        override val vipManagement = "Audiophile license"
        override fun vipActivated(plan: String) = "Activated: $plan"
        override fun vipActivatedWithKey(plan: String, key: String) = "Activated: $plan ($key)"
        override val vipInactiveNote = "Not activated. Enter a license key to unlock FlickEngine and exclusive passthrough"
        override val vipDialogTitle = "Unlock Hi-Res Audiophile Privileges"
        override val vipDialogNote = "On Windows, FlickEngine offers WASAPI hardware-exclusive output " +
            "(bit-perfect passthrough); on Android it offers low-latency Oboe and USB DAC output. " +
            "Both include DSD64/128 software decoding and 64-bit floating-point DSP. Exclusive mode " +
            "is off by default; turn it on in Settings after activation."
        override val vipKeyLabel = "License key"
        override val vipKeyPlaceholder = "Paste an offline or cloud license key"
        override val vipActivateFailed = "Activation failed. Check the license key."
        override val vipActivateNow = "Activate"
        override val vipActivateLater = "Not now"
        override val changeLicenseKey = "Change key"
        override val activatePrivilege = "Activate"
        override val dspSection = "DSP effects and mixer"
        override val dspNote = "5-band parametric EQ (PEQ), style presets, channel balance and stereo width"
        override val mixer = "Mixer"
        override val edit = "Edit"
        override val set = "Set"

        override val sectionLyrics = "Lyrics"
        override val lyricsSource = "Lyrics source"
        override val lyricsSourceNote =
            "AMLL is a word-by-word lyrics database (better translations and romanization). " +
                "With AMLL first, a miss falls back to the source's lyrics. Applies on the next refresh."
        override val lyricsProviderOnly = "Source API only"
        override val lyricsAmllFirst = "AMLL first"
        override val lyricsAmllOnly = "AMLL only"
        override val showMiniPlayerLyrics = "Lyrics in mini player"
        override val showMiniPlayerLyricsSubtitle = "Show the current lyric in the bottom mini player; turn off to show the artist"
        override val showRubyLyrics = "Japanese Furigana"
        override val showRubyLyricsSubtitle = "Show reading kana above kanji in Japanese lyrics"
        override val lyricsWordSegmentation = "Lyric Word Segmentation"
        override val lyricsWordSegmentationSubtitle = "Wrap and segment lyrics by language word boundaries (e.g. Japanese)"
        override val smartKaraokeLrc = "Smart Karaoke for LRC (Beta)"
        override val smartKaraokeLrcSubtitle = "Synthesize syllable-level highlight using beat grid and language weights when karaoke data is unavailable"

        override val sectionSleepTimer = "Sleep timer"
        override val sleepTimer = "Sleep timer"
        override val sleepAfterTrack = "Pause after the current track"
        override fun sleepRemaining(minutes: Long) = "$minutes min left"
        override val sleepOff = "Off"

        override val sectionAutoPlay = "Startup & Auto-Play"
        override val autoPlayMode = "Auto-play on launch"
        override val autoPlayModeNote = "Automatically start playing music when app opens and backend is ready"
        override val autoPlayModeOff = "Off"
        override val autoPlayModeContinue = "Resume last playback"
        override val autoPlayModeDaily = "Daily recommendations"
        override val autoPlayModeCustom = "Specific playlist / track"
        override val autoPlayCustomTarget = "Auto-play target"
        override val autoPlayCustomTargetEmpty = "Not set, click to select playlist or enter ID"
        override val autoPlayCustomDialogTitle = "Configure Auto-Play Target"
        override val autoPlayPickFromPlaylists = "Select from My Playlists"
        override val autoPlayUseCurrentSong = "Set current track"
        override val autoPlayUseCurrentPlaylist = "Set current playlist"
        override val autoPlayManualInput = "Enter ID or URL manually"
        override val autoPlayTypePlaylist = "Playlist"
        override val autoPlayTypeTrack = "Track"
        override val autoPlayInputIdPlaceholder = "Enter playlist/song ID or URL"
        override fun autoPlayPlaylistLabel(id: String) = "Playlist $id"
        override val autoPlayInputTitlePlaceholder = "Display title (optional)"
        override val autoPlayClearTarget = "Clear Target"

        override val sectionLastPlayback = "Last playback"
        override val keepLastPlayback = "Keep last playback"
        override val keepLastPlaybackNote =
            "Restore your last queue and position on launch, without auto-playing; " +
                "press play to resume where you left off"

        override val sectionPreload = "Audio Preload"
        override val preloadNext = "Preload Next Track"
        override val preloadNextNote =
            "Preload and buffer the next track in the background for instant switching and smooth transitions"

        override val sectionFade = "Fade in / out"
        override val fadeToggle = "Enable fades"
        override val fadeNote = "Smooth volume transitions between tracks"
        override val fadeIn = "Fade in on start"
        override val fadeInNote = "Ramp volume up from silence when a new track begins"
        override val fadeOut = "Fade out before end"
        override val fadeOutNote = "Lower the volume near the end so the next track does not cut in"
        override val fadeDuration = "Transition length"
        override val fadeDurationNote = "Applies to both fade-in and fade-out"
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

        override val timerCustomChip = "Custom..."
        override val timerCustomDialogTitle = "Custom sleep timer"
        override val timerCustomModeDuration = "Countdown"
        override val timerCustomModeAtTime = "At time"
        override fun timerCustomDurationHint(minutes: Int, endTimeText: String) =
            "Will pause in $minutes min (approx. $endTimeText)"
        override fun timerCustomAtTimeHint(targetTimeText: String, diffMinutes: Long) =
            "Will pause at $targetTimeText (in approx. $diffMinutes min)"
        override val timerCustomConfirm = "OK"
        override val timerCustomToggleDial = "Switch to clock dial"
        override val timerCustomToggleInput = "Switch to text input"
    }

    override val audioEffect: AudioEffectStrings = object : AudioEffectStrings {
        override val screenTitle = "Audio effects"
        override val entrySubtitle = "Parametric equalizer, channel balance and loudness leveling"
        override val unsupportedNote =
            "This platform's audio engine only exposes volume — it has no equalizer or effect " +
                "processing available, so nothing on this page can be adjusted on desktop."
        override val kernelStatusSection = "Audio engine status"
        override val kernelNotReadyTitle = "The FlickEngine native audio library isn't ready"
        override val kernelNotReadyNote = "Desktop decoding, effects and output all come from FlickEngine; " +
            "without the library, neither effects nor playback work. Check that the installation is complete, " +
            "or open the audio output page to inspect the engine status."
        override val viewStatus = "View status"
        override val equalizerEnabledSummary = "Enabled · 5-band parametric EQ is active"
        override val resetFlat = "Reset to flat"
        override val advancedFrequencyTuning = "Advanced: center frequency tuning (PEQ)"
        override val advancedFrequencyTuningNote = "Adjust the cutoff frequency of each band filter"
        override fun bandCenterFrequency(index: Int) = "Band ${index + 1} center frequency"

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
        override fun fullLabelOf(level: String) = when (level) {
            "standard" -> "Standard Quality"
            "higher" -> "High Quality"
            "exhigh" -> "Very High Quality"
            "lossless" -> "Lossless Quality"
            "hires" -> "Hi-Res"
            "jymaster" -> "Master Quality"
            "sky" -> "Immersive Audio"
            "jyeffect" -> "Effects"
            else -> "$level Quality"
        }
    }

    override val externalAccess: ExternalAccessStrings = object : ExternalAccessStrings {
        override val screenTitle = "External Access"

        // ---- Serve ----
        override val sectionService = "Serve"
        override val enabled = "Enable external access"
        override val enabledNote =
            "Run a local HTTP service: third parties can pull streams and read source data, " +
                "and CPPlayer can push tracks out to a receiver"
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

        override val statusDisabled = "The service is off."
        override fun statusStartFailed(error: String) = "Failed to start: $error"
        override fun statusRunning(url: String) = "Running · $url"
        override val statusStarting = "Starting…"

        // ---- Port ----
        override val sectionPort = "Port"
        override val port = "Service port"
        override val portNote =
            "The port third parties connect to for streams and data. Takes effect immediately"
        override val portRestartNote =
            "Changing the port restarts the listener, which briefly interrupts streams in progress."
        override fun portEmpty() = "The port can't be empty"
        override fun portNotNumber() = "The port must be a number"
        override fun portOutOfRange(from: Int, to: Int) = "The port must be between $from and $to"

        // ---- Access token ----
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
            "The stream and data APIs share the same access token. Please keep it secure."
        override val currentToken = "Current token"
        override val tokenAutoGenerated = "Generated automatically when enabled"

        // ---- Third-party access ----
        override val sectionThirdParty = "Third-party access"
        override val allowStream = "Allow third parties to pull the audio stream"
        override val allowStreamNote = "The GET /stream that receivers need to pull a track"
        override val allowApi = "Allow third parties to read source data"
        override val allowApiNote =
            "Search, tracks, and playback state (/api/v1/…). Off by default because it widens exposure"
        override val apiSwitchNote =
            "Turning off \"read source data\" makes the whole data API return 403, but stream pulling " +
                "keeps working. Both switches take effect immediately, and both rely on the switch " +
                "at the top of this page."

        // ---- Push ----
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

        // ---- Endpoints ----
        override val sectionEndpoint = "Endpoints"
        override val endpointNote =
            "Streaming lives at /stream and the data API at /api/v1/…, both under this address. " +
                "Pulling a track passes ?mediaId=…"
        override val configFileNote =
            "Third-party software can read server connection details and token from the configuration folder."
        override val serviceDisabledNote =
            "External access is currently off, so the addresses above aren't reachable yet. " +
                "Turn on the switch at the top of this page."

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
            "⚠️ Automatically syncs listening history between devices running CPPlayer on the same LAN (excludes accounts and credentials). Recommended on trusted networks only."
        override val syncEnabledNote =
            "Off by default. When enabled, listening history syncs automatically between devices on the same Wi-Fi running CPPlayer."

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
            "Listening for LAN devices. Devices running CPPlayer on the same network will appear automatically."
        override val discoveryNotStarted = "Device discovery isn't started."
        override val onlineDevices = "Online devices"
        override fun onlineCount(count: Int) = if (count == 1) "1 device" else "$count devices"
        override val sectionOnline = "Online — click to move the current playback there"
        override val localNotPlaying = " · nothing playing here"
        override val sectionOffline = "Offline"
        override fun justOnline(address: String, port: Int) = "$address:$port · here a moment ago"
        override val troubleshooting =
            "If devices cannot be discovered: ① Ensure both devices run the latest version of CPPlayer; " +
                "② Verify \"Visible on the LAN\" is enabled and firewalls allow incoming traffic; " +
                "③ Ensure both devices are on the same Wi-Fi/subnet; " +
                "④ Avoid guest networks with client isolation."
        override val handoffPrereq =
            "Handoff requires the target device to enable Auto Sync and be logged into the same music source."
        override val handoffSecurityNote =
            "It is recommended to use device handoff and sync features only within trusted local networks."
        override val keepAliveReality =
            "Keeps Wi-Fi active while the screen is off to help maintain background connectivity."
        override val keepAliveRecommend =
            "Pairing with battery optimization whitelist improves background playback stability."

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
        override val shareUnavailableLocal = "Local songs have no online share link"
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

        override val lyricsLoading = "Loading lyrics…"
        override val lyricsEmpty = "No lyrics"
        override val lyricsIdle = "Lyrics appear once the track starts playing"
        override fun lyricsError(message: String) = "Failed to load lyrics: $message"

        override fun capabilityLabel(key: String): String? = when (key) {
            "qrLogin" -> "QR sign-in"
            "wordSyncedLyrics" -> "Word-level lyrics"
            "comments" -> "Comments"
            "cloudDrive" -> "Cloud drive"
            "recommend" -> "Recommendations"
            "lyricsSearch" -> "Lyrics search"
            else -> null
        }

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
        override val infoSize = "File size"
        override val infoPath = "File path"
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

        override val nowPlaying = "Now Playing"
        override val collapse = "Collapse"
        override val lyricsCollapse = "Collapse lyrics"
        override val lyricsExpand = "Show full lyrics"
        override val immersive = "Immersive lyrics"
        override val exitImmersive = "Exit immersive mode"
        override val songComments = "Song comments"
        override val audioOutputDevice = "Audio output"
        override val translation = "Translation"
        override val queue = "Queue"
        override val repeat = "Repeat"
        override val shuffle = "Shuffle"
        override val like = "Like"
        override val unlike = "Remove like"
        override val volume = "Volume"
        override val mute = "Mute"
        override val unmute = "Unmute"
        override val quality = "Quality"
        override val qualityGlobalNote = "Applies to every track loaded from now on; the current one is not interrupted"
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
        override val queueEmptyHint = "The play queue is empty"
        override val queueNoMatchHint = "No matching tracks in the queue"
        override val queueReorderHint = "Drag to reorder"
        override val coverContentDescription = "Cover"

        override val previousTrack = "Previous track"
        override val nextTrack = "Next track"
        override fun playOrPause(playing: Boolean) = if (playing) "Pause" else "Play"

        override val commentsLoadFailed = "Couldn't load comments"
        override val retry = "Retry"
        override val noComments = "No comments yet"

        override val lyricOffset = "Lyric offset"
        override val lyricOffsetTitle = "Lyric Timing Calibration"
        override fun lyricOffsetSubtitle(ms: Long) = when {
            ms > 0 -> "Lyrics advanced by ${ms / 1000f}s"
            ms < 0 -> "Lyrics delayed by ${-ms / 1000f}s"
            else -> "Not adjusted (Standard sync)"
        }
        override fun lyricOffsetBadge(ms: Long) = "Offset " + (if (ms > 0) "+" else "") + "${ms / 1000f}s"
        override val lyricOffsetAdvance = "Advance (+)"
        override val lyricOffsetDelay = "Delay (-)"
        override val lyricOffsetReset = "Reset to 0"
        override val lyricOffsetHint = "Click 'Advance' if lyrics lag behind audio; click 'Delay' if lyrics rush ahead. Automatically saved for this track."

        override val miniMode = "Mini mode"
        override val miniModeRestore = "Restore window"
        override val miniModePin = "Always on top"
        override val miniModeUnpin = "Unpin"

        override val exploreSimilar = "Explore similar songs"
        override val exploreSimilarNote = "Find more tracks in the same style as what's playing"
        override val immersiveLyricsOn = "Immersive lyrics (on)"
        override val immersiveLyricsOff = "Immersive lyrics (off)"
        override val playerSettings = "Player screen settings"
        override fun lyricOffsetChipValue(label: String, seconds: String) = "$label ($seconds)"

        override val similarSongs = "Similar songs"
        override fun similarCount(count: Int) = "$count similar tracks · tap to play"
        override val similarEmptyNote = "Recommendations based on what's playing"
        override val similarLoadFailed = "Couldn't load similar songs"
        override val similarUnsupported = "This provider doesn't support similar songs yet"
        override val similarUnsupportedNote = "Switch to a provider that supports it and try again"
        override val similarNothingPlaying = "Nothing is playing"
        override val similarNothingPlayingNote = "Play a song to see similar recommendations"
        override val similarNoMatch = "No similar songs found"
        override val similarNoMatchNote = "Try another song"

        override val searchQueue = "Search queue…"

        override val favoriteTrack = "Favorite this song"
        override val unfavoriteTrack = "Remove favorite"
        override val repeatOne = "Repeat one"
        override val repeatAll = "Repeat all"
        override val repeatOff = "Play in order"
        override val showLyrics = "Show lyrics"
        override val showQueue = "Show queue"
        override val showComments = "Show comments"
        override val playMainView = "Show player"
        override val backToPlayer = "Back to the player"
        override val showLyricsTranslation = "Show lyric translation"
        override val hideLyricsTranslation = "Hide lyric translation"
        override val lyricsPluginSettings = "Lyrics plugin settings"
        override val playMv = "Play MV"
        override val addToPlaylistMenu = "Add to playlist…"
        override val songDetailsMenu = "Track details…"
        override val transferDeviceMenu = "Hand off / cast…"
        override val sleepTimerMenu = "Sleep timer…"
        override val copyTrackInfo = "Copy track info"
        override val switchToStandardPlayer = "Switch to the standard player"
        override val switchToThreePanePlayer = "Switch to the three-pane player"
        override val collapsePlayer = "Collapse the player"

        override val tabLyrics = "Lyrics"
        override val tabSong = "Song"
        override val tabQueue = "Queue"
        override val tabComments = "Comments"

        override fun switchToTab(tab: String) = "Switch to $tab"
        override val lyricsTranslation = "Lyric translation"
        override val closeLyricsTranslation = "Hide lyric translation"
        override val miniPlayer = "Mini player"
        override val restoreVolume = "Restore volume"
        override val maxVolume = "Maximum volume"

        override val commentsUnsupported = "This provider doesn't support comments"
        override val exclusiveBadge = "WASAPI exclusive · Bit-Perfect"
    }

    override val library: LibraryStrings = object : LibraryStrings {
        override val playAll = "Play all"
        override val addToQueue = "Add to queue"
        override val downloadAll = "Download all"
        override fun downloadAllMessage(name: String, count: Int) =
            "This will queue all $count songs from \u201C$name\u201D for download. Downloads can be cancelled and files removed. Continue?"
        override val sharePlaylist = "Share playlist"
        override val favoritePlaylist = "Favorite playlist"
        override val unfavoritePlaylist = "Remove favorite"
        override val deletePlaylist = "Delete playlist"
        override val removeFromPlaylist = "Remove from playlist"
        override val removeFromCloud = "Delete from cloud drive"
        override val removeSelected = "Remove"

        override fun removeTrackMessage(playlistName: String, trackName: String) =
            "Remove \"$trackName\" from \"$playlistName\"?"
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

        override fun playlistRefreshed() = "Refreshed"
        override val playlistDetailRefreshFailed = "Couldn't refresh the playlist"
        override val playlistDetailLoadFailed = "Couldn't load the playlist"
        override val loadMoreFailed = "Couldn't load more"
        override fun playlistDeleted(name: String) = "Deleted \"$name\""
        override fun playlistUnsubscribed(name: String) = "Removed \"$name\" from favorites"
        override val ownerOnlyAddTracks = "You can only add tracks to your own playlists"
        override val tracksAlreadyInPlaylist = "Every selected track is already in this playlist"
        override fun tracksAdded(count: Int) = if (count == 1) "Added 1 track" else "Added $count tracks"
        override val addTracksFailed = "Couldn't add tracks to the playlist"
        override fun tracksRemoved(count: Int) = if (count == 1) "Removed 1 track" else "Removed $count tracks"
        override val playlistFavorited = "Added to favorites"
        override val playlistUnfavorited = "Removed from favorites"
        override fun emptyPlaylist(name: String) = "\"$name\" has no tracks yet"
        override fun playlistQueued(name: String) = "Added \"$name\" to the play queue"
        override val localLibraryEmpty = "Your local library is empty. Scan or import music first."
        override val personalFmUnavailable = "Personal FM isn't available right now. Try again later."
        override fun playlistSourceLabel(key: String) = when (key) {
            "Recommended" -> "Recommended"
            "Hot" -> "Trending"
            "Premium" -> "Curated"
            else -> key
        }
        override fun newSongRegionLabel(key: String) = when (key) {
            "All" -> "All"
            "Chinese" -> "Chinese"
            "Western" -> "Western"
            "Japanese" -> "Japanese"
            "Korean" -> "Korean"
            else -> key
        }

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
        override val deselectAll = "Deselect all"

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
        override val gridView = "Grid view"
        override val listView = "List view"
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

        override val searchLibrary = "Search library"
        override val searchLibraryHint = "Search my playlists, playlist songs, and local songs"
        override val searchLibraryEmpty = "Type a keyword to search"
        override val searchLibraryEmptyNote = "Searches your playlists, their songs, and downloaded / imported local songs"
        override val searchPlaylistSection = "Playlists"
        override val searchPlaylistTracksSection = "Playlist songs"
        override val searchPlaylistTracksLoading = "Loading playlist songs"
        override val searchLocalSection = "Local music"
        override fun searchOnlineHint(query: String) = "Search online for \"$query\""
        override val noSearchResults = "No matches"
        override val importPlaylist = "Import playlist"
        override val importShort = "Import"

        override val renamePlaylist = "Rename playlist"
        override val renamePlaylistPlaceholder = "Enter a new playlist name"
        override fun renamedPlaylist(name: String) = "Renamed to \"$name\""
        override val renamePlaylistFailed = "Couldn't rename the playlist"

        override val play = "Play"
        override val shuffle = "Shuffle"
        override val add = "Add"

        override val searchFieldHint = "Search songs, artists, or albums"
        override val trackSearchHint = "Search songs, artists, albums..."
        override val trackSearchClear = "Clear"
        override val trackSearchClose = "Close search"
        override fun trackSearchResultCount(count: Int) = if (count == 1) "1 track" else "$count tracks"
        override val trackAdd = "Add"
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
        override val searchAlbum = "Search album tracks…"

        override val favoriteAlbum = "Favorite album"
        override val favoriteAlbumActive = "Album favorited"
        override fun favoriteAlbumDone(favorited: Boolean) =
            if (favorited) "Album added to favorites" else "Album removed from favorites"
        override fun unfavoriteAlbumMessage(name: String) =
            "Remove \"$name\" from favorites? You can add it back later."
        override val unfavoriteAlbumAction = "Remove favorite"
        override val favoriteAlbumNeedLogin = "Sign in to favorite albums"
        override val favoriteAlbumFailed = "Couldn't update the album favorite, try again later"
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

        override val addToLocalPlaylistTitle = "Add to local playlist"
        override val newLocalPlaylistPlaceholder = "New local playlist…"
        override val noCustomLocalPlaylists = "No local playlists yet — enter a name to create one"
        override fun createdAndAddedTo(name: String) = "Created and added to \"$name\""
        override fun addedToPlaylist(count: Int, name: String) =
            if (count > 0) "Added $count track(s) to \"$name\"" else "Already in \"$name\""
        override fun alreadyInPlaylist(name: String) = "Already in \"$name\""

        override val saveDailyToPlaylist = "Save as playlist"
        override val saveDailyToPlaylistTitle = "Save as playlist"
        override val saveDailyToPlaylistConfirm = "Convert"
        override val saveDailyToPlaylistConverted = "Saved"
        override fun saveDailyToPlaylistNote(count: Int) = "Save all $count daily recommended tracks into a new playlist"
        override fun saveDailyToPlaylistSuccess(name: String, count: Int) = "Saved as playlist \"$name\" ($count tracks)"
        override val saveDailyToPlaylistAlreadySaved = "Note: Today's recommendations have already been saved to a playlist."
        override val saveDailyToPlaylistFailed = "Failed to create playlist, please check your connection or login status"
        override fun saveDailyToPlaylistPartial(name: String) =
            "Playlist \"$name\" was created, but some tracks could not be added"
        override fun dailyRecommendationPlaylistName(date: String) = "Daily Mix · $date"
        override val artistFallback = "Artist"
    }
    override val playlistImport = PlaylistImportStringsEn
    override val account = AccountStringsEn
    override val downloads = DownloadStringsEn
    override val insights = InsightStringsEn
    override val social = SocialStringsEn
    override val lyricsPlugin = LyricsPluginStringsEn
    override val pluginRepository = PluginRepositoryStringsEn
    override val ai = AiStringsEn
    override val onboarding = OnboardingStringsEn
    override val providerManagement = ProviderManagementStringsEn
    override val contextMenu = ContextMenuStringsEn
    override val lyricPush = LyricPushStringsEn
    override val scrobble = ScrobbleStringsEn
    override val wall = WallStringsEn

    override val about: AboutStrings = object : AboutStrings {
        override val screenTitle = "About & Support"
        override val appSubtitle = "Compose Multiplatform Music Player"
        override val changelog = "Changelog"
        override val versionSection = "Version Info"
        override val commitSha = "Commit Hash"
        override val checkUpdate = "Check for updates"
        override val checkingUpdate = "Checking for updates..."
        override fun foundUpdate(v: String) = "New version found: v$v"
        override val alreadyLatest = "Up to date"
        override val projectSection = "Project"
        override val githubProject = "GitHub Repository"
        override val licensesAndThanks = "Open Source Licenses & Credits"
        override val licensesAndThanksSubtitle = "Apache-2.0 · Special thanks to moss-apps/Flick and open source projects"
        override val maintainerSection = "Maintainer"
        override val maintainerRole = "Creator & Primary Maintainer"
        override val supportSection = "Support"
        override val supportProject = "Support this project"
        override val supportProjectSubtitle = "View guidelines and ways to support on project homepage"
        override val sponsorDeveloper = "Sponsor Developer"
        override val sponsorDeveloperSubtitle = "Support via WeChat Pay or Alipay"
        override val sponsorDialogTitle = "Sponsor & Support"
        override val sponsorDialogSubtitle = "If CPPlayer helps you, feel free to sponsor and support ongoing development!"
        override val wechatPay = "WeChat Pay"
        override val alipay = "Alipay"
        override val sponsorAccount = "Recipient: Aurora星空(*森)"
        override val sponsorThanks = "Thanks to every supporter for your encouragement ❤️"
        override val saveQrCode = "Save QR code"
        override val savingQrCode = "Saving…"
        override val qrSavedToGallery = "Saved to gallery"
        override fun qrSavedTo(path: String) = "Saved to $path"
        override val qrSaveFailed = "Save failed. Check storage permission or disk space."
        override val close = "Close"
        override val footerNote = "CPPlayer · Compose Multiplatform"
        override val legacyCleanupNone = "No legacy Provider V1/V3 module directories were found; SDK providers and account data will not be deleted."
        override val legacyCleanupHint = "The following legacy module directories will be queued after you confirm " +
            "and removed only once the updated version starts successfully. If the install is cancelled or fails, " +
            "the old version keeps these directories:"
        override val updateConfirmDownload = "Confirm and download"
    }

    override val update: AppUpdateStrings = object : AppUpdateStrings {
        override val title = "New Version Available"
        override fun titleWithVersion(version: String) = "New version v$version available"
        override val changelogTitle = "Release notes"
        override val defaultChangelog = "This release includes routine experience optimizations, performance improvements, and bug fixes.\nKeeping updated is recommended for the best experience."
        override val viewDiffOnGithub = "View complete code diff on GitHub"
        override val downloadNow = "Update now"
        override val releasePage = "View details"
        override val later = "Remind me later"
        override val disableAutoUpdate = "Turn off auto updates"
        override val installPackage = "Select install type:"
        override fun publishedAt(date: String) = "Published: $date"
        override val packageAndroid = "Android package (.apk)"
        override val packageWindows = "Windows installer (.exe)"
        override val packageWindowsPortable = "Windows portable (ZIP)"
        override val packageMacos = "macOS DMG image"
        override val packageLinuxDeb = "Linux Debian/Ubuntu (.deb)"
        override val packageLinuxTar = "Linux universal package (.tar.gz)"
        override val packageLinux = "Linux package"
        override val packageUniversal = "Cross-platform package"
    }

    override val video: VideoStrings = object : VideoStrings {
        override val playbackFailed = "Playback failed"
        override val retry = "Retry"
        override val videoTitle = "Video"
        override val nowPlaying = "Now playing"
        override val resolvedQuality = "Resolution"
        override val standardQuality = "Standard"
        override val desktopPlayerHint = "Use a system player for full hardware decoding"
        override val openInSystemPlayer = "Open in system player (PotPlayer recommended)"
        override val copiedDirectLink = "Direct link copied"
        override val copyDirectLink = "Copy direct link"
        override val playPause = "Play/Pause"
        override val quality = "Quality"
        override val fullscreen = "Toggle fullscreen"
        override val scalingMode = "Scaling mode"
        override val openExternally = "Open in system player"
    }

    override val home: HomeStrings = object : HomeStrings {
        override val title = "Discover"
        override val subtitle = "Your music, always fresh"
        override val posterWall = "Cover wall"

        override val dailyTitle = "Daily Mix"
        override fun dailySubtitle(count: Int) = if (count == 1) "1 pick for you" else "$count picks for you"
        override val intelligenceTitle = "Heartbeat mode"

        override val discoveryTitle = "Discover playlists"
        override val rankingTitle = "Charts"
        override val rankingSubtitle = "Open a chart to browse its tracks"
        override val newAlbumsTitle = "New releases"
        override val newAlbumsSubtitle = "Open an album to see its tracks"
        override val hotArtistsTitle = "Popular artists"
        override val hotArtistsSubtitle = "Open an artist to see their tracks"
        override val newSongsTitle = "New songs"
        override val newSongsSubtitle = "Browse the latest releases by region"

        override val recentTitle = "Recently played"
        override val recentSubtitle = "Pick up a familiar tune"
        override val recentEmptySubtitle = "Songs you play will appear here"
        override val continueListening = "Continue listening"
        override val continueListeningSubtitle = "Pick up where you left off"

        override val playlistSourceRecommended = "For you"
        override val playlistSourceRecommendedHint = "Updated from what you've been playing"
        override val playlistSourceHot = "Popular"
        override val playlistSourceHotHint = "Playlists everyone is saving"
        override val playlistSourcePremium = "Curated"
        override val playlistSourcePremiumHint = "Hand-picked high-quality playlists"

        override val quickEntry = "Quick access"
        override val quickRadio = "Quick radio"
        override val localShuffle = "Shuffle local"
        override val localShuffleNote = "Shuffle music stored on this device"
        override val personalFm = "Personal FM"
        override val personalFmNote = "Continuous playback, no picking"
        override val fmRecommended = "For you"
        override val fmExplore = "Explore"
        override val fmFamiliar = "Familiar"
        override val fmPuzzle = "Puzzle"
        override val fmGuideTitle = "Updates as you listen"
        override val fmGuideBody = "Tap any track to start continuous Personal FM playback. New tracks are fetched and appended to this list automatically as you listen, or switch channels above anytime."
        override fun fmLiveStatus(count: Int) = if (count == 1) "Live · 1 track" else "Live · $count tracks"
        override fun fmReadyStatus(count: Int) = if (count == 1) "1 track" else "$count tracks"
        override val localLibrary = "Local library"
        override val localLibraryNote = "Browse music files on this device"
        override val intelligenceNote = "Built around today's picks"
        override val intelligenceNeedsDaily = "Needs today's picks first"
        override val recentNote = "Pick up where you left off"

        override val nowPlaying = "Now playing"
        override val paused = "Paused"

        override val loadIncompleteTitle = "Some recommendations didn't load"
        override val loadFailed = "Could not load"
        override val localModeTitle = "Local music mode"
        override val localModeNoteDesktop =
            "No online provider plugin is installed. Use Shuffle local or Local library above to play or scan music on this device."
        override val localModeNoteMobile =
            "No online provider plugin is installed. Use Shuffle local or Local library above to play or manage music on this device."
        override val openLocalLibrary = "Open local library"
        override val noPersonalizedTitle = "No personalized picks yet"
        override val noPersonalizedNote = "Sign in to sync your daily songs and playlists"

        override fun trackCount(count: Int) = if (count == 1) "1 track" else "$count tracks"
        override val rankingFallback = "Chart"
        override val viewTracks = "View tracks"
        override val unknownArtist = "Unknown artist"

        override val recommendedPlaylistFallback = "Recommended playlist"
        override val rankingCreator = "Chart"
    }

    override val engine: EngineStrings = object : EngineStrings {
        override val titleAndroid = "Playback engine & audiophile features"
        override val titleDesktop = "Audio output & hardware exclusivity"

        override val heroExclusive = "WASAPI Exclusive · Bit-perfect passthrough · Exclusive device access"
        override val heroSharedWasapi = "WASAPI Shared · Mixed by the system · Shares audio with other apps"
        override val heroSharedSystem = "Shared system output · Mixed by the system · Shares audio with other apps"
        override val heroAndroidFlick = "Rust Oboe · Low-latency passthrough · USB DAC · DSD decoding"
        override val heroAndroidStandard = "System default engine · Best network compatibility · Lowest power use"

        override val badgeExclusive = "Exclusive"
        override val badgeSharedDefault = "Shared · Default"
        override val badgeVip = "VIP"
        override val badgeDefault = "Default"

        override val sectionKernel = "Playback engine"
        override val kernelCurrent = "Active engine"
        override val kernelNeedsVip = "License not activated: fell back to the system engine (FlickEngine requires VIP)"
        override val kernelEnabled = "FlickEngine is enabled and will take effect on the next playback"
        override val kernelSystemNote = "System Media3 engine: steadier compatibility and power use; audiophile features unavailable"
        override val kernelSystemOption = "System Media3 (default)"
        override val kernelFlickOption = "FlickEngine (VIP)"
        override val kernelSwitchNote = "Switching engines won't interrupt playback; it applies on the next start (track change or replay)."

        override val sectionOutputMode = "Output mode"
        override val wasapiMode = "WASAPI output mode"
        override val wasapiExclusiveNote = "Device is exclusive: system sounds, browsers and meeting apps are muted during playback"
        override val wasapiSharedNote = "Device is shared: other apps can play audio too, but output goes through the system mixer"
        override val wasapiSharedOption = "Shared (default)"
        override val wasapiExclusiveOption = "Exclusive (VIP)"
        override val sharedOutput = "Shared output"
        override val sharedOutputNote = "This platform has no WASAPI exclusive path; audio always goes through the system mixer"
        override val ready = "Ready"
        override val exclusiveWarning =
            "Exclusive mode is off by default and requires VIP plus a manual opt-in. Once on, CPPlayer takes over " +
                "the audio device until you switch back to shared mode or quit — that is the cost of bit-perfect " +
                "output, not a fault. Switching reopens the audio device, so playback stops; press play again."

        override val sectionCapabilities = "FlickEngine audiophile features"
        override val oboeTitle = "Oboe / AAudio low-latency passthrough"
        override val oboeNote = "Bypasses AudioFlinger resampling and talks directly to the audio HAL or a USB DAC"
        override val usbDacTitle = "USB DAC passthrough (UAC 2.0)"
        override val usbDacNote = "An external DAC outputs at its own supported sample rate and bit depth, without system resampling"
        override val dsdTitle = "DSD master decoding"
        override val dsdNoteAndroid = "Built-in DSF / DFF decoding; DSD64 / DSD128 play directly (decoded to PCM)"
        override val dsdNoteDesktop =
            "Built-in DSF / DFF decoding; DSD64 / DSD128 play directly (decoded to PCM) in both modes"
        override val wasapiExclusiveTitle = "WASAPI exclusive passthrough"
        override val wasapiExclusiveNoteWindows = "Bypasses the Windows session mixer and system effects; the engine takes over the audio device"
        override val wasapiExclusiveNoteOther = "Only Windows provides a WASAPI exclusive path; this platform always uses shared output"
        override val bitPerfectTitle = "Bit-perfect output"
        override val bitPerfectNoteActive =
            "Sample rate is renegotiated to each track's native rate (44.1k / 48k / 96k / 192k) without system resampling"
        override val bitPerfectNoteOther =
            "Requires an exclusive audio path, which only Windows provides; this platform always resamples through the system mixer"
        override val bitPerfectNoteInactive =
            "Only applies in exclusive mode; shared mode resamples to the device's default rate through the system mixer"
        override val dspTitle = "64-bit floating-point DSP pipeline"
        override val dspNote = "5-band parametric EQ, channel balance and stereo width run inside the engine, not the system effect chain"

        override val sectionToggles = "Hardware output & decoding"
        override val androidUsbExclusiveTitle = "USB DAC exclusive mode (UAC 2.0)"
        override val androidUsbExclusiveNote = "Uses shared AAudio by default. When on, claims USB DAC only during active playback and releases immediately on pause, stop, or exit."
        override val androidUsbAutoResumeTitle = "Auto-resume if USB reconnects within 3s"
        override val androidUsbAutoResumeNote = "Automatically resumes playback if an external USB DAC or headset reconnects within 3 seconds. Works with both Media3 and FlickEngine."
        override val dsdModeTitle = "DSD output mode"
        override val dsdModePcm = "PCM decimation"
        override val dsdModeDop = "DoP"
        override val dsdModeNative = "Native DSD"
        override val dsdModeAuto = "Auto"
        override val togglesNote = "DSD output mode applies only to DSD tracks (PCM decimation works everywhere; DoP / Native require a compatible DAC)."

        override val badgeActive = "Active"
        override val badgeNeedsFlick = "Needs FlickEngine"
        override val badgeAvailable = "Available"
        override val badgeUnsupported = "Unsupported here"
        override val badgeNeedsVip = "Needs VIP"
        override val badgeNeedsExclusive = "Needs exclusive mode"
        override val badgeOff = "Off"

        override val sectionEffects = "Mixer & effects"
        override val openEffects = "Open audio effects"
        override val effectsNoteAndroid = "5-band parametric EQ, style presets, channel balance and loudness normalization"
        override val effectsNoteDesktop = "5-band parametric EQ, style presets, channel balance and stereo width"

        override val sectionCredits = "Open-source engine credits"
        override val creditsNote = "FlickEngine's core technology comes from the open-source moss-apps/Flick project (MIT); credit to its authors"
    }

    override val health: HealthStrings = object : HealthStrings {
        override val screenTitle = "Diagnostics & health"
        override val clearLogAction = "Clear log"
        override val clearTitle = "Clear diagnostics"
        override fun clearMessage(count: Int) =
            if (count == 1) "Clear the 1 recorded call?" else "Clear all $count recorded calls?"
        override val clearConfirm = "Clear"

        override val tabOverview = "Overview & Health"
        override val tabProbe = "API Probe"
        override val tabMethods = "Operations"
        override val tabLogs = "Call log"
        override val tabStatus = "Provider status"

        override val diagnosticTitle = "System Health Self-Check"
        override val diagnosticSubtitle = "Proactively verify network, provider, audio engine, cache and runtime"
        override val runDiagnostics = "Run Full Diagnostics"
        override val reRunDiagnostics = "Re-run Diagnostics"
        override val runningDiagnostics = "Running diagnostic self-check..."
        override val diagnosticHealthy = "System is healthy with all components operational"
        override val diagnosticWarning = "Warnings detected; some functionality may be degraded"
        override val diagnosticError = "Issues detected; core playback or source may be affected"
        override val diagnosticScoreSummary = "Diagnostic health score"
        override val diagnosticItemPassed = "Passed"
        override val diagnosticItemWarning = "Warning"
        override val diagnosticItemError = "Error"
        override val diagnosticItemSkipped = "Skipped"
        override val diagnosticNotRun = "Diagnostics have not been run"
        override val diagnosticNotRunHint = "Click Run Full Diagnostics to proactively test network, music APIs, audio engine and cache"
        override val diagnosticDetailsLabel = "Details"
        override val diagnosticSuggestionLabel = "Troubleshooting suggestion"

        override val checkNetwork = "Network & API connectivity"
        override val checkProvider = "Active provider status"
        override val checkSearch = "Track search probe"
        override val checkStreamUrl = "Audio stream URL probe"
        override val checkLyric = "Lyric parsing probe"
        override val checkDiscovery = "Charts & discovery probe"
        override val checkAudioEngine = "Audio engine & output"
        override val checkStorageCache = "Local storage & stream cache"
        override val checkSystemMemory = "Runtime memory & environment"

        override val toolkitTitle = "Troubleshooting Toolkit"
        override val copyReport = "Copy Diagnostic Report"
        override val copyReportDone = "Diagnostic report copied to clipboard"
        override val reloadActiveProvider = "Reload Active Provider"
        override val reloadProviderDone = "Active provider reloaded"
        override val clearStreamCacheAction = "Clear Stream Cache"
        override val clearStreamCacheTitle = "Clear Stream Cache"
        override val clearStreamCacheMessage = "Clear all cached temporary audio files? Downloaded songs will not be affected."
        override val clearStreamCacheDone = "Stream cache cleared"
        override val clearImageCacheAction = "Clear Image Cache"
        override val clearImageCacheTitle = "Clear Image Cache"
        override val clearImageCacheMessage = "Clear all cached album covers and images? Downloaded songs will not be affected."
        override val clearImageCacheDone = "Image cache cleared"
        override val resetAudioEngineAction = "Reset Audio Engine"
        override val resetAudioEngineDone = "Audio engine reset"

        override val probeTitle = "Provider API Probe Workbench"
        override val probeSubtitle = "Test API connectivity, latency, and response payloads directly"
        override val probeTargetProvider = "Target Provider"
        override val probeSwitchProvider = "Switch Active Provider"
        override val probeSwitchDone = "Active test provider switched"
        override val probeOperation = "Probe Operation"
        override val probeOpSearch = "Search Tracks"
        override val probeOpDetail = "Track Detail"
        override val probeOpStream = "Stream URL"
        override val probeOpLyric = "Lyrics"
        override val probeOpRankings = "Rankings & Charts"
        override val probeOpPlaylists = "Playlists"
        override val probeInputKeywordLabel = "Search keyword"
        override val probeInputMediaIdLabel = "Track MediaId or ID"
        override val probeQualityLabel = "Audio quality"
        override val probeExecute = "Execute Test"
        override val probeExecuting = "Executing..."
        override val probeResultLabel = "Probe Response Result"
        override val probeDurationLabel = "Latency"
        override val probeSuccess = "Call succeeded"
        override val probeUnsupported = "Unsupported by provider"
        override val probeFailed = "Call failed"
        override val probeEmptyPrompt = "Select an operation and click Execute Test to inspect real-time response"
        override val probeSearchQuickStream = "Test Stream"
        override val probeSearchQuickLyric = "Test Lyrics"
        override val probeCopyResult = "Copy Result"
        override val probeResultCopied = "Test result copied to clipboard"

        override val filterAll = "All"
        override val filterErrors = "Errors only"
        override val filterWarnings = "Warnings only"
        override val filterSuccess = "Success only"


        override val providerHealthSection = "Provider health scores & metrics"
        override val noLoadedProviders = "No providers loaded"
        override val systemStatus = "Overall system status"
        override fun callsSummary(totalCalls: Int, avgResponseMs: Long) =
            if (totalCalls == 1) "1 call · ${avgResponseMs}ms average" else "$totalCalls calls · ${avgResponseMs}ms average"
        override val levelOk = "Healthy"
        override val levelWarning = "Warning"
        override val levelError = "Error"
        override val success = "Success"
        override val failure = "Failed"
        override val unsupported = "Unsupported"
        override val activeNow = "Active"
        override fun providerMeta(id: String, type: String, version: String) =
            "ID: $id · Type: $type · Version: $version"
        override fun score(value: Int) = "$value pts"
        override fun loadError(error: String) = "Load error: $error"
        override val noCalls = "No calls recorded"
        override val successRate = "Success rate"
        override val metricCalls = "Calls"
        override val metricAverage = "Average"
        override val metricWarning = "Warnings"
        override fun recentError(error: String) = "Last error: $error"
        override val diagnostics = "Low-level diagnostics"

        override val noOperationStats = "No operation statistics yet"
        override val operationStatsHeader = "Operation performance by UnifiedMusicSource method"
        override fun operationSummary(totalCalls: Int, successRate: Int, avgResponseMs: Long) =
            if (totalCalls == 1) {
                "1 call · $successRate% success · ${avgResponseMs}ms average"
            } else {
                "$totalCalls calls · $successRate% success · ${avgResponseMs}ms average"
            }
        override fun failCount(count: Int) = "$count failed"
        override fun warningCount(count: Int) = "$count warnings"
        override val recentSamples = "Recent samples"

        override fun history(count: Int) =
            if (count == 1) "Call history (latest 1)" else "Call history (latest $count)"
        override val resultPrefix = "Result: "
        override val resultSuccess = "Success"
        override val resultUnsupported = "Provider doesn't support this operation"
        override val resultFailed = "Call failed"
        override fun errorMessage(message: String) = "Error: $message"

        override val providerListSection = "Registered & loaded providers"
        override val noRegisteredProviders = "No providers registered"
        override val switchToProvider = "Switch to this provider"
        override fun providerTypeVersion(type: String, version: String, apiVersion: Int) =
            "Type: $type  ·  Version: $version  ·  API v$apiVersion"
        override fun apiGeneration(tag: String) = "API generation: $tag"
        override fun startupFailed(error: String) = "Startup/load failed: $error"
        override val runtimeDiagnostics = "Runtime diagnostics"
    }

}
