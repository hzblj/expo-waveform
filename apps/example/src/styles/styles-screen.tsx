import {useSpeechTranscriber, useTranscribe} from '@hzblj/expo-speech'
import {
  useWaveformPlayback,
  type WaveformAlignment,
  type WaveformMarkerStyle,
  type WaveformScale,
  type WaveformVariant,
  WaveformView,
} from '@hzblj/expo-waveform'
import {
  Box,
  Island,
  Pressable,
  RnScrollView,
  RnText,
  SegmentedControl,
  Slider,
  Switch,
  useAppTheme,
  VStack,
} from '@hzblj/kara-ui'
import {setAudioModeAsync, useAudioPlayer} from 'expo-audio'
import {useEffect, useMemo, useState} from 'react'
import {type ColorValue, Platform, PlatformColor} from 'react-native'

import {FIRST_CLIP} from '../f1/radio'
import {Caption, formatDuration, NativeCaption} from '../ui/format'
import {Section} from '../ui/page'
import {PlayButton} from '../ui/play-button'
import {togglePlayback} from '../ui/toggle'

const VARIANTS: WaveformVariant[] = ['bars', 'area', 'line']
const ALIGNMENTS: WaveformAlignment[] = ['center', 'bottom']
const SCALES: WaveformScale[] = ['logarithmic', 'linear']
const MARKER_STYLES: WaveformMarkerStyle[] = ['none', 'dot', 'line']

/** Teams' colours for the played part, and the platform's own accent. */
const PALETTE = ['accent', '#e10600', '#ff8000', '#27f4d2', '#3671c6', '#229971', '#ffffff'] as const

/**
 * What the view plays in without a `progressColor` on Android: the system accent as the dark theme resolves it (the
 * theme's accent before Android 12). On iOS it is the view's `tintColor`, which Kara sets to the theme's accent.
 */
const ANDROID_ACCENT: ColorValue = PlatformColor('@android:color/system_accent1_200', '?android:attr/colorAccent')

const round = (value: number) => Math.round(value * 2) / 2

/** A prop as it would be written, when it is not the default. */
const prop = (name: string, value: unknown, fallback: unknown) =>
  value === fallback ? null : typeof value === 'string' ? `${name}="${value}"` : `${name}={${String(value)}}`

/**
 * Every prop that shapes the waveform, on one radio message: the variant, the bars, the scale, the colours, the cursor,
 * the markers. The code for what you see is under it.
 */
export const StylesScreen = () => {
  const theme = useAppTheme()
  const clip = FIRST_CLIP
  const transcriber = useSpeechTranscriber({locale: 'en-GB'})
  const {transcript} = useTranscribe(transcriber, clip?.url, {cacheKey: clip?.url})
  const player = useAudioPlayer(clip ? {uri: clip.url} : null, {updateInterval: 250})
  const playback = useWaveformPlayback(player)

  const [variant, setVariant] = useState(0)
  const [alignment, setAlignment] = useState(0)
  const [scale, setScale] = useState(0)
  const [barWidth, setBarWidth] = useState(3)
  const [barGap, setBarGap] = useState(2)
  const [minBarHeight, setMinBarHeight] = useState(2)
  const [noiseFloor, setNoiseFloor] = useState(-50)
  const [height, setHeight] = useState(96)
  const [normalize, setNormalize] = useState(true)
  const [cursor, setCursor] = useState(false)
  const [markers, setMarkers] = useState(true)
  const [markerStyle, setMarkerStyle] = useState(0)
  const [haptics, setHaptics] = useState(true)
  const [palette, setPalette] = useState(0)

  useEffect(() => {
    setAudioModeAsync({playsInSilentMode: true})
  }, [])

  const chosen = PALETTE[palette] ?? 'accent'
  const accent = Platform.OS === 'ios' ? theme.color('content.accent') : ANDROID_ACCENT
  const progressColor = chosen === 'accent' ? undefined : chosen
  const wordStarts = useMemo(() => transcript.words.map(word => word.start), [transcript.words])

  const code = [
    'source={clip.url}',
    'playback={useWaveformPlayback(player)}',
    prop('variant', VARIANTS[variant], 'bars'),
    prop('alignment', ALIGNMENTS[alignment], 'center'),
    prop('scale', SCALES[scale], 'logarithmic'),
    prop('barWidth', barWidth, 3),
    prop('barGap', barGap, 2),
    prop('minBarHeight', minBarHeight, 2),
    prop('noiseFloor', noiseFloor, -50),
    prop('normalize', normalize, true),
    prop('cursor', cursor, false),
    prop('haptics', haptics, true),
    progressColor ? `progressColor="${progressColor}"` : null,
    markers ? 'markers={words.map(word => word.start)}' : null,
    markers ? prop('markerStyle', MARKER_STYLES[markerStyle], 'none') : null,
    'onSeek={time => player.seekTo(time)}',
  ].filter(Boolean)

  return (
    <RnScrollView
      bg="surface.base"
      content={{gap: 's32', pb: 's96', pt: 's8', px: 's20'}}
      contentInsetAdjustmentBehavior="automatic"
      flex={1}
      testID="styles"
    >
      <Box bg="surface.primary" borderRadius="large" gap="s12" p="s16">
        <Caption>
          {clip
            ? `${clip.driver?.name_acronym ?? 'Radio'} · ${formatDuration(playback?.duration)}`
            : 'Loading the radio'}
        </Caption>
        <WaveformView
          alignment={ALIGNMENTS[alignment]}
          barGap={barGap}
          barWidth={barWidth}
          cursor={cursor}
          haptics={haptics}
          markerStyle={MARKER_STYLES[markerStyle]}
          markers={markers ? wordStarts : null}
          minBarHeight={minBarHeight}
          noiseFloor={noiseFloor}
          normalize={normalize}
          onSeek={time => player.seekTo(time)}
          playback={playback}
          progressColor={progressColor}
          scale={SCALES[scale]}
          source={clip?.url}
          style={{height}}
          testID="preview"
          variant={VARIANTS[variant]}
        />
        <Box align="center" gap="s12" row>
          <PlayButton
            color={progressColor ?? accent}
            label={playback?.playing ? 'Pause' : 'Play'}
            onPress={() => togglePlayback(player)}
            playing={playback?.playing ?? false}
          />
          <RnText color="content.secondary" fill numberOfLines={2} variant="footnote">
            {clip?.driver
              ? `${clip.driver.first_name} ${clip.driver.last_name}, ${clip.driver.team_name ?? 'team radio'}`
              : ''}
          </RnText>
        </Box>
      </Box>

      <Section title="Variant">
        <SegmentedControl onChange={setVariant} selectedIndex={variant} values={['Bars', 'Area', 'Line']} />
        <SegmentedControl onChange={setAlignment} selectedIndex={alignment} values={['Center', 'Bottom']} />
      </Section>

      <Section
        caption="Logarithmic shows speech the way it is heard; linear shows the signal, where quiet words nearly vanish."
        title="Loudness"
      >
        <SegmentedControl onChange={setScale} selectedIndex={scale} values={['Logarithmic', 'Linear']} />
        <Island>
          <VStack bg="surface.primary" borderRadius="large" p="s16" spacing="s12" wide>
            <NativeCaption>{`Noise floor ${noiseFloor} dB`}</NativeCaption>
            <Slider
              max={-20}
              min={-80}
              onValueChange={value => setNoiseFloor(Math.round(value))}
              step={5}
              value={noiseFloor}
            />
            <Switch label="Normalize" onValueChange={setNormalize} value={normalize} />
          </VStack>
        </Island>
      </Section>

      <Section title="Bars">
        <Island>
          <VStack bg="surface.primary" borderRadius="large" p="s16" spacing="s12" wide>
            <NativeCaption>{`Width ${barWidth}`}</NativeCaption>
            <Slider max={8} min={1} onValueChange={value => setBarWidth(round(value))} step={0.5} value={barWidth} />
            <NativeCaption>{`Gap ${barGap}`}</NativeCaption>
            <Slider max={6} min={0} onValueChange={value => setBarGap(round(value))} step={0.5} value={barGap} />
            <NativeCaption>{`Shortest ${minBarHeight}`}</NativeCaption>
            <Slider
              max={8}
              min={0}
              onValueChange={value => setMinBarHeight(round(value))}
              step={0.5}
              value={minBarHeight}
            />
            <NativeCaption>{`Height ${height}`}</NativeCaption>
            <Slider max={160} min={24} onValueChange={value => setHeight(Math.round(value))} step={8} value={height} />
          </VStack>
        </Island>
      </Section>

      <Section
        caption="Markers are the words expo-speech found: a drag ticks as it crosses one, drawn or not."
        title="Playhead"
      >
        <SegmentedControl onChange={setMarkerStyle} selectedIndex={markerStyle} values={['Hidden', 'Dots', 'Lines']} />
        <Island>
          <VStack bg="surface.primary" borderRadius="large" p="s16" spacing="s12" wide>
            <Switch label="Cursor" onValueChange={setCursor} value={cursor} />
            <Switch label="Word markers" onValueChange={setMarkers} value={markers} />
            <Switch label="Haptics" onValueChange={setHaptics} value={haptics} />
          </VStack>
        </Island>
      </Section>

      <Section
        caption="The first is the default: the tint colour on iOS, Material You's accent on Android."
        title="Played"
      >
        <Box gap="s12" row wrap>
          {PALETTE.map((color, index) => (
            <Pressable
              accessibilityLabel={`${color === 'accent' ? 'The accent' : color}${index === palette ? ', chosen' : ''}`}
              accessibilityRole="button"
              borderRadius="full"
              key={color}
              onPress={() => setPalette(index)}
            >
              <Box
                borderRadius="full"
                size={36}
                style={{
                  backgroundColor: color === 'accent' ? accent : color,
                  borderColor: theme.color(index === palette ? 'content.primary' : 'surface.base'),
                  borderWidth: 3,
                }}
              />
            </Pressable>
          ))}
        </Box>
      </Section>

      <Section title="Code">
        <Box bg="surface.primary" borderRadius="large" p="s16">
          <RnText monospaced selectable variant="footnote">
            {`<WaveformView\n${code.map(line => `  ${line}`).join('\n')}\n/>`}
          </RnText>
        </Box>
      </Section>
    </RnScrollView>
  )
}
