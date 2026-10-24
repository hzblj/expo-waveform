import type {Transcript} from '@hzblj/expo-speech'
import {type ColorToken, Island, Text, type TextVariant} from '@hzblj/kara-ui'
import {useMemo} from 'react'

type KaraokeProps = {
  transcript: Pick<Transcript, 'text' | 'segments'>
  /** The word being said: words before it are spoken, words after it to come. -1 before the first. */
  wordIndex: number
  /** Whether the words are being followed: off, every word reads plainly. */
  following: boolean
  /** The word being said. */
  activeColor: ColorToken
  variant?: TextVariant
}

/** A run of the transcript: a word and what follows it up to the next, or the text before the first word. */
type Piece = {index: number; text: string; volatile: boolean}

const piecesOf = (transcript: KaraokeProps['transcript']): Piece[] => {
  const pieces: Piece[] = []
  let cursor = 0
  let index = 0

  for (const segment of transcript.segments) {
    for (const word of segment.words) {
      if (word.range[0] > cursor) {
        const gap = transcript.text.slice(cursor, word.range[0])
        const previous = pieces[pieces.length - 1]
        if (previous) {
          previous.text += gap
        } else {
          pieces.push({index: -1, text: gap, volatile: !segment.isFinal})
        }
      }
      pieces.push({index, text: transcript.text.slice(word.range[0], word.range[1]), volatile: !segment.isFinal})
      cursor = word.range[1]
      index += 1
    }
  }

  const rest = transcript.text.slice(cursor)
  const last = pieces[pieces.length - 1]
  if (rest && last) {
    last.text += rest
  }
  return pieces
}

/**
 * A transcript lit word by word as it plays, natively: one kara `Text` with a span per word, and `animate`, so each
 * word eases from to come, to being said, to said, instead of switching.
 */
export const Karaoke = ({activeColor, following, transcript, variant = 'subheadline', wordIndex}: KaraokeProps) => {
  const pieces = useMemo(() => piecesOf(transcript), [transcript])

  const colorOf = (piece: Piece): ColorToken => {
    if (!following || piece.volatile) {
      return piece.volatile ? 'content.tertiary' : 'content.secondary'
    }
    if (piece.index < wordIndex) {
      return 'content.primary'
    }
    return piece.index === wordIndex ? activeColor : 'content.tertiary'
  }

  return (
    <Island>
      <Text align="left" animate color="content.secondary" variant={variant} wide>
        {pieces.map(piece => (
          <Text color={colorOf(piece)} key={`${piece.index}:${piece.text}`}>
            {piece.text}
          </Text>
        ))}
      </Text>
    </Island>
  )
}
