import {FlagView} from '@hzblj/expo-flags-kit'
import {useSpeechTranscriber} from '@hzblj/expo-speech'
import {Box, Chip, EmptyState, Island, RnFlatList, RnText} from '@hzblj/kara-ui'
import {setAudioModeAsync, useAudioPlayer} from 'expo-audio'
import {useLocalSearchParams} from 'expo-router'
import {useCallback, useEffect, useRef, useState} from 'react'
import {capture} from '../capture'
import {flagCode, MEETING, type RadioClip, SESSIONS} from '../f1/radio'
import {Caption} from '../ui/format'
import {togglePlayback} from '../ui/toggle'
import {RadioCard} from './radio-card'

/** The weekend, and a chip for each of its sessions. */
const Header = ({onSelect, selected}: {selected: number | null; onSelect: (sessionKey: number) => void}) => {
  const flag = flagCode(MEETING.country_code)

  return (
    <Box gap="s16" pb="s8">
      <Box align="center" gap="s12" row>
        {flag ? <FlagView code={flag as never} style={{borderRadius: 4, height: 24, width: 36}} /> : null}
        <Box fill gap="s2">
          <RnText style={{fontWeight: '700'}} variant="headingXs">
            {MEETING.meeting_name}
          </RnText>
          <Caption>{`${MEETING.circuit_short_name} · ${MEETING.year}`}</Caption>
        </Box>
      </Box>
      <Box gap="s8" row wrap>
        {SESSIONS.map(session => (
          <Chip
            key={session.key}
            label={session.name}
            onPress={() => onSelect(session.key)}
            selected={session.key === selected}
          />
        ))}
      </Box>
    </Box>
  )
}

/**
 * A weekend's team radio, newest first, a session at a time. Every message is a waveform, analysed on the device as it
 * comes on screen and cached by URL, so scrolling back costs nothing. One player plays them all.
 */
export const RadioScreen = () => {
  const [selected, setSelected] = useState<number | null>(SESSIONS[0]?.key ?? null)
  const clips = SESSIONS.find(session => session.key === selected)?.clips ?? []
  // British English, for the word markers: two clips at a time, so the waveforms are not kept waiting.
  const transcriber = useSpeechTranscriber({locale: 'en-GB', maxConcurrent: 2})
  const player = useAudioPlayer(null, {updateInterval: 250})
  const [active, setActive] = useState<string | null>(null)
  // Read by the callbacks, so they stay the same and only the two cards that change play state re-render.
  const activeRef = useRef(active)
  activeRef.current = active

  useEffect(() => {
    setAudioModeAsync({playsInSilentMode: true})
  }, [])

  // A message that played to its end lets go, like a voice message: its waveform and words are at rest again.
  useEffect(() => {
    const subscription = player.addListener('playbackStatusUpdate', status => {
      if (status.didJustFinish) {
        setActive(null)
      }
    })
    return () => subscription.remove()
  }, [player])

  const start = useCallback(
    (clip: RadioClip, at = 0) => {
      player.replace({uri: clip.url})
      if (at > 0) {
        player.seekTo(at)
      }
      player.play()
      setActive(clip.id)
    },
    [player]
  )

  // `?play=1` (or the `capturePlay` launch argument) plays the session's second message: how the captures start
  // playback.
  const play = useLocalSearchParams<{play?: string}>().play ?? capture.play
  useEffect(() => {
    const clip = play === undefined ? undefined : clips[Number(play)]
    if (clip) {
      start(clip)
    }
  }, [clips, play, start])

  const onPlay = useCallback(
    (clip: RadioClip) => {
      if (activeRef.current !== clip.id) {
        start(clip)
      } else {
        togglePlayback(player)
      }
    },
    [player, start]
  )

  // Scrubbing another message starts it there.
  const onSeek = useCallback(
    (clip: RadioClip, time: number) => {
      if (activeRef.current === clip.id) {
        player.seekTo(time)
      } else {
        start(clip, time)
      }
    },
    [player, start]
  )

  return (
    <RnFlatList
      ListEmptyComponent={
        <Island>
          <EmptyState description="No team radio in this session." icon="radio" title="Radio silence" />
        </Island>
      }
      ListHeaderComponent={<Header onSelect={setSelected} selected={selected} />}
      bg="surface.base"
      content={{gap: 's12', pb: 's96', pt: 's8', px: 's16'}}
      contentInsetAdjustmentBehavior="automatic"
      data={clips}
      flex={1}
      keyExtractor={clip => clip.id}
      renderItem={({item}) => (
        <RadioCard
          active={item.id === active}
          clip={item}
          onPlay={onPlay}
          onSeek={onSeek}
          player={player}
          transcriber={transcriber}
        />
      )}
      testID="radio"
    />
  )
}
