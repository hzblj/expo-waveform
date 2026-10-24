/**
 * Checks what npm would receive, the way a consumer meets it. Packs every published package (each `prepack` builds it
 * through turbo's cache), asserts what must and must not ship in each tarball, then installs it into a throwaway app
 * and type-checks real usage with a consumer's TypeScript: no `source` condition, no workspace links, only what the
 * tarball declares.
 *
 * The negative cases matter as much as the positive ones: if a declaration failed to resolve, its imports would
 * quietly become `any` and everything would compile, so each `@ts-expect-error` is there to prove the types are real.
 */
import {execFileSync} from 'node:child_process'
import {existsSync} from 'node:fs'
import {mkdir, readdir, readFile, rm, writeFile} from 'node:fs/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const WORK = path.join(ROOT, '.pack-check')

const run = (command, args, cwd = ROOT) => execFileSync(command, args, {cwd, encoding: 'utf8'})

const fail = message => {
  console.error(`pack-check: ${message}`)
  process.exit(1)
}

const REQUIRED = {
  '@hzblj/expo-waveform': [
    'package.json',
    'README.md',
    'LICENSE',
    'build/index.js',
    'build/index.d.ts',
    'build/types.d.ts',
    'build/WaveformView.js',
    'src/index.ts',
    'expo-module.config.json',
    'ios/ExpoWaveform.podspec',
    'ios/ExpoWaveformModule.swift',
    'ios/WaveformView.swift',
    'ios/Core/WaveformAnalyzer.swift',
    'ios/Core/WaveformStore.swift',
    'ios/Core/WaveformGeometry.swift',
    'android/build.gradle',
    'android/src/main/AndroidManifest.xml',
    'android/src/main/java/com/hzblj/expowaveform/ExpoWaveformModule.kt',
    'android/src/main/java/com/hzblj/expowaveform/WaveformView.kt',
    'android/src/main/java/com/hzblj/expowaveform/WaveformAnalyzer.kt',
  ],
}

// The tests of all three and the build output of the native projects stay home. The TypeScript source ships: `exports`
// names it under the `source` condition.
const FORBIDDEN = [/^test\//, /^ios\/Tests\//, /^android\/src\/test\//, /\/build\/intermediates\//, /\.DS_Store$/]

const packages = []

for (const directory of await readdir(path.join(ROOT, 'packages'))) {
  const manifestPath = path.join(ROOT, 'packages', directory, 'package.json')

  if (!existsSync(manifestPath)) {
    continue
  }

  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))

  if (!manifest.private) {
    packages.push({directory, name: manifest.name})
  }
}

await rm(WORK, {force: true, recursive: true})
await mkdir(path.join(WORK, 'tarballs'), {recursive: true})

let fileCount = 0

for (const {directory, name} of packages) {
  const tarball = path.join(WORK, 'tarballs', `${directory}.tgz`)

  run('yarn', ['pack', '--out', tarball], path.join(ROOT, 'packages', directory))

  const files = run('tar', ['-tzf', tarball])
    .split('\n')
    .filter(Boolean)
    .map(file => file.replace(/^package\//, ''))

  const missing = (REQUIRED[name] ?? ['package.json', 'README.md']).filter(file => !files.includes(file))
  const leaked = files.filter(file => FORBIDDEN.some(pattern => pattern.test(file)))

  if (missing.length > 0) {
    fail(`${name} is missing:\n  ${missing.join('\n  ')}`)
  }

  if (leaked.length > 0) {
    fail(`${name} must not ship:\n  ${leaked.join('\n  ')}`)
  }

  const packed = JSON.parse(run('tar', ['-xzOf', tarball, 'package/package.json']))

  if (JSON.stringify(packed.dependencies ?? {}).includes('workspace:')) {
    fail(`${name} still declares a workspace: range: it would not install outside this repo`)
  }

  const installed = path.join(WORK, 'node_modules', name)

  await mkdir(installed, {recursive: true})
  run('tar', ['-xzf', tarball, '-C', installed, '--strip-components', '1'])
  fileCount += files.length
}

const consumer = `import {
  barCount,
  type PlaybackSource,
  toBars,
  useWaveformPlayback,
  Waveform,
  type WaveformData,
  type WaveformErrorCode,
  WaveformView,
} from '@hzblj/expo-waveform'

export const analyse = async (): Promise<number[]> => {
  const data: WaveformData = await Waveform.loadAsync('https://example.com/radio.mp3', {headers: {Authorization: 'Bearer x'}, samplesPerSecond: 50})
  await Waveform.clearCacheAsync()
  return toBars(data.samples, barCount(320, 3, 2), {noiseFloor: -60, scale: 'logarithmic'})
}

export const VoiceMessage = ({player}: {player: PlaybackSource}) => {
  const playback = useWaveformPlayback(player)
  return (
    <WaveformView
      alignment="bottom"
      markers={[1.2, 3.4]}
      onError={({code}: {code: WaveformErrorCode}) => code === 'network'}
      onLoad={({duration, fromCache}) => duration > 0 && fromCache}
      onSeek={time => time * 2}
      playback={playback}
      progressColor="#e10600"
      source={{headers: {Cookie: 'a=b'}, uri: 'https://example.com/radio.mp3'}}
      style={{height: 40}}
      variant="area"
    />
  )
}

export const Meter = ({levels}: {levels: number[]}) => <WaveformView live samples={levels} />

// @ts-expect-error: the variant is bars, area or line
export const wrongVariant = <WaveformView source="file:///a.m4a" variant="dots" />

// @ts-expect-error: a seek hands over seconds, not an event
export const wrongSeek = <WaveformView onSeek={(event: {nativeEvent: unknown}) => event} source="file:///a.m4a" />

// @ts-expect-error: loadAsync resolves with the analysis, not the samples alone
export const notSamples: number[] = await Waveform.loadAsync('file:///a.m4a')
`

await writeFile(path.join(WORK, 'consumer.tsx'), consumer)
await writeFile(
  path.join(WORK, 'tsconfig.json'),
  JSON.stringify(
    {
      compilerOptions: {noEmit: true, skipLibCheck: true, strict: true},
      extends: 'expo/tsconfig.base',
      include: ['consumer.tsx'],
    },
    null,
    2
  )
)

try {
  run('yarn', ['run', '-T', 'tsc', '-p', WORK])
} catch (error) {
  fail(`a consumer's type check failed:\n${error.stdout}`)
}

await rm(WORK, {force: true, recursive: true})

console.log(`pack-check: ${packages.length} package, ${fileCount} files, and a consumer type-checks`)
