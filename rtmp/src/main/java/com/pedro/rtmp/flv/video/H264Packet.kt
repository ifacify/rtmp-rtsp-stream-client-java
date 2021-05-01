package com.pedro.rtmp.flv.video

import android.media.MediaCodec
import android.util.Log
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import java.nio.ByteBuffer
import kotlin.experimental.and

/**
 * Created by pedro on 8/04/21.
 *
 * ISO 14496-15
 */
class H264Packet(private val videoPacketCallback: VideoPacketCallback) {

  private val TAG = "H264Packet"

  private val header = ByteArray(5)
  private val naluSize = 4
  //first time we need send video config
  private var configSend = false

  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  var profileIop = ProfileIop.BASELINE

  enum class Type(val value: Byte) {
    SEQUENCE(0x00), NALU(0x01), EO_SEQ(0x02)
  }

  fun sendVideoInfo(sps: ByteBuffer, pps: ByteBuffer) {
    val mSps = removeH264StartCode(sps)
    val mPps = removeH264StartCode(pps)
    this.sps = ByteArray(mSps.remaining())
    this.pps = ByteArray(mPps.remaining())
    mSps.get(this.sps!!, 0, this.sps!!.size)
    mPps.get(this.pps!!, 0, this.pps!!.size)
  }

  fun createFlvAudioPacket(byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    //header is 5 bytes length:
    //4 bits FrameType, 4 bits CodecID
    //1 byte AVCPacketType
    //3 bytes CompositionTime, the cts.
    val cts = 0
    header[2] = (cts shr 16).toByte()
    header[3] = (cts shr 8).toByte()
    header[4] = cts.toByte()

    val buffer: ByteArray
    if (!configSend) {
      header[0] = ((VideoNalType.IDR.value shl 4) or VideoFormat.AVC.value).toByte()
      header[1] = Type.SEQUENCE.value

      if (sps != null && pps != null) {
        val config = VideoSpecificConfig(sps!!, pps!!, profileIop)
        buffer = ByteArray(config.size + header.size)
        config.write(buffer, header.size)
      } else {
        Log.e(TAG, "waiting for a valid sps and pps")
        return
      }
      configSend = true
    } else {
      val validBuffer = removeH264StartCode(byteBuffer)
      val size = validBuffer.remaining()
      buffer = ByteArray(header.size + size + naluSize)

      val type: Int = (validBuffer.get(0) and 0x1F).toInt()
      var nalType = VideoDataType.INTER_FRAME.value
      if (type == VideoNalType.IDR.value || info.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
        nalType = VideoDataType.KEYFRAME.value
      }
      header[0] = ((nalType shl 4) or VideoFormat.AVC.value).toByte()
      header[1] = Type.NALU.value
      writeNaluSize(buffer, header.size, size)
      validBuffer.get(buffer, header.size + naluSize, size)
    }
    System.arraycopy(header, 0, buffer, 0, header.size)
    val ts = info.presentationTimeUs / 1000
    videoPacketCallback.onVideoFrameCreated(FlvPacket(buffer, ts, buffer.size, FlvType.VIDEO))
  }

  //naluSize = UInt32
  private fun writeNaluSize(buffer: ByteArray, offset: Int, size: Int) {
    buffer[offset] = (size ushr 24).toByte()
    buffer[offset + 1] = (size ushr 16).toByte()
    buffer[offset + 2] = (size ushr 8).toByte()
    buffer[offset + 3] = size.toByte()
  }

  private fun removeH264StartCode(byteBuffer: ByteBuffer): ByteBuffer {
    var startCodeSize = 0
    if (byteBuffer.get(0).toInt() == 0x00 && byteBuffer.get(1).toInt() == 0x00
        && byteBuffer.get(2).toInt() == 0x00 && byteBuffer.get(3).toInt() == 0x01) {
      //match 00 00 00 01
      startCodeSize = 4
    } else if (byteBuffer.get(0).toInt() == 0x00 && byteBuffer.get(1).toInt() == 0x00
        && byteBuffer.get(2).toInt() == 0x01) {
      //match 00 00 01
      startCodeSize = 3
    }
    byteBuffer.position(startCodeSize)
    return byteBuffer.slice()
  }

  fun reset() {
    configSend = false
  }
}