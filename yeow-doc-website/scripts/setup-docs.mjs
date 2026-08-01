// 创建 docs/ → ../Yeow-Docs/zh 的目录链接（Windows junction / POSIX symlink）。
// 这样文档站点零复制引用仓库内的文档源，且保持同步。
import { existsSync, symlinkSync, lstatSync, realpathSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const link = resolve(root, 'docs')
const target = resolve(root, '..', 'Yeow-Docs', 'zh')

if (existsSync(link)) {
    const st = lstatSync(link)
    if (st.isSymbolicLink()) {
        try {
            if (realpathSync(link) === realpathSync(target)) {
                console.log('docs/ already linked to Yeow-Docs/zh')
                process.exit(0)
            }
            console.error(`docs/ points to ${realpathSync(link)} — remove it and re-run this script`)
            process.exit(1)
        } catch {
            console.error('docs/ link is broken — remove it and re-run this script')
            process.exit(1)
        }
    }
    console.error('docs/ exists as a regular directory — remove it first (it would shadow the linked docs)')
    process.exit(1)
}

try {
    symlinkSync(target, link, process.platform === 'win32' ? 'junction' : 'dir')
    console.log(`linked docs/ -> ${target}`)
} catch (e) {
    console.error(`failed to create docs link: ${e.message}`)
    process.exit(1)
}
