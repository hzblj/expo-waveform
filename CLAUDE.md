# expo-waveform

Yarn 4 + Turborepo monorepo for **`@hzblj/expo-waveform`**: native audio waveforms for Expo, analysed on the device
(AVAssetReader, MediaCodec) and cached, drawn by a native view that follows playback, seeks with haptics and meters a
recording live. Read the package's own `CLAUDE.md` before touching it.

| Path | What lives there |
| --- | --- |
| `packages/expo-waveform/` | The published package: `src/` (JS: the view, `useWaveformPlayback`, `Waveform`, `toBars`), `ios/` (Swift; `ios/Core` also builds as a SwiftPM package for Mac tests), `android/` (Kotlin). No config plugin. |
| `apps/example/` | The example: a Formula 1 weekend's team radio (captured from OpenF1 into `src/f1/radio.json`) as waveforms with word markers and words lit as they are said (from `@hzblj/expo-speech`), a style playground, live meters, the analysis lab, built from `@hzblj/kara-ui`. Private. |
| `scripts/pack-check.mjs` | Packs the package as npm gets it, checks what ships and type-checks a throwaway consumer. |
| `.changeset/`, `.github/workflows/` | Release notes, CI (Biome, types, tests, pack check) and the changesets release to npm. |

## Commands

```bash
yarn ios / yarn android   # run the example (dev client)
yarn prebuild             # clean `expo prebuild` of the example
yarn lint / yarn typecheck
yarn test                 # JS tests (Node)
yarn test:ios             # Swift tests on the Mac: the analyser decodes audio the tests write with AVAudioFile
yarn test:android         # Kotlin JVM tests (after `yarn prebuild`)
yarn pack:check
yarn changeset
```

## Conventions

- Commits are Jan Blazej's (`hello@janblazej.dev`), with no co-author trailers.
- Workspaces resolve each other's TypeScript source: the example maps `@hzblj/expo-waveform` to `src/` (tsconfig
  `paths`, `metro.config.js`), so no build is needed to run it. The published entry points are `build/`.
- Docs copy (READMEs, JSDoc that becomes docs, changesets) has no em dashes.
- `yarn release` publishes through Yarn (`workspace:` ranges rewritten) and tags through changesets.
