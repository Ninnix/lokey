package org.lokey.app

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SwitchRecording {
    fun playable(file:File):File {
        if(file.extension!="mp3" || file.length()>=2048) return file
        val output=File(file.path+".wav")
        if(output.exists() && output.lastModified()>=file.lastModified()) return output
        // Android's MP3 extractor rejects recordings with fewer than four audio frames.
        // Feed these short, untagged MPEG-1 recordings directly to its standard decoder.
        val data=file.readBytes();val frames=ArrayList<ByteArray>();var at=0;var rate=0;var channels=0
        val bitrates=intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
        while(at+4<=data.size) {
            val header=ByteBuffer.wrap(data,at,4).int
            check(header ushr 21==0x7ff && (header ushr 17 and 15)==13) { "Unsupported short MP3" }
            val bitrate=bitrates.getOrNull(header ushr 12 and 15) ?: error("Invalid MP3 bitrate")
            rate=intArrayOf(44100,48000,32000).getOrNull(header ushr 10 and 3) ?: error("Invalid MP3 rate")
            channels=if(header ushr 6 and 3==3) 1 else 2
            val size=144000*bitrate/rate+(header ushr 9 and 1)
            check(size>4 && at+size<=data.size) { "Truncated MP3 frame" }
            val metadata=at+(if(header and 0x10000==0) 6 else 4)+(if(channels==1) 17 else 32)
            val tag=if(metadata+4<=at+size) String(data,metadata,4,Charsets.US_ASCII) else ""
            if(tag!="Info" && tag!="Xing") frames.add(data.copyOfRange(at,at+size))
            at+=size
        }
        check(frames.isNotEmpty())
        val codec=MediaCodec.createDecoderByType("audio/mpeg")
        val pcm=ByteArrayOutputStream()
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/mpeg",rate,channels),null,null,0)
            codec.start()
            val info=MediaCodec.BufferInfo();var sent=0;var finished=false
            val deadline=System.nanoTime()+3_000_000_000L
            while(!finished) {
                check(System.nanoTime()<deadline) { "Recording decoder timed out" }
                if(sent<=frames.size) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val frame=frames.getOrNull(sent)
                        codec.getInputBuffer(index)!!.apply { clear();if(frame!=null) put(frame) }
                        codec.queueInputBuffer(index,0,frame?.size ?: 0,sent*1152L*1000000/rate,if(frame==null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        sent++
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index>=0) {
                    val buffer=codec.getOutputBuffer(index)!!
                    buffer.position(info.offset);buffer.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size);buffer.get(bytes);pcm.write(bytes)
                    finished=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(index,false)
                } else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate=codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels=codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally { codec.release() }
        val audio=pcm.toByteArray();check(audio.isNotEmpty())
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+audio.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*2)
            .putShort((channels*2).toShort()).putShort(16).put("data".toByteArray()).putInt(audio.size)
        val partial=File(output.path+".part")
        partial.outputStream().use { it.write(header.array());it.write(audio) }
        check(partial.renameTo(output))
        return output
    }
}
