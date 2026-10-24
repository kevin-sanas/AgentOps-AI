import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const lock = JSON.parse(await readFile(path.join(root, 'package-lock.json'), 'utf8'))
const sections = ['Dawnwatch third-party notices\n\nIncludes installed build dependencies for attribution. Optional packages for other platforms are listed in the lockfile/SBOM and are not distributed in this build.\n']
for (const [location, metadata] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b))) {
  if (!location) continue
  const directory = path.join(root, location)
  let files
  try { files = await readdir(directory, { withFileTypes: true }) }
  catch (error) { if (metadata.optional && error.code === 'ENOENT') continue; throw error }
  const manifest = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'))
  let noticeDirectory=directory
  // Rolldown publishes binding packages without LICENSE; the same-version parent ships
  // both the project's license and notices for the bundled native dependencies.
  if (manifest.name.startsWith('@rolldown/binding-')) {
    const parentDirectory=path.join(root,'node_modules/rolldown')
    const parent=JSON.parse(await readFile(path.join(parentDirectory,'package.json'),'utf8'))
    if(parent.version!==manifest.version || parent.license!==manifest.license) throw new Error('Rolldown license version mismatch')
    noticeDirectory=parentDirectory;files=await readdir(noticeDirectory,{withFileTypes:true})
  }
  let notices = files.filter(f => f.isFile() && /^(?:(?:third[-_]party[-_])?licen[cs]e|notice)(\.|$)/i.test(f.name))
  if(manifest.name==='@vue/devtools-api' && manifest.version==='6.6.4') {
    noticeDirectory=path.join(root,'licenses');notices=[{name:'vue-devtools-api-6.6.4-LICENSE'}]
  }
  if (!notices.length) throw new Error(`Missing license file: ${manifest.name}`)
  sections.push(`\n${'='.repeat(72)}\n${manifest.name} ${manifest.version}\nDeclared license: ${manifest.license || metadata.license || 'see notices'}\n`)
  for (const notice of notices.sort((a, b) => a.name.localeCompare(b.name))) sections.push(`\n${notice.name}\n${await readFile(path.join(noticeDirectory, notice.name), 'utf8')}\n`)
}
await mkdir(path.join(root, 'public'), { recursive: true })
await writeFile(path.join(root, 'public/THIRD_PARTY_NOTICES.txt'), sections.join(''), 'utf8')
console.log(`Third-party notices generated for ${sections.filter(s => s.startsWith('\n===')).length} installed packages.`)
