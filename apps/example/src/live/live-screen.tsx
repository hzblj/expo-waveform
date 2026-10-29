import {LiveWaveformView, useWaveformPlayback, WaveformView} from '@hzblj/expo-waveform'
import {Box, Button, Island, RnScrollView, RnText, useAppTheme, VStack} from '@hzblj/kara-ui'
import {
  type AudioSample,
  RecordingPresets,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
  useAudioPlayer,
  useAudioRecorder,
  useAudioRecorderState,
  useAudioSampleListener,
} from 'expo-audio'
import {useCallback, useEffect, useMemo, useRef, useState} from 'react'

import {MockMeter} from '../../modules/mock-meter'
import {capture} from '../capture'
import {FIRST_CLIP, teamColor} from '../f1/radio'
import {Caption, formatDuration} from '../ui/format'
import {Section} from '../ui/page'
import {PlayButton} from '../ui/play-button'
import {togglePlayback} from '../ui/toggle'

/** How many levels a meter keeps: more than fit, so the oldest scroll off the left edge. */
const KEEP = 240
/** How often a meter adds a level, in milliseconds: a bar every 50 ms reads as speech, not noise. */
const INTERVAL = 50
const NOISE_FLOOR = -50

/** Decibels as a level from 0 to 1 above the noise floor: the same scale the view draws a file in. */
const levelOf = (decibels: number) => Math.min(1, Math.max(0, (decibels - NOISE_FLOOR) / -NOISE_FLOOR))

const RECORDING = {...RecordingPresets.HIGH_QUALITY, isMeteringEnabled: true}

/**
 * The stand-in microphone's rate: a level every 85 ms (4096-frame buffers at 48 kHz), each up to 15 ms early or late,
 * as a main thread hands them over.
 */
const MOCK_INTERVAL = 0.085
const MOCK_JITTER = 0.015
/** A busy main thread: held 60 ms every 600 ms, about what an app does when each new word updates a screen. */
const HITCH_EVERY = 0.6
const HITCH_DURATION = 0.06

/** Levels as they come, the newest last. */
const useMeter = () => {
  const [levels, setLevels] = useState<number[]>([])
  const push = useCallback((level: number) => setLevels(previous => [...previous.slice(-(KEEP - 1)), level]), [])
  const clear = useCallback(() => setLevels([]), [])

  return {clear, levels, push}
}

/**
 * The microphone, metered live: expo-audio's recorder reports its level every 50 ms and each one becomes a bar,
 * scrolling in from the right. Stop, and the take is a file: analysed like any other and played back.
 */
const Microphone = () => {
  const theme = useAppTheme()
  const recorder = useAudioRecorder(RECORDING)
  const state = useAudioRecorderState(recorder, INTERVAL)
  const meter = useMeter()
  const [take, setTake] = useState<string | null>(null)
  const [denied, setDenied] = useState(false)
  const player = useAudioPlayer(take ? {uri: take} : null, {updateInterval: 250})
  const playback = useWaveformPlayback(player)
  const {push} = meter

  useEffect(() => {
    if (state.isRecording && state.metering !== undefined) {
      push(levelOf(state.metering))
    }
  }, [state.isRecording, state.metering, push])

  const toggle = async () => {
    if (state.isRecording) {
      await recorder.stop()
      await setAudioModeAsync({allowsRecording: false, playsInSilentMode: true})
      setTake(recorder.uri)
      return
    }
    const permission = await requestRecordingPermissionsAsync()
    if (!permission.granted) {
      setDenied(true)
      return
    }
    setDenied(false)
    setTake(null)
    meter.clear()
    await setAudioModeAsync({allowsRecording: true, playsInSilentMode: true})
    await recorder.prepareToRecordAsync()
    recorder.record()
  }

  return (
    <Section
      caption={
        denied
          ? 'The microphone is off for this app: allow it in Settings.'
          : 'A simulator has no microphone of its own; the radio below meters on any device.'
      }
      title="Microphone"
    >
      <Box bg="surface.primary" borderRadius="large" gap="s12" p="s16">
        <Caption color={state.isRecording ? 'content.accent' : 'content.secondary'}>
          {state.isRecording ? `Recording · ${formatDuration(state.durationMillis / 1000)}` : 'Live'}
        </Caption>
        <WaveformView
          color={theme.color(state.isRecording ? 'content.accent' : 'content.tertiary')}
          live
          samples={meter.levels}
          style={{height: 96}}
          testID="microphone"
        />
      </Box>
      <Island>
        <VStack wide>
          <Button label={state.isRecording ? 'Stop' : 'Record'} onPress={toggle} />
        </VStack>
      </Island>
      {take && !state.isRecording ? (
        <Box bg="surface.primary" borderRadius="large" gap="s12" p="s16">
          <Caption>{`Your take · ${formatDuration(playback?.duration)}`}</Caption>
          <Box align="center" gap="s12" row>
            <PlayButton
              color={theme.color('fill.accent.primary')}
              label={playback?.playing ? 'Pause your take' : 'Play your take'}
              onPress={() => togglePlayback(player)}
              playing={playback?.playing ?? false}
            />
            <WaveformView
              accessibilityLabel="Your take"
              color={theme.color('content.tertiary')}
              cursor
              onSeek={time => player.seekTo(time)}
              playback={playback}
              progressColor={theme.color('content.accent')}
              source={take}
              style={{flex: 1, height: 48}}
              testID="take"
            />
          </Box>
        </Box>
      ) : null}
    </Section>
  )
}

/**
 * `LiveWaveformView` following a stand-in microphone (`modules/mock-meter`), natively: levels shaped like speech that
 * come as unevenly as a real one's, on a simulator too. With the main thread held now and then, the bars keep flowing
 * on iOS, where Core Animation moves them.
 */
const Simulated = () => {
  const theme = useAppTheme()
  const meter = useMemo(() => new MockMeter.Meter(), [])
  const [running, setRunning] = useState(capture.mock === '1')
  const [busy, setBusy] = useState(capture.hitches === '1')

  useEffect(() => {
    if (running) {
      meter.start(MOCK_INTERVAL, MOCK_JITTER)
    }
    return () => meter.stop()
  }, [meter, running])

  useEffect(() => {
    MockMeter.setMainThreadHitches(busy ? HITCH_EVERY : 0, HITCH_DURATION)
    return () => MockMeter.setMainThreadHitches(0, 0)
  }, [busy])

  return (
    <Section
      caption="A stand-in microphone: a level every 85 ms, give or take 15. A busy main thread is held for 60 ms every 600 ms."
      title="Simulated"
    >
      <Box bg="surface.primary" borderRadius="large" gap="s12" p="s16">
        <Caption color={running ? 'content.accent' : 'content.secondary'}>
          {running ? (busy ? 'Listening · busy main thread' : 'Listening') : 'Live'}
        </Caption>
        <LiveWaveformView
          barGap={3}
          barWidth={3}
          color={theme.color('content.accent')}
          growIn
          meter={meter}
          minBarHeight={3}
          style={{height: 64}}
          testID="simulated"
        />
      </Box>
      <Island>
        <VStack spacing="s8" wide>
          <Button label={running ? 'Stop' : 'Listen'} onPress={() => setRunning(value => !value)} />
          <Button label={busy ? 'Free the main thread' : 'Busy main thread'} onPress={() => setBusy(value => !value)} />
        </VStack>
      </Island>
    </Section>
  )
}

/** The root mean square of a sample's first channel, in decibels. */
const decibelsOf = (sample: AudioSample) => {
  const frames = sample.channels[0]?.frames ?? []
  if (frames.length === 0) {
    return -160
  }
  let sum = 0
  for (const frame of frames) {
    sum += frame * frame
  }
  return 20 * Math.log10(Math.sqrt(sum / frames.length) || 1e-8)
}

/**
 * A team radio message metered as it plays, through expo-audio's sample listener: the loudest stretch of every 50 ms
 * becomes a bar. Works on a simulator, where the microphone does not.
 */
const RadioMeter = () => {
  const clip = FIRST_CLIP
  const player = useAudioPlayer(clip ? {uri: clip.url} : null, {updateInterval: 250})
  const playback = useWaveformPlayback(player)
  const meter = useMeter()
  const window = useRef({at: 0, loudest: -160})
  const color = teamColor(clip?.driver)

  useAudioSampleListener(player, sample => {
    const now = Date.now()
    window.current.loudest = Math.max(window.current.loudest, decibelsOf(sample))
    if (now - window.current.at >= INTERVAL) {
      meter.push(levelOf(window.current.loudest))
      window.current = {at: now, loudest: -160}
    }
  })

  const toggle = async () => {
    if (!player.playing) {
      // Android's visualizer needs the microphone permission to read what plays.
      await requestRecordingPermissionsAsync()
    }
    togglePlayback(player)
  }

  return (
    <Section caption="The same live view, fed by what plays instead of what it hears." title="Radio">
      <Box bg="surface.primary" borderRadius="large" gap="s12" p="s16">
        <Caption>
          {clip?.driver ? `${clip.driver.name_acronym} · ${clip.driver.team_name ?? ''}` : 'Loading the radio'}
        </Caption>
        <WaveformView color={color} live samples={meter.levels} style={{height: 96}} testID="radio-meter" />
        <Box align="center" gap="s12" row>
          <PlayButton
            color={color}
            label={playback?.playing ? 'Pause the radio' : 'Play the radio'}
            onPress={toggle}
            playing={playback?.playing ?? false}
          />
          <RnText color="content.secondary" variant="footnote">
            {formatDuration(playback?.duration)}
          </RnText>
        </Box>
      </Box>
    </Section>
  )
}

export const LiveScreen = () => (
  <RnScrollView
    bg="surface.base"
    content={{gap: 's32', pb: 's96', pt: 's8', px: 's20'}}
    contentInsetAdjustmentBehavior="automatic"
    flex={1}
    testID="live"
  >
    <Simulated />
    <Microphone />
    <RadioMeter />
  </RnScrollView>
)
