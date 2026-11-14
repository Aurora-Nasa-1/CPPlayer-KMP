import { defineConfig } from 'vitepress'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chineseSearchOptimize, pagefindPlugin } from 'vitepress-plugin-pagefind'
import { repoLinks } from './repo-links.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const docsRoot = resolve(here, '..')
const REPO = 'https://github.com/Aurora-Nasa-1/CPPlayer-KMP'

// 与 GitHub 的标题锚点保持一致。VitePress 默认实现会给数字开头的标题加下划线
// （`## 2. Kotlin` → `#_2-kotlin`），而文档里的中文锚点是按 GitHub 规则写的，
// 两边不一致就会出现「GitHub 上能跳、站点上跳不到」。
const slugify = (text: string): string =>
  text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')

export default defineConfig({
  lang: 'zh-CN',
  title: 'CPPlayer',
  titleTemplate: ':title · CPPlayer 文档',
  description: '跨平台音乐播放器（Kotlin Multiplatform）文档：用户指南、Provider 开发、架构、对外集成与发布。',
  base: '/CPPlayer-KMP/',
  srcExclude: ['node_modules/**'],
  lastUpdated: true,
  cleanUrls: false,

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/CPPlayer-KMP/logo.png' }],
    ['meta', { name: 'theme-color', content: '#4F55A5' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'CPPlayer 文档' }],
  ],

  markdown: {
    anchor: { slugify },
    config: (md) => md.use(repoLinks, { repo: REPO, docsRoot }),
    lineNumbers: false,
  },

  sitemap: { hostname: 'https://aurora-nasa-1.github.io/CPPlayer-KMP/' },

  // 全文搜索用 Pagefind：VitePress 自带的 MiniSearch 按空格与标点切词，
  // 对中文会把整句当成一个词。Pagefind 对 CJK 走字符 n-gram 索引，
  // 查询侧再用 Intl.Segmenter 断词，中文短语才能命中。
  vite: {
    plugins: [
      pagefindPlugin({
        btnPlaceholder: '搜索文档',
        placeholder: '搜索文档…',
        emptyText: '没有匹配结果',
        heading: '共 {{searchResult}} 条结果',
        forceLanguage: 'zh-cn',
        excludeSelector: ['img', 'a.header-anchor'],
        customSearchQuery: chineseSearchOptimize,
      }),
    ],
  },

  themeConfig: {
    logo: '/logo.png',
    siteTitle: 'CPPlayer',

    nav: [
      { text: '使用指南', link: '/USER_GUIDE' },
      { text: 'Provider 开发', link: '/dev/providers/', activeMatch: '/dev/providers/' },
      { text: '架构', link: '/dev/ARCHITECTURE', activeMatch: '/dev/(ARCHITECTURE|BUILD|FLICK_ENGINE|FONTS|I18N)' },
      { text: '集成 API', link: '/dev/INTEGRATION_API' },
      { text: '发布', link: '/dev/RELEASE' },
    ],

    sidebar: {
      '/dev/providers/': [
        {
          text: 'Provider 开发',
          items: [
            { text: '导读与阅读路径', link: '/dev/providers/' },
            { text: '快速上手', link: '/dev/providers/QUICKSTART' },
            { text: '契约', link: '/dev/providers/GUIDE' },
            { text: 'Manifest 与分发', link: '/dev/providers/MANIFEST' },
            { text: '分域实现', link: '/dev/providers/WORKFLOWS' },
            { text: 'JavaScript / TypeScript', link: '/dev/providers/JS' },
            { text: '原生库', link: '/dev/providers/NATIVE' },
          ],
        },
        {
          text: '参考',
          items: [
            { text: '操作总表（161 项）', link: '/dev/providers/SDK_OPERATIONS' },
            { text: '字段与类型（155 条）', link: '/dev/providers/SDK_TYPES' },
          ],
        },
      ],

      '/dev/lyrics/': [
        {
          text: '歌词源插件',
          items: [{ text: '概览与契约', link: '/dev/lyrics/LYRICS_PLUGIN' }],
        },
      ],

      '/dev/': [
        {
          text: '架构与工程',
          items: [
            { text: '架构与模块职责', link: '/dev/ARCHITECTURE' },
            { text: '构建与验证', link: '/dev/BUILD' },
            { text: 'FlickEngine 接入', link: '/dev/FLICK_ENGINE' },
            { text: '字体栈', link: '/dev/FONTS' },
            { text: '国际化（i18n）', link: '/dev/I18N' },
          ],
        },
        {
          text: '集成与发布',
          items: [
            { text: '对外集成 API v1', link: '/dev/INTEGRATION_API' },
            { text: '发布流程', link: '/dev/RELEASE' },
          ],
        },
      ],

      '/history/': [
        {
          text: '归档',
          items: [
            { text: '归档说明', link: '/history/' },
            { text: 'Provider SDK 设计提案', link: '/history/PROVIDER_SDK_PROPOSAL' },
            { text: '类型化 runtime 记录', link: '/history/PROVIDER_TYPED_RUNTIME' },
            { text: 'SDK 迁移状态', link: '/history/SDK_MIGRATION_STATUS' },
          ],
        },
      ],

      '/': [
        {
          text: '开始使用',
          items: [
            { text: '文档索引', link: '/' },
            { text: '使用指南', link: '/USER_GUIDE' },
          ],
        },
        {
          text: '按角色进入',
          items: [
            { text: 'Provider 开发', link: '/dev/providers/' },
            { text: '架构与构建', link: '/dev/ARCHITECTURE' },
            { text: '对外集成 API', link: '/dev/INTEGRATION_API' },
            { text: '发布流程', link: '/dev/RELEASE' },
            { text: '归档', link: '/history/' },
          ],
        },
      ],
    },

    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一篇', next: '下一篇' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '目录',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色',
    darkModeSwitchTitle: '切换到深色',
    externalLinkIcon: true,
    lastUpdated: {
      text: '最后更新',
      formatOptions: { dateStyle: 'medium', timeStyle: 'short' },
    },
    editLink: { pattern: `${REPO}/edit/main/docs/:path`, text: '在 GitHub 上编辑此页' },

    notFound: {
      title: '页面不存在',
      quote: '链接可能随文档重构失效了，回到索引重新找。',
      linkLabel: '回到文档索引',
      linkText: '文档索引',
    },

    footer: {
      message: '基于 Apache License 2.0 发布',
      copyright: 'Copyright © CPPlayer contributors',
    },

    socialLinks: [{ icon: 'github', link: REPO }],
  },
})
