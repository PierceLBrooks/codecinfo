package com.parseus.codecinfo.data.codecinfo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.Surface
import androidx.preference.PreferenceManager
import com.homesoft.encoder.FrameMuxer
import com.homesoft.encoder.Mp4FrameMuxer
import com.homesoft.encoder.Muxer
import com.homesoft.encoder.MuxerConfig
import com.homesoft.encoder.MuxingCompletionListener
import com.homesoft.encoder.WebmFrameMuxer
import com.parseus.codecinfo.BuildConfig
import com.parseus.codecinfo.R
import com.parseus.codecinfo.data.DetailsProperty
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jcodec.codecs.h264.io.model.NALUnitType
import org.jcodec.codecs.h264.io.model.SEI
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

data class CodecSimpleInfo(val id: Long,
                           val codecId: String,
                           val codecName: String,
                           val isAudio: Boolean,
                           val isEncoder: Boolean,
                           val isHardwareAccelerated: Boolean,
                           var isProblematic: Boolean = false,
                           var isTested: Boolean = false,
                           var isTesting: Boolean = false,
                           var tester: Context? = null,
                           var decoder: Decoder? = null,
                           var surface: Surface? = null,
                           var texture: SurfaceTexture? = null) : MuxingCompletionListener, Decoder.Listener  {

    override fun toString(): String {
        return "$codecId ($codecName)"
    }

    override fun decoderDidError(sender: Decoder) {
        Log.e("CodecSimpleInfo", "decoderDidError")
    }

    override fun decoderDidChangeFormat(sender: Decoder, width: Int, height: Int) {
        Log.e("CodecSimpleInfo", "decoderDidChangeFormat: $width x $height")
    }

    override fun onVideoSuccessful(file: File) {
        Log.v("CodecSimpleInfo", "onVideoSuccessful")
        if (isTesting) {
            isTested = true
            isTesting = false
            isProblematic = false
            resolveWhetherProblematic()
        }
    }

    override fun onVideoError(error: Throwable) {
        Log.e("CodecSimpleInfo", "onVideoError", error)
        if (isTesting) {
            isTested = true
            isTesting = false
            isProblematic = true
            resolveWhetherProblematic()
        }
    }

    private fun getTestPath(context: Context): String {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            return Environment.getExternalStorageDirectory().absolutePath
        }
        return context.applicationInfo.dataDir
    }

    private fun readByte(bytes: ByteArray, i: Int): Int {
        var b = bytes[i].toInt()
        if (b < 0) {
            b += 128
        }
        return b
    }

    private fun findNextAnnexB(bytes: ByteArray, currentStart: Int): Int {
        var nextStart = currentStart

        while (nextStart < bytes.size - 4) {
            if ((bytes[nextStart].toInt() == 0x00) && (bytes[nextStart + 1].toInt() == 0x00) && (bytes[nextStart + 2].toInt() == 0x00) && (bytes[nextStart + 3].toInt() == 0x01)) {
                return nextStart
            }
            nextStart++
        }

        return bytes.size
    }

    private fun convertFromAvccToAnnexB(bytes: ByteArray?): ByteArray? {
        if (bytes == null || (bytes.size > 4 && bytes[0].toInt() == 0x00 && bytes[1].toInt() == 0x00 && bytes[2].toInt() == 0x00 && bytes[3].toInt() == 0x01)) {
            if (bytes == null) {
                return null
            }
            return bytes.clone()
        }

        var offset = 6
        var spsLength = readByte(bytes, offset) shl 8
        spsLength += readByte(bytes, offset + 1)
        val spsData = bytes.copyOfRange(offset + 2, bytes.size - (offset - 2))

        offset = 8 + spsLength + 1
        var ppsLength = readByte(bytes, offset) shl 8
        ppsLength += readByte(bytes, offset + 1).toInt()
        val ppsData = bytes.copyOfRange(offset + 2, bytes.size - (offset + 2))

        val length = 4 + spsLength + 4 + ppsLength
        val data = ByteArray(length)

        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x00
        data[3] = 0x01
        for (i in 0 until spsLength) {
            data[4 + i] = spsData[i]
        }

        offset = 4 + spsLength
        data[offset + 0] = 0x00
        data[offset + 1] = 0x00
        data[offset + 2] = 0x00
        data[offset + 3] = 0x01
        offset += 4
        for (i in 0 until ppsLength) {
            data[offset + i] = ppsData[i]
        }

        return data
    }

    private fun convertToAvccFromAnnexB(bytes: ByteArray): ByteArray? {
        var firstStart = 0
        var nextStart = 0

        var sps = ByteArray(0)
        var spsSize = 0

        var pps = ByteArray(0)
        var ppsSize = 0

        var start = ByteArray(0)
        var size = 0

        var type = 0

        var data = ByteArray(0)
        var dataSize = 0

        if (bytes.size < 4) {
            return null
        }

        if (bytes[0].toInt() != 0x00 || bytes[1].toInt() != 0x00 || bytes[2].toInt() != 0x00 || bytes[3].toInt() != 0x01) {
            return ByteArray(bytes.size)
        }

        firstStart = findNextAnnexB(bytes, 0)

        if (firstStart == bytes.size) {
            return null
        }

        nextStart = firstStart + 1
        while (nextStart < bytes.size) {
            nextStart = findNextAnnexB(bytes, nextStart)

            size = (nextStart - firstStart) - 4
            if (size + firstStart + 4 > bytes.size) {
                return null
            }
            start = bytes.copyOfRange(firstStart + 4, size + firstStart + 4)

            type = start[0].toInt() and 0x1F

            if (type == 7) {
                sps = start.clone()
                spsSize = sps.size
            } else if (type == 8) {
                pps = start.clone()
                ppsSize = pps.size
            }

            firstStart = nextStart
            nextStart += 1
        }

        if (spsSize == 0 || ppsSize == 0) {
            return null
        }

        dataSize = 5 + 1 + 2 + spsSize + 1 + 2 + ppsSize
        data = ByteArray(dataSize)

        data[0] = 1
        data[1] = sps[1]
        data[2] = sps[2]
        data[3] = sps[3]
        data[4] = 0xFF.toByte()
        data[5] = 0xE1.toByte()
        data[6] = (spsSize shr 8).toByte()
        data[7] = (spsSize and 0xFF).toByte()
        for (i in 0 until spsSize) {
            if (8 + i >= data.size) {
                return null
            }
            data[8 + i] = sps[i]
        }
        data[8 + spsSize] = 1
        data[9 + spsSize] = (ppsSize shr 8).toByte()
        data[10 + spsSize] = (ppsSize and 0xFF).toByte()
        for (i in 0 until ppsSize) {
            if (11 + spsSize + i >= data.size) {
                return null
            }
            data[11 + spsSize + i] = pps[i]
        }

        return data
    }

    private fun convertToAnnexB(bytes: ByteArray): ByteArray? {
        var clone = bytes.clone()
        var nalThen = bytes.clone()
        var nalNow = bytes.clone()
        val length: Long = bytes.size.toLong()
        var position: Long = 0
        var nalLength: Long = 0
        var remainingLength: Long = length

        if (length > 4 && bytes[0].toInt() == 0x00 && bytes[1].toInt() == 0x00 && bytes[2].toInt() == 0x00 && bytes[3].toInt() == 0x01) {
            return clone
        }

        while (remainingLength > 0) {
            nalLength  = readByte(bytes, (position + 0).toInt()).toLong() shl 24
            nalLength += readByte(bytes, (position + 1).toInt()).toLong() shl 16
            nalLength += readByte(bytes, (position + 2).toInt()).toLong() shl 8
            nalLength += readByte(bytes, (position + 3).toInt()).toLong()// shl 0

            if (nalLength !in 0..length) {
                Log.e("CodecSimpleInfo", "Oh no! Bad NAL length. Remaining: $remainingLength Current: $nalLength, Length: $length")
                return null
            }

            val type = readByte(bytes, (position + 4).toInt()) and 0x1F
            if (BuildConfig.DEBUG) {
                Log.w("CodecSimpleInfo", "NAL = " + NALUnitType.fromValue(type))
                nalThen = bytes.copyOfRange(position.toInt(), bytes.size - position.toInt())
                nalNow = bytes.copyOfRange(position.toInt() + (nalLength + 4).toInt(), bytes.size - (nalLength + 4 + position).toInt())
            }

            if (nalLength.toInt() == 1) {
                Log.w("CodecSimpleInfo", "1 length NAL!")
            }

            if (BuildConfig.DEBUG && type == 6) {
                try {
                    val sei = SEI.read(ByteBuffer.wrap(nalThen.copyOfRange(5, nalLength.toInt() + 5)))
                    val messages = sei.messages
                    for (i in 0 until messages.size) {
                        val message = messages[i]
                        val payload = message.payloadType
                        val size = message.payloadSize
                        Log.w("CodecSimpleInfo", "SEI @ $i = $payload / $size")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            clone[(position + 0).toInt()] = 0x00
            clone[(position + 1).toInt()] = 0x00
            clone[(position + 2).toInt()] = 0x00
            clone[(position + 3).toInt()] = 0x01

            position += 4 + nalLength
            remainingLength -= 4 + nalLength
        }

        return clone
    }

    private suspend fun demuxVideo(context: Context, codec: String) {
        if (Build.VERSION.SDK_INT < 26) {
            isTested = true
            isTesting = false
            resolveWhetherProblematic()
            return
        }
        texture = SurfaceTexture(true)
        surface = Surface(texture)
        var problems = 0
        var frameDecodes = 0
        var frameTotals = 0
        var extraDataThen = ByteArray(0)
        val combinedCodecName = "$codecId/$codecName"
        val manifests = JSONObject(context.assets.open("codecs/codecs.py.json").bufferedReader().readText()).getJSONArray(codec)
        for (i in 0 until manifests.length()) {
            try {
                val manifest = context.assets.open("codecs/" + manifests.getString(i))
                val ivf = IVF(manifest).readAll()
                decoder = null
                Log.v("CodecSimpleInfo", ivf.size.toString())
                Log.v("CodecSimpleInfo", ivf.first().codec)
                Log.v("CodecSimpleInfo", ""+ivf.first().width+"x"+ivf.first().height)
                for (j in 0 until ivf.size) {
                    val frame = ivf[j]
                    frameTotals += 1
                    if (codec == "h264" || codec == "hevc") {
                        frame.payload = convertToAnnexB(frame.payload)
                    }
                    if (decoder == null) {
                        if (codec == "h264") {
                            decoder = Decoder(surface, Decoder.Codec.H264, codecName, frame.width.toInt(), frame.height.toInt())
                        } else if (codec == "hevc") {
                            decoder = Decoder(surface, Decoder.Codec.HEVC, codecName, frame.width.toInt(), frame.height.toInt())
                        } else if (codec == "av1") {
                            decoder = Decoder(surface, Decoder.Codec.AV1, codecName, frame.width.toInt(), frame.height.toInt())
                        } else if (codec == "vp8") {
                            decoder = Decoder(surface, Decoder.Codec.VP8, codecName, frame.width.toInt(), frame.height.toInt())
                        } else if (codec == "vp9") {
                            decoder = Decoder(surface, Decoder.Codec.VP9, codecName, frame.width.toInt(), frame.height.toInt())
                        } else {
                            continue
                        }
                        decoder!!.setListener(this)
                    }
                    if (codec == "h264" || codec == "hevc") {
                        if (codec == "h264") {
                            val extraDataNow = convertFromAvccToAnnexB(convertToAvccFromAnnexB(frame.payload))
                            if (extraDataNow != null && (extraDataThen.isEmpty() || extraDataThen.size != extraDataNow.size || !extraDataThen.contentEquals(extraDataNow))) {
                                extraDataThen = extraDataNow.clone()
                                if (!decoder!!.rebuildDecoder(extraDataNow, extraDataNow.size)) {
                                    Log.e("CodecSimpleInfo", "rebuildDecoder @ $combinedCodecName = $i / $j / " + manifests.getString(i))
                                    problems += 1
                                    break
                                }
                            }
                        }
                    }
                    if (!decoder!!.addEncodedData(frame.payload, frame.payload.size, extraDataThen, extraDataThen.size, frame.timestamp)) {
                        Log.e("CodecSimpleInfo", "addEncodedData @ $combinedCodecName = $i / $j / " + manifests.getString(i))
                        problems += 1
                        break
                    }
                    frameDecodes += 1
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace()
                }
                problems += 1
            }
        }
        if (problems > 0) {
            Log.e("CodecSimpleInfo", "Problems @ $combinedCodecName = $problems")
            isProblematic = true
        } else {
            Log.d("CodecSimpleInfo", "Frames @ $combinedCodecName = $frameDecodes / $frameTotals")
            isProblematic = false
        }
        isTested = true
        isTesting = false
        resolveWhetherProblematic()
    }

    private suspend fun muxVideo(context: Context, codec: String) {
        val path = getTestPath(context)
        val bitmap = BitmapFactory.decodeResource(context.resources, R.raw.im1)
        val file = File(path + "/test_" + codecId.replace("/", "_") + "_" + codecName.replace("/", "_") + ".mp4")
        val fps = 1.0f
        var mux: FrameMuxer? = null
        if (codec == "h264" || codec == "hevc") {
            mux = Mp4FrameMuxer(file.absolutePath, fps)
        } else {
            mux = WebmFrameMuxer(file.absolutePath, fps)
        }
        val config = MuxerConfig(
            file,
            0,
            0,
            codecId,
            3,
            fps,
            1500000,
            mux,
            10
        )
        val muxer = Muxer(context, config)
        Log.v("CodecSimpleInfo", path)
        Log.v("CodecSimpleInfo", file.absolutePath)
        muxer.setOnMuxingCompletedListener(this)
        muxer.muxAsync(mutableListOf<Bitmap>(bitmap), null)
    }

    fun testWhetherProblematic(context: Context): Boolean {
        val combinedCodecName = "$codecId/$codecName"
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var pref = ""
        var codec: String? = null
        var isVideo = false
        if (isTesting) {
            return true
        }
        if (isTested) {
            return false
        }
        tester = context
        isTesting = true
        pref += prefs.getString(combinedCodecName, "")
        if (pref.isNotEmpty()) {
            isProblematic = pref.toBoolean()
            Log.v("CodecSimpleInfo", "isProblematic @ $combinedCodecName = $isProblematic")
        }
        if (codecId == "video/avc") { // H264
            isVideo = true
            codec = "h264"
        } else if (codecId == "video/hevc") { // H265/HEVC
            isVideo = true
            codec = "hevc"
        } else if (codecId == "video/av01") { // AV1
            isVideo = true
            codec = "av1"
        } else if (codecId == "video/x-vnd.on2.vp8") { // VP8
            isVideo = true
            codec = "vp8"
        } else if (codecId == "video/x-vnd.on2.vp9") { // VP9
            isVideo = true
            codec = "vp9"
        }
        if (isVideo) {
            if (isEncoder) {
                runBlocking {
                    launch {
                        muxVideo(context, codec!!)
                    }
                }
            } else {
                runBlocking {
                    launch {
                        demuxVideo(context, codec!!)
                    }
                }
            }
        } else {
            isTesting = false
        }
        if (!isTesting) {
            isTested = true
            resolveWhetherProblematic()
        }
        return true
    }

    fun resolveWhetherProblematic() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(tester!!).edit()
        if (isTested) {
            val combinedCodecName = "$codecId/$codecName"
            Log.v("CodecSimpleInfo", "resolveWhetherProblematic @ $combinedCodecName")
            prefs.putString(combinedCodecName, isProblematic.toString())
        }
        prefs.apply()
        try {
            if (surface != null) {
                surface!!.release()
                surface = null
            }
            if (texture != null) {
                texture!!.release()
                texture = null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace()
            }
        }
    }

    fun saveToLogcat(context: Context) {
        saveToLogcat(context, codecId, codecName, arrayListOf<DetailsProperty>()/*getDetailedCodecInfo(context, codecId, codecName)*/)
    }
}