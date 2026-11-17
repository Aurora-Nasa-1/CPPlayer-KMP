import { dirname, relative, resolve, sep } from 'node:path'

const EXTERNAL = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i

/**
 * 把指向 `docs/` 之外的相对链接改写成 GitHub 上的对应路径。
 *
 * 文档里常引用源码、脚本与仓库根文件（`../../scripts/...`、`../../../provider-sdk-kmp/...`），
 * 这些目标不在站点内。交给 GitHub 比在站点里复制一份更可靠：源码永远是最新的。
 */
export function repoLinks(md, { repo, docsRoot, branch = 'main' }) {
  const repoRoot = resolve(docsRoot, '..')

  md.core.ruler.push('cp-repo-links', (state) => {
    const fromFile = state.env?.relativePath
    if (!fromFile) return

    for (const token of state.tokens) {
      if (token.type !== 'inline' || !token.children) continue
      for (const child of token.children) {
        if (child.type !== 'link_open') continue
        const href = child.attrGet('href')
        if (!href || href.startsWith('#') || href.startsWith('/') || EXTERNAL.test(href)) continue

        const [pathPart, ...rest] = href.split('#')
        if (!pathPart) continue
        const hash = rest.length ? `#${rest.join('#')}` : ''

        const target = resolve(docsRoot, dirname(fromFile), pathPart)
        if (!relative(docsRoot, target).startsWith('..')) continue

        const repoPath = relative(repoRoot, target).split(sep).join('/')
        const isFile = /\.[a-z0-9]+$/i.test(repoPath)
        const kind = isFile ? 'blob' : 'tree'
        child.attrSet('href', `${repo}/${kind}/${branch}/${repoPath}${hash}`)
      }
    }
  })
}
