import {Platform, Settings} from 'react-native'

/**
 * How the captures drive the app on iOS: launch arguments, which land in NSUserDefaults
 * (`xcrun simctl launch <device> <bundle id> -capturePlay 1 -captureRoute /styles`). The iOS 27 simulator takes no
 * scripted taps, and a link from outside asks before it opens. Android takes the `?play=` link and taps.
 */
export const capture: {hitches?: string; mock?: string; play?: string; route?: string; words?: string} =
  Platform.OS === 'ios'
    ? {
        hitches: Settings.get('captureHitches') as string | undefined,
        mock: Settings.get('captureMock') as string | undefined,
        play: Settings.get('capturePlay') as string | undefined,
        route: Settings.get('captureRoute') as string | undefined,
        words: Settings.get('captureWords') as string | undefined,
      }
    : {}
