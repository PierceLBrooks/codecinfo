
// Author: Pierce Brooks

package com.parseus.codecinfo.data.codecinfo;

import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.chaofan.util.bitstream.BitInputStream;
import io.chaofan.util.bitstream.LittleEndianBitInputStream;

public class IVF {
    public class Frame {
        public String codec;
        public long width;
        public long height;
        public byte[] payload;
        public long timestamp;
        public Frame next;

        public Frame(String codec, long width, long height, byte[] payload, long timestamp) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.payload = payload;
            this.timestamp = timestamp;
            this.next = null;
        }
    }

    private BitInputStream stream;
    private long length;
    private int version;
    private int header;
    private String codec;
    private long width;
    private long height;
    private long frameRate;
    private long timeScale;
    private long frameCount;
    private long reservation;
    private long frames;
    private Frame previous;

    public IVF(InputStream stream) throws Exception {
        this.stream = new LittleEndianBitInputStream(stream);
        this.length = 0;
        this.version = 0;
        this.header = 0;
        this.codec = "";
        this.width = 0;
        this.height = 0;
        this.frameRate = 0;
        this.timeScale = 0;
        this.frameCount = 0;
        this.reservation = 0;
        this.frames = 0;
        this.previous = null;

        long magic = this.stream.read(32);
        if (magic == 0x46494B44) {
            this.version += this.stream.read(16);
            this.header += this.stream.read(16);
            this.codec += (char)this.stream.read(8);
            this.codec += (char)this.stream.read(8);
            this.codec += (char)this.stream.read(8);
            this.codec += (char)this.stream.read(8);
            this.width += this.stream.read(16);
            this.height += this.stream.read(16);
            this.frameRate += this.stream.read(32);
            this.timeScale += this.stream.read(32);
            this.frameCount += this.stream.read(32);
            this.reservation += this.stream.read(32);
        } else {
            Log.e("IVF", "Bad Magic = "+Long.toHexString(magic));
        }
    }

    public Frame read() {
        Frame frame = null;
        byte[] payload = null;
        long timestamp = 0;
        int length = 0;
        if (frames >= frameCount) {
            return frame;
        }
        frames += 1;
        try {
            length += stream.read(32);
            if (length > 0) {
                payload = new byte[length];
            }
            timestamp += (long)stream.read(32);
            timestamp += ((long)stream.read(32)) << 32;
            for (int i = 0; i < length; i++) {
                payload[i] = (byte)stream.read(8);
            }
            frame = new Frame(codec, width, height, payload, timestamp);
        } catch (Exception e) {
            frame = null;
        }
        return frame;
    }

    public List<Frame> readAll() {
        List<Frame> frames = new ArrayList<Frame>();
        Frame frame = read();
        while (frame != null) {
            Frame next = read();
            frames.add(frame);
            frame.next = next;
            frame = next;
        }
        return frames;
    }
}
