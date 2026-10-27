// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The doorbell's live stream over Baichuan (BcMedia), from the payloads of its cmd 3 answers, already decrypted. A
 * frame can span several messages, so bytes are buffered until a frame is complete:
 *   "1001"/"1002": a 32-byte info header (width, height, frames per second);
 *   "00dc"/"90dc": an H.264 keyframe, "01dc"/"91dc": any other frame: magic, codec ("H264"), payload size, extra
 *     header size, microseconds, unknown; then the extra header, the Annex-B payload and padding to 8 bytes;
 *   "05wb": AAC as ADTS: magic, size, size, the data, padding to 8. The ADTS header is removed here.
 * Layout first learned from neolink (AGPL-3.0); no code copied. A frame larger than MAX_FRAME, or bytes that are no
 * frame, make it skip ahead to the next magic, so a broken stream cannot make the process allocate without bound.
 */
public final class BcMediaParser {
    public interface Listener {
        void onInfo(int width, int height, int fps);
        void onVideo(byte[] annexB, long micros, boolean keyframe, boolean h265);
        /** One raw AAC frame (ADTS removed) and the AudioSpecificConfig its ADTS header describes. */
        void onAac(byte[] raw, byte[] config, int sampleRate);
    }

    static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private final Listener listener;
    private byte[] buf = new byte[64 * 1024];
    private int length;
    private long skipped;

    public BcMediaParser(Listener listener) {
        this.listener = listener;
    }

    /** Bytes skipped while looking for a frame: zero on a healthy stream. */
    public long skipped() {
        return skipped;
    }

    public void feed(byte[] data, int offset, int count) {
        if (length + count > buf.length) {
            int want = Math.max(buf.length * 2, length + count);
            if (want > MAX_FRAME + 64 * 1024) {
                // A frame this large is not one: drop what is buffered and look for a new start in the new bytes.
                skipped += length;
                length = 0;
                want = Math.max(buf.length, count);
            }
            byte[] bigger = new byte[Math.min(want, MAX_FRAME + 64 * 1024)];
            System.arraycopy(buf, 0, bigger, 0, length);
            buf = bigger;
        }
        int n = Math.min(count, buf.length - length);
        System.arraycopy(data, offset, buf, length, n);
        length += n;
        parse();
    }

    private void parse() {
        int at = 0;
        while (true) {
            int used = frameLength(at);
            if (used == 0) break;  // incomplete: wait for more bytes
            if (used < 0) {
                at++;  // not a frame: look for the next magic
                skipped++;
                continue;
            }
            if (at + used > length) break;
            emit(at, used);
            at += used;
        }
        System.arraycopy(buf, at, buf, 0, length - at);
        length -= at;
    }

    /** The length of the frame at at with its padding; 0 if more bytes are needed, -1 if this is no frame. */
    private int frameLength(int at) {
        if (length - at < 8) return 0;
        ByteBuffer b = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        byte m0 = buf[at], m1 = buf[at + 1], m2 = buf[at + 2], m3 = buf[at + 3];
        if (m0 == '1' && m1 == '0' && m2 == '0' && (m3 == '1' || m3 == '2')) return 32;
        if ((m0 == '0' || m0 == '9') && (m1 == '0' || m1 == '1') && m2 == 'd' && m3 == 'c') {
            if (length - at < 24) return 0;
            long size = b.getInt(at + 8) & 0xFFFFFFFFL, extra = b.getInt(at + 12) & 0xFFFFFFFFL;
            if (size > MAX_FRAME || extra > 1024) return -1;
            return (int) (24 + extra + size + pad(size));
        }
        if (m0 == '0' && m1 == '5' && m2 == 'w' && m3 == 'b') {
            int size = b.getShort(at + 4) & 0xFFFF;
            return 8 + size + (int) pad(size);
        }
        return -1;
    }

    private void emit(int at, int used) {
        ByteBuffer b = ByteBuffer.wrap(buf, at, used).order(ByteOrder.LITTLE_ENDIAN);
        byte m0 = buf[at], m1 = buf[at + 1];
        if (m0 == '1') {
            listener.onInfo(b.getInt(at + 8), b.getInt(at + 12), buf[at + 17] & 0xFF);
        } else if (buf[at + 2] == 'd') {
            boolean h265 = buf[at + 4] == 'H' && buf[at + 5] == '2' && buf[at + 6] == '6' && buf[at + 7] == '5';
            int size = b.getInt(at + 8), extra = b.getInt(at + 12);
            long micros = b.getInt(at + 16) & 0xFFFFFFFFL;
            byte[] annexB = new byte[size];
            System.arraycopy(buf, at + 24 + extra, annexB, 0, size);
            listener.onVideo(annexB, micros, m1 == '0', h265);
        } else {
            int size = b.getShort(at + 4) & 0xFFFF;
            aac(at + 8, size);
        }
    }

    /** Splits an ADTS frame into its AudioSpecificConfig and the raw frame. */
    private void aac(int at, int size) {
        if (size < 7 || (buf[at] & 0xFF) != 0xFF || (buf[at + 1] & 0xF0) != 0xF0) return;
        boolean crc = (buf[at + 1] & 1) == 0;
        int header = crc ? 9 : 7;
        int profile = (buf[at + 2] >> 6) & 3, rateIndex = (buf[at + 2] >> 2) & 0xF;
        int channels = ((buf[at + 2] & 1) << 2) | ((buf[at + 3] >> 6) & 3);
        int blocks = buf[at + 6] & 3;  // raw data blocks minus one: only single-block frames are passed on
        if (size <= header || rateIndex >= AAC_RATES.length || blocks != 0) return;
        int objectType = profile + 1;
        byte[] config = {(byte) (objectType << 3 | rateIndex >> 1), (byte) ((rateIndex & 1) << 7 | channels << 3)};
        byte[] raw = new byte[size - header];
        System.arraycopy(buf, at + header, raw, 0, raw.length);
        listener.onAac(raw, config, AAC_RATES[rateIndex]);
    }

    private static long pad(long size) {
        return (8 - size % 8) % 8;
    }

}
