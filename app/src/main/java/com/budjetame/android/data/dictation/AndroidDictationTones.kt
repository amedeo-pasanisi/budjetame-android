package com.budjetame.android.data.dictation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The two tones that frame a Dictation, as the web's `dictationTones.ts`
 * makes them: a rising chirp (660 Hz then 880 Hz) when the microphone is
 * really listening, a falling one (880 then 660) at its end — two quick
 * sine notes of 70 ms, each faded in over 10 ms and out exponentially so
 * neither clicks. Synthesised, so no audio file ships; played as
 * sonification, at the device volume, with no setting to mute them. A
 * device that will not play them changes nothing: the Dictation goes on in
 * silence. The sound itself is judged by ear.
 */
class AndroidDictationTones : DictationTones {

    override fun playStart() = chirp(660.0, 880.0)

    override fun playEnd() = chirp(880.0, 660.0)

    private fun chirp(fromHz: Double, toHz: Double) {
        try {
            val samples = note(fromHz) + note(toHz)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.notificationMarkerPosition = samples.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(played: AudioTrack) = played.release()
                override fun onPeriodicNotification(played: AudioTrack) {}
            })
            track.play()
        } catch (_: Exception) {
            // A tone is a courtesy: a device that will not play it changes nothing.
        }
    }

    /** One sine note: a linear fade-in to the peak, then an exponential
     * fade to near silence by its end. */
    private fun note(frequency: Double): ShortArray {
        val count = (SAMPLE_RATE * NOTE_SECONDS).toInt()
        val fadeIn = (SAMPLE_RATE * FADE_IN_SECONDS).toInt()
        return ShortArray(count) { i ->
            val gain = if (i < fadeIn) {
                PEAK_GAIN * i / fadeIn
            } else {
                val progress = (i - fadeIn).toDouble() / (count - fadeIn)
                PEAK_GAIN * (SILENT_GAIN / PEAK_GAIN).pow(progress)
            }
            (sin(2 * PI * frequency * i / SAMPLE_RATE) * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val NOTE_SECONDS = 0.07
        const val FADE_IN_SECONDS = 0.01
        const val PEAK_GAIN = 0.15
        /** Where each note fades out to: an exponential fade cannot reach zero. */
        const val SILENT_GAIN = 0.0001
    }
}
