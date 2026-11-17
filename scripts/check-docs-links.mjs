#!/usr/bin/env node
/**
 * check-docs-links.mjs — 校验 VitePress 产物（docs/.vitepress/dist）的站内链接与锚点。
 *
 *   node scripts/check-docs-links.mjs          # 校验
 *   node scripts/check-docs-links.mjs --quiet  # 只报错
 *
 * 拦三类问题：
 *   1. 站内链接指向不存在的页面
 *   2. `page.html#anchor` 的锚点在目标页不存在（VitePress 自身不校验锚点）
 *   3. 站内链接指向不存在的静态资源
 *
 * 必须在 `npm --prefix docs run build` 之后运行。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, posix, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(repo, 'docs', '.vitepress', 'dist')
const base = '/CPPlayer-KMP/'
const quiet = process.argv.includes('--quiet')

if (!existsSync(dist)) {
  console.error(`未找到构建产物：${dist}\n先跑 npm --prefix docs run build`)
  process.exit(1)
}

const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })

const files = walk(dist).map((full) => relative(dist, full).split('\\').join('/'))
const pages = new Map()

for (const file of files.filter((f) => f.endsWith('.html'))) {
  const html = readFileSync(join(dist, file), 'utf8')
  const ids = new Set([...html.matchAll(/\sid="([^"]*)"/g)].map(([, id]) => decodeURIComponent(id)))
  pages.set(routeOf(file), { file, ids })
}

function routeOf(file) {
  const route = `/${file}`
  return route.endsWith('/index.html') ? route.slice(0, -'index.html'.length) : route.replace(/\.html$/, '')
}

function pageFor(pathname) {
  const clean = pathname.endsWith('.html') ? pathname.slice(0, -'.html'.length) : pathname
  if (pages.has(clean)) return clean
  const withSlash = clean.endsWith('/') ? clean : `${clean}/`
  return pages.has(withSlash) ? withSlash : null
}

const errors = []
let checked = 0

for (const [page, { file, ids }] of pages) {
  const html = readFileSync(join(dist, file), 'utf8')

  for (const [, raw] of html.matchAll(/href="([^"]+)"/g)) {
    const href = raw.replaceAll('&amp;', '&')
    if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) continue

    const [rawPath, rawHash = ''] = href.split('#')
    const hash = rawHash ? decodeURIComponent(rawHash) : ''
    let pathname = decodeURIComponent(rawPath)

    if (!pathname) pathname = page
    else if (pathname.startsWith(base)) pathname = pathname.slice(base.length - 1)
    else if (!pathname.startsWith('/')) {
      const from = page.endsWith('/') ? page : posix.dirname(page)
      pathname = posix.normalize(posix.join(from, pathname))
    }

    checked++

    if (/\.[a-z0-9]+$/i.test(pathname) && !pathname.endsWith('.html')) {
      if (!existsSync(join(dist, pathname))) errors.push(`${page}: 资源不存在 ${pathname}`)
      continue
    }

    const target = pageFor(pathname)
    if (!target) {
      errors.push(`${page}: 页面不存在 ${pathname}`)
      continue
    }
    if (hash && !pages.get(target).ids.has(hash)) {
      errors.push(`${page}: 锚点不存在 ${target}#${hash}`)
    }
  }
}

if (errors.length) {
  for (const line of errors) console.error(`✖ ${line}`)
  console.error(`\n${errors.length} 个问题（共检查 ${checked} 个链接）`)
  process.exit(1)
}

if (!quiet) console.log(`✓ ${pages.size} 个页面、${checked} 个链接，锚点与资源全部命中`)
