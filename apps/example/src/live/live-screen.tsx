import {useWaveformPlayback, WaveformView} from '@hzblj/expo-waveform'
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
import {useCallback, useEffect, useRef, useState} from 'react'

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
    <Microphone />
    <RadioMeter />
  </RnScrollView>
)
