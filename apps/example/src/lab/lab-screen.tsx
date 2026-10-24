import {toBars, Waveform, type WaveformData, type WaveformErrorCode} from '@hzblj/expo-waveform'
import {
  Box,
  Button,
  Island,
  Picker,
  RnScrollView,
  RnText,
  SearchBar,
  SegmentedControl,
  useAppTheme,
  VStack,
} from '@hzblj/kara-ui'
import {useMemo, useState} from 'react'
import {useWindowDimensions} from 'react-native'

import {FIRST_CLIP, SESSIONS} from '../f1/radio'
import {Caption} from '../ui/format'
import {Section} from '../ui/page'

const RATES = [25, 100, 400] as const

type Result = {data: WaveformData; milliseconds: number} | {error: {code: WaveformErrorCode; message: string}}

/** Sources that fail, one for each error code. */
const FAILURES = [
  {label: 'A missing file', uri: 'file:///nowhere/radio.mp3'},
  {label: 'Not audio', uri: 'https://github.com/hzblj/expo-waveform'},
  {label: 'A 404', uri: 'https://livetiming.formula1.com/static/nowhere.mp3'},
  {label: 'A photo library URI', uri: 'ph://radio'},
]

/** Every message of the weekend, by session. */
const CLIPS = SESSIONS.flatMap(session => session.clips)

const OPTIONS = SESSIONS.flatMap(session =>
  session.clips.map(clip => ({
    label: `${session.name} · ${clip.driver?.name_acronym ?? '???'} · ${clip.date.toLocaleTimeString('en-GB')}`,
    value: clip.id,
  }))
)

const Stat = ({label, value}: {label: string; value: string}) => (
  <Box gap="s2" style={{minWidth: '45%'}}>
    <Caption color="content.tertiary">{label}</Caption>
    <RnText monospacedDigit variant="headline">
      {value}
    </RnText>
  </Box>
)

/** An analysis drawn in JavaScript, from `toBars`: the samples are plain numbers to use as you like. */
const Bars = ({samples}: {samples: number[]}) => {
  const theme = useAppTheme()
  const {width} = useWindowDimensions()
  const bars = useMemo(() => toBars(samples, Math.floor((width - 72) / 4)), [samples, width])

  return (
    <Box align="flex-end" gap="s2" h={64} row>
      {bars.map((level, index) => (
        <Box
          key={index}
          style={{backgroundColor: theme.color('content.accent'), borderRadius: 1, height: 2 + level * 62, width: 2}}
        />
      ))}
    </Box>
  )
}

/**
 * `Waveform.loadAsync` on its own: the analysis behind every view, its numbers, the time it took, the cache, and what
 * each error looks like.
 */
export const LabScreen = () => {
  const [clipId, setClipId] = useState<string | null>(null)
  const clip = CLIPS.find(candidate => candidate.id === clipId) ?? FIRST_CLIP
  const [customUrl, setCustomUrl] = useState('')
  const source = customUrl.trim() || clip?.url
  const [rate, setRate] = useState(1)
  const [result, setResult] = useState<Result | null>(null)
  const [loading, setLoading] = useState(false)

  const load = async (uri: string) => {
    setLoading(true)
    const started = performance.now()
    try {
      const data = await Waveform.loadAsync(uri, {samplesPerSecond: RATES[rate]})
      setResult({data, milliseconds: performance.now() - started})
    } catch (error) {
      const {code, message} = error as {code: WaveformErrorCode; message: string}
      setResult({error: {code, message}})
    } finally {
      setLoading(false)
    }
  }

  return (
    <RnScrollView
      bg="surface.base"
      content={{gap: 's32', pb: 's96', pt: 's8', px: 's20'}}
      contentInsetAdjustmentBehavior="automatic"
      flex={1}
      testID="lab"
    >
      <Section caption="A message of the weekend, or any audio or video file by its URL." title="Message">
        <Picker onValueChange={setClipId} options={OPTIONS} value={clip?.id ?? ''} />
        <SearchBar
          onChangeText={setCustomUrl}
          placeholder="https://…/radio.mp3"
          testID="custom-url"
          value={customUrl}
        />
      </Section>

      <Section
        caption="Samples per second of audio. A long file gets fewer: an analysis keeps to 20,000 samples."
        title="Resolution"
      >
        <SegmentedControl onChange={setRate} selectedIndex={rate} values={RATES.map(value => `${value} / s`)} />
      </Section>

      <Island>
        <VStack spacing="s8" wide>
          <Button
            disabled={!source || loading}
            label={loading ? 'Analysing…' : 'Analyse'}
            onPress={() => source && load(source)}
          />
          <Button label="Clear the cache" onPress={() => Waveform.clearCacheAsync()} variant="secondary" />
        </VStack>
      </Island>

      {result && 'data' in result ? (
        <Section
          caption="Analysed once, then read from the cache: in memory while the app runs, on disk after."
          title="Analysis"
        >
          <Box bg="surface.primary" borderRadius="large" gap="s16" p="s16">
            <Box gap="s16" row wrap>
              <Stat label="Duration" value={`${result.data.duration.toFixed(2)} s`} />
              <Stat label="Samples" value={result.data.samples.length.toLocaleString('en-GB')} />
              <Stat label="Each" value={`${(result.data.sampleDuration * 1000).toFixed(1)} ms`} />
              <Stat
                label={result.data.fromCache ? 'From the cache' : 'Analysed'}
                value={`${Math.round(result.milliseconds)} ms`}
              />
            </Box>
            <Bars samples={result.data.samples} />
          </Box>
        </Section>
      ) : null}

      {result && 'error' in result ? (
        <Section title="Error">
          <Box bg="surface.primary" borderRadius="large" gap="s8" p="s16">
            <RnText color="content.destructive" variant="headline">
              {result.error.code}
            </RnText>
            <RnText color="content.secondary" variant="footnote">
              {result.error.message}
            </RnText>
          </Box>
        </Section>
      ) : null}

      <Section caption="Each rejects with a code: audio, network or unsupported." title="Failures">
        <Island>
          <VStack spacing="s8" wide>
            {FAILURES.map(failure => (
              <Button key={failure.uri} label={failure.label} onPress={() => load(failure.uri)} variant="secondary" />
            ))}
          </VStack>
        </Island>
      </Section>
    </RnScrollView>
  )
}
