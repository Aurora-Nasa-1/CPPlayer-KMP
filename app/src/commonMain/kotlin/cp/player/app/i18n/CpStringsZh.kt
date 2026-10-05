package cp.player.app.i18n

/**
 * 简体中文文案 —— **兜底语言**。
 *
 * ⚠️ 加 / 改文案时这里的改动**必须同步到 [CpStringsEn]**：两边实现同一个接口，
 * 漏了会编译不过（这正是选接口方案而非 XML 的理由）。
 */
object CpStringsZh : CpStrings {
    override val language: LanguageStrings = object : LanguageStrings {
        override val screenTitle = "语言"
        override val optionSystem = "跟随系统"
        override val optionSystemNote = "按系统语言显示；系统语言不受支持时回落简体中文"
        override val optionZhHans = "简体中文"

        /**
         * 中文界面下**仍然写英文**—— 这是有意的，不要当漏译改掉。
         *
         * 语言列表按语言**自称**显示是国际惯例：用户找的是「English」这个他认识的名字，
         * 写成「英文」反而多一层翻译。Windows / macOS 的中文语言列表同样混排。
         * [itemLanguage] 的副标题里出现 `English` 同理（那里在列举可选项）。
         */
        override val optionEnglish = "English"
        override val current = "当前"
        override val applyNote = "切换后立即生效，无需重启应用。"
    }

    override val settings: SettingsStrings = object : SettingsStrings {
        override val screenTitle = "设置"
        override val groupGeneral = "通用"
        override val groupAccountProvider = "账号与音源"
        override val groupConnectivity = "连接与集成"
        override val groupOther = "其他"

        override val itemLanguage = CpTextPair(
            title = "语言",
            subtitle = "跟随系统，或固定为简体中文 / English", // 混排 English 的理由见 optionEnglish
        )
        override val itemAppearance = CpTextPair(
            title = "外观与主题",
            subtitle = "主题模式、取色来源与纯黑背景",
        )
        override val itemPlayback = CpTextPair(
            title = "播放与音质",
            subtitle = "默认音质与睡眠定时",
        )
        override val itemStorage = CpTextPair(
            title = "下载与存储",
            subtitle = "下载目录、歌曲缓存与图片缓存",
        )
        override val itemShortcuts = CpTextPair(
            title = "快捷键",
            subtitle = "查看与自定义桌面快捷键",
        )
        override val itemAccount = CpTextPair(
            title = "账号与登录",
            subtitle = "登录音源账号、切换与管理已保存的账号",
        )
        override val itemProviders = CpTextPair(
            title = "音源管理",
            subtitle = "导入、切换或移除音源模块",
        )
        override val itemStreamOutput = CpTextPair(
            title = "本地流输出",
            subtitle = "把音频流通过 HTTP 对外提供",
        )
        override val itemIntegration = CpTextPair(
            title = "外部推送与集成",
            subtitle = "推送到接收端、开放第三方接口",
        )
        override val itemStandby = CpTextPair(
            title = "局域网设备",
            subtitle = "同一网络里的其他 CPPlayer：互相发现与自动同步听歌记录",
        )
        override val itemAbout = CpTextPair(
            title = "关于与支持",
            subtitle = "版本、更新与项目支持",
        )
        override val itemDiagnostics = CpTextPair(
            title = "诊断",
            subtitle = "查看接口调用状态、日志与回退信息",
        )
        override val itemRenderTuning = CpTextPair(
            title = "渲染后端",
            subtitle = "显示后端与垂直同步；画面撕裂或卡顿时可调整",
        )
        override val itemOnboarding = CpTextPair(
            title = "重看新手引导",
            subtitle = "重新走一遍首次使用引导",
        )
    }
}
