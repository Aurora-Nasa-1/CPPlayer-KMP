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
}
