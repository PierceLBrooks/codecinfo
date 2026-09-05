
// Author: Pierce Brooks

package com.parseus.codecinfo.data.codecinfo;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import androidx.annotation.NonNull;

import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Decoder {
    public interface Listener {
        public void decoderDidError(Decoder sender);
        public void decoderDidChangeFormat(Decoder sender, int width, int height);
    }

    public enum Mode {
        SYNC,
        ASYNC,
    }

    public enum Codec {
        H264("video/avc"),
        HEVC("video/hevc"),
        VP8("video/x-vnd.on2.vp8"),
        VP9("video/x-vnd.on2.vp9"),
        AV1("video/av01");
        private final String mime;
        public String getMime() {
            return mime;
        }
        Codec(String mime) {
            this.mime = mime;
        }
    }

    public class Packet {
        byte[] data;
        int dataSize;
        long pts;

        Packet(byte[] data, int dataSize, long pts) {
            this.data = data.clone();
            this.dataSize = dataSize;
            this.pts = pts;
        }
    }

    private static final String TAG = "PLB-Decoder";
    private static final int DECODER_TIMEOUT = 25000;
    private static final String[] blocklist = {
        /*
        "OMX.google.h264",
        "OMX.google.hevc",
        "OMX.qcom.audio.decoder.multiaac",
        "OMX.SEC.avc.sw.dec"
        */
    };

    private Mode mode;
    private Surface surface;
    private int width;
    private int height;
    private Codec codec;
    private String mime;
    private Decoder that;
    private Listener listener;
    private MediaCodec decoder;
    private MediaFormat format;
    private long initialPTS = -1;
    private ByteBuffer[] inputBuffers;
    private byte[] lastExtraData;
    private Queue<Packet> packetQueue;
    private Thread pollingThread;
    private boolean pollingThreadKeepRunning;
    private boolean rebuild;

    public Decoder(Surface surface, Codec codec, String mime, int width, int height) {
        this.surface = null;
        this.codec = codec;
        this.mime = mime;
        this.width = width;
        this.height = height;
        this.mode = Mode.ASYNC;
        this.listener = null;
        this.pollingThreadKeepRunning = false;
        this.rebuild = false;
        this.packetQueue = new ConcurrentLinkedQueue<Packet>();
        this.that = this;
        setSurface(surface);
    }

    public void close() {
        if (pollingThread != null) {
            pollingThreadKeepRunning = false;

            try {
                pollingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to join the polling thread");
                e.printStackTrace();
            }
        }

        packetQueue.clear();

        if (decoder != null) {
            try {
                decoder.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to finalize the decoder");
                e.printStackTrace();
            }
        }
    }

    public boolean addEncodedData(byte[] data, int dataSize, byte[] extraData, int extraDataSize, long pts) {
        return addEncodedData(data, dataSize, extraData, extraDataSize, pts, false);
    }

    public boolean addEncodedData(byte[] data, int dataSize, byte[] extraData, int extraDataSize, long pts, boolean rebuild) {
        this.rebuild |= rebuild;
        if (decoder == null || this.rebuild || hasExtraDataChanged(extraData, extraDataSize)) {
            if (!rebuildDecoder(extraData, extraDataSize)) {
                return false;
            } else {
                this.rebuild = false;
            }
        }

        if (initialPTS == -1) {
            initialPTS = pts;
        }

        long adjustedPTS = pts - initialPTS;

        boolean success = false;
        switch (this.mode) {
            case ASYNC:
                success = addEncodedDataAsync(data, dataSize, pts);
                break;
            case SYNC:
                success = addEncodedDataSync(data, dataSize, pts);
                break;
        }
        return success;
    }

    private boolean addEncodedDataAsync(byte[] data, int dataSize, long pts) {
        Packet packet = new Packet(data, dataSize, pts);
        return packetQueue.add(packet);
    }

    private boolean addEncodedDataSync(byte[] data, int dataSize, long pts) {
        boolean success = true;
        int attempt = 1;
        while (attempt < 20) {
            int inputBufferId = Integer.MIN_VALUE;
            try {
                inputBufferId = decoder.dequeueInputBuffer(DECODER_TIMEOUT);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Illegal state attempting to input buffer. Skipping.");
                e.printStackTrace();
                success = false;
                break;
            }

            if (inputBufferId >= 0) {
                ByteBuffer inputBuffer = null;

                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        inputBuffer = decoder.getInputBuffer(inputBufferId);
                    } else {
                        inputBuffer = inputBuffers[inputBufferId];
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Illegal state attempting to get input buffer " + inputBufferId);
                    e.printStackTrace();
                    success = false;
                    break;
                }

                inputBuffer.clear();
                inputBuffer.put(data, 0, dataSize);

                Log.v(TAG, ""+dataSize);

                try {
                    decoder.queueInputBuffer(inputBufferId, 0, dataSize, pts, 0);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Illegal state attempting to queue input buffer");
                    e.printStackTrace();
                    success = false;
                }

                break;
            } else {
                Log.w(TAG, "Failed to get input buffer on attempt " + attempt);
                attempt += 1;
            }
        }

        if (attempt >= 20) {
            Log.e(TAG, "Could not get an input buffer, so we gave up, which is a fatal error");
            success = false;
        }
        return success;
    }

    private static String getDecoderName(String mimetype, String[] blocklist) {
        String name = "";

        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            Log.i(TAG, "Inspecting codec " + info.getName());

            if (info.isEncoder()) {
                Log.i(TAG, "- Codec is an encoder. Skipping.");
                continue;
            }

            MediaCodecInfo.CodecCapabilities capabilities = null;

            try {
                capabilities = info.getCapabilitiesForType(mimetype);
            } catch (Exception e) {
                Log.e(TAG, "- Codec did not have proper capabilities. Skipping.");
                continue;
            }

            if (capabilities == null) {
                continue;
            }

            String lowerName = info.getName().toLowerCase(Locale.US);
            boolean isblocklisted = false;
            for (int _blocklisted = 0; _blocklisted < blocklist.length; _blocklisted++) {
                String blocklisted = blocklist[_blocklisted];
                if (lowerName.contains(blocklisted.toLowerCase(Locale.US))) {
                    isblocklisted = true;
                    break;
                }
            }

            if (isblocklisted) {
                Log.e(TAG, "- Codec is blocklisted. Skipping.");
                continue;
            }

            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimetype)) {
                    name = info.getName();
                    Log.i(TAG, "Choosing " + name + " for decoding");

                    return name;
                }
            }
        }

        return name;
    }

    private boolean hasExtraDataChanged(byte[] extraData, int extraDataSize) {
        if (extraData == null) {
            return false;
        }

        if (lastExtraData == null) {
            return true;
        }

        if (extraDataSize != lastExtraData.length) {
            return true;
        }

        if (Arrays.equals(Arrays.copyOfRange(extraData, 0, extraDataSize), lastExtraData)) {
            return false;
        }

        Log.d(TAG, "extra data has changed");
        return true;
    }

    public boolean rebuildDecoder(byte[] extraData, int extraDataSize) {
        if (extraData == null) {
            if (codec.equals(Codec.VP8)) {
                return rebuildDecoder(null);
            }
            return false;
        }
        if (Mode.ASYNC.equals(mode)) {
            packetQueue.clear();
        }
        int length = extraData.length;
        if (extraDataSize == length) {
            return rebuildDecoder(extraData);
        }
        if (extraDataSize > length) {
            return false;
        }
        return rebuildDecoder(Arrays.copyOfRange(extraData, 0, extraDataSize));
    }

    private boolean rebuildDecoder(byte[] extraData) {
        Log.i(TAG, "Rebuilding decoder ("+width+"x"+height+")...");

        packetQueue.clear();

        if (pollingThread != null) {
            pollingThreadKeepRunning = false;

            try {
                pollingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to join polling thread on rebuild");
                e.printStackTrace();
            }

            pollingThread = null;
        }

        initialPTS = -1;

        if (surface == null) {
            Log.e(TAG, "Decoder surface is null!");
            return false;
        }
        if (!surface.isValid()) {
            Log.e(TAG, "Decoder surface is invalid!");
            return false;
        }

        String mimetype;
        switch (codec) {
            case H264:
            case HEVC:
            case VP8:
            case VP9:
            case AV1:
                mimetype = codec.getMime();
                break;
            default:
                mimetype = "ERROR";
                break;
        }

        boolean ready = true;
        String name = mime;
        if (name == null) {
            name = getDecoderName(mimetype, blocklist);
        }

        if (decoder == null) {
            try {
                decoder = MediaCodec.createByCodecName(name);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create initial decoder");
                e.printStackTrace();
                ready = false;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to create initial decoder with an invalid name: " + name);
                e.printStackTrace();
                ready = false;
            }
        } else {
            try {
                decoder.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to de-initialize the decoder");
                e.printStackTrace();
                ready = false;
            }
        }

        if (!ready) {
            return false;
        }

        format = MediaFormat.createVideoFormat(mimetype, width, height);
        format.setString(MediaFormat.KEY_MIME, mimetype);

        if (extraData != null) {
            if (codec.equals(Codec.H264)) {
                ByteBuffer csd0 = ByteBuffer.wrap(extraData);
                format.setByteBuffer("csd-0", csd0);

                int position = -1;
                for (int idx = 3; idx + 3 < extraData.length; idx++) {
                    if (extraData[idx] == 0x00 && extraData[idx + 1] == 0x00 && extraData[idx + 2] == 0x01) {
                        position = idx;
                        break;
                    }
                }

                if (position > -1) {
                    ByteBuffer csd1 = ByteBuffer.wrap(extraData, position, extraData.length - position);
                    format.setByteBuffer("csd-1", csd1);
                } else {
                    ByteBuffer csd1 = ByteBuffer.wrap(extraData);
                    format.setByteBuffer("csd-1", csd1);
                    Log.w(TAG, "Could not find PPS @ "+extraData.length+"?");
                }
            } else if (codec.equals(Codec.HEVC)) {
                ByteBuffer csd0 = ByteBuffer.wrap(extraData);
                format.setByteBuffer("csd-0", csd0);
            }

            lastExtraData = extraData.clone();
        } else {
            lastExtraData = null;
        }

        if (mode.equals(Mode.ASYNC)) {
            if (Build.VERSION.SDK_INT >= 21) {
                final Decoder thiz = this;

                decoder.setCallback(new MediaCodec.Callback() {
                    @Override @TargetApi(21)
                    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int idx) {
                        Packet packet = packetQueue.poll();

                        long submittedPTS = 0;
                        int submittedSize = 0;
                        ByteBuffer inputBuffer = null;

                        if (packet != null) {
                            try {
                                inputBuffer = mediaCodec.getInputBuffer(idx);
                            } catch (IllegalStateException e) {
                                Log.w(TAG, "Attempted to get an input buffer in an illegal state, which might mean a transition is occurring");
                                inputBuffer = null;
                            }
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(packet.data, 0, packet.dataSize);

                                submittedPTS = packet.pts;
                                submittedSize = packet.dataSize;
                            }
                        }

                        try {
                            mediaCodec.queueInputBuffer(idx, 0, submittedSize, submittedPTS, 0);
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "Attempted to submit an input buffer in an illegal state, which might mean a transition is occurring");
                        }
                    }

                    @Override
                    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int idx, @NonNull MediaCodec.BufferInfo bufferInfo) {
                        try {
                            mediaCodec.releaseOutputBuffer(idx, true);
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "Attempted to release an output buffer in an illegal state, which might mean a transition is occurring");
                        }
                    }

                    @Override @TargetApi(21)
                    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                        Log.e(TAG, "Decoder experienced an internal error");

                        if (!e.isRecoverable()) {
                            mediaCodec.reset();
                        }

                        if (listener != null) {
                            listener.decoderDidError(thiz);
                        }
                    }

                    @Override
                    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
                        int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                        int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                        if (width == that.width && height == that.height) {
                            return;
                        }

                        Log.i(TAG, "Decoder changed format to " + width + "x" + height);

                        if (listener != null) {
                            listener.decoderDidChangeFormat(thiz, width, height);
                        }
                    }
                });
            } else {
                Log.e(TAG, "Attempting to use ASYNC mode on a version of Android that does not support it");
            }
        }

        if (mode.equals(Mode.SYNC)) {
            final Decoder thiz = this;
            pollingThreadKeepRunning = true;
            pollingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (pollingThreadKeepRunning) {
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int outputBufferId = Integer.MIN_VALUE;

                        try {
                            outputBufferId = decoder.dequeueOutputBuffer(info, DECODER_TIMEOUT);
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Illegal state attempting to dequeue output buffer. Skipping.");
                            pollingThreadKeepRunning = false;
                            rebuild = true;
                        }

                        if (outputBufferId >= 0) {
                            decoder.releaseOutputBuffer(outputBufferId, true);
                        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat mediaFormat = decoder.getOutputFormat();

                            int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                            int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);

                            Log.i(TAG, "Decoder changed format to " + width + "x" + height);

                            if (listener != null) {
                                listener.decoderDidChangeFormat(thiz, width, height);
                            }
                        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            inputBuffers = decoder.getInputBuffers();
                        }
                    }
                }
            });
        }

        boolean success = true;
        int tries = 0;
        do {
            if (tries >= 10) {
                break;
            }
            if (!success) {
                try {
                    decoder.reset();
                } catch (MediaCodec.CodecException e) {
                    Log.w(TAG, "Likely unrecoverable...");
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Already uninitialized...");
                }
            }
            try {
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                decoder.configure(format, surface, null, 0);
                decoder.start();
            } catch (MediaCodec.CodecException e) {
                Log.e(TAG, "Failed to configure decoder: " + e.getMessage());
                success = false;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to configure decoder with an invalid argument: " + e.getMessage());
                e.printStackTrace();
                success = false;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to configure decoder in an invalid state: " + e.getMessage());
                e.printStackTrace();
                success = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
            ++tries;
        } while (!success);

        try {
            if (success) {
                if (Build.VERSION.SDK_INT < 21) {
                    inputBuffers = decoder.getInputBuffers();
                }
                if (pollingThread != null) {
                    if (pollingThread.getState() == Thread.State.NEW) {
                        pollingThread.start();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            success = false;
        }

        return success;
    }
    
    public boolean rebuildDecoder() {
        return rebuildDecoder(lastExtraData);
    }

    public void updateDimensions(int width, int height) {
        if (this.width == width && this.height == height) {
            Log.w(TAG, "Redundant dimensions...");
            return;
        }
        this.width = width;
        this.height = height;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSurface(Surface surface) {
        Log.v(TAG, "New surface...");
        if ((surface != null) && (this.surface != surface)) {
            this.rebuild = true;
        }
        this.surface = surface;
    }

    public Surface getSurface() {
        return surface;
    }
}
