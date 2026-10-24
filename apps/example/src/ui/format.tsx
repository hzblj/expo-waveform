import {type ColorToken, RnText, Text} from '@hzblj/kara-ui'

/** `0:19`. */
export const formatDuration = (seconds: number | null | undefined) => {
  if (seconds == null || !Number.isFinite(seconds)) {
    return '0:00'
  }
  const whole = Math.max(0, Math.round(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

export const Caption = ({children, color = 'content.secondary'}: {children: string; color?: ColorToken}) => (
  <RnText color={color} style={{letterSpacing: 0.6}} variant="captionMedium">
    {children.toUpperCase()}
  </RnText>
)

/** `Caption` inside a native stack (an `Island`), where a React Native text has no size of its own. */
export const NativeCaption = ({children, color = 'content.secondary'}: {children: string; color?: ColorToken}) => (
  <Text align="left" color={color} transform="uppercase" variant="captionMedium" wide>
    {children}
  </Text>
)
