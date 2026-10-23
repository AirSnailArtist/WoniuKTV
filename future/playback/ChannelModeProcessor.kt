package com.pocketktv.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Keeps left/right KTV accompaniment audible on both speakers; never invents a missing stem. */
@UnstableApi
class ChannelModeProcessor: BaseAudioProcessor() {
    @Volatile var mode: String = "stereo"
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if(inputAudioFormat.channelCount==2 && inputAudioFormat.encoding==C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output=replaceOutputBuffer(inputBuffer.remaining())
        val selected=mode
        while(inputBuffer.remaining()>=4) {
            val left=inputBuffer.short;val right=inputBuffer.short
            when(selected) {
                "left" -> { output.putShort(left);output.putShort(left) }
                "right" -> { output.putShort(right);output.putShort(right) }
                else -> { output.putShort(left);output.putShort(right) }
            }
        }
        output.flip()
    }
}
