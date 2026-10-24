import type {ExpoConfig} from 'expo/config'

// Not "Expo Waveform": the generated Xcode target would be `ExpoWaveform`, the Swift module of the package's native
// module.
const NAME = 'Waveform Example'
const BUNDLE_ID = 'com.hzblj.expowaveform.example'
// The middle of the iOS icon's red: what the Android icon's waveform sits on.
const ICON_BACKGROUND = '#d1150d'

export default {
  android: {
    adaptiveIcon: {
      backgroundColor: ICON_BACKGROUND,
      foregroundImage: './assets/adaptive-icon.png',
    },
    package: BUNDLE_ID,
  },
  experiments: {
    typedRoutes: true,
  },
  icon: './assets/icon.png',
  ios: {
    bundleIdentifier: BUNDLE_ID,
    supportsTablet: true,
  },
  name: NAME,
  orientation: 'portrait',
  plugins: [
    'expo-router',
    // iOS 27's SDK requires the UIScene life cycle, which Expo 57's template lacks: React Native starts in Expo's scene
    // delegate instead.
    ['expo-build-properties', {ios: {enableSceneSupport: true}}],
    // The example's icons (Material Symbols): every SVG in assets/icons becomes a native asset on both platforms, and
    // its name a type in native-icons.d.ts.
    ['@hzblj/kara-ui', {icons: {source: './assets/icons'}}],
    // Plays the team radio and records the pit wall tab. expo-speech's microphone text would win over it, so both
    // get the same one.
    [
      'expo-audio',
      {
        enableBackgroundPlayback: false,
        microphonePermission: 'Allow $(PRODUCT_NAME) to hear you, to draw your voice as a waveform.',
      },
    ],
    // The radio's words, as markers on its waveform.
    [
      '@hzblj/expo-speech',
      {microphonePermission: 'Allow $(PRODUCT_NAME) to hear you, to draw your voice as a waveform.'},
    ],
  ],
  scheme: 'waveform-example',
  slug: 'expo-waveform-example',
  userInterfaceStyle: 'dark',
  version: '1.0.0',
} satisfies ExpoConfig
