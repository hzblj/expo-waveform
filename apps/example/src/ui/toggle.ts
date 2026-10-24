import type {AudioPlayer} from 'expo-audio'

/** Play or pause; a player that has played to the end starts again from the beginning. */
export const togglePlayback = (player: AudioPlayer) => {
  if (player.playing) {
    player.pause()
    return
  }
  if (player.duration > 0 && player.currentTime >= player.duration - 0.1) {
    player.seekTo(0)
  }
  player.play()
}
