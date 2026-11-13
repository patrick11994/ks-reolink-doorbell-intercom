// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

/**
 * The picture size from an H.264 sequence parameter set, enough to configure a decoder before its first frame, and
 * the same SPS with a promise of no reordering added (lowDelay).
 */
public final class SpsInfo {
    public final int width;
    public final int height;

    SpsInfo(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** sps is the NAL unit without a start code. Returns null when it cannot be read. */
    public static SpsInfo parse(byte[] sps) {
        int[] read = read(sps);
        return read == null ? null : new SpsInfo(read[0], read[1]);
    }

    /**
     * The SPS with VUI saying that pictures come out in decoding order and one at a time (bitstream_restriction:
     * max_num_reorder_frames 0, max_dec_frame_buffering the reference frames), or null when it already has VUI or
     * cannot be read. Without VUI a decoder has to assume the most reordering the level allows, and may hold back as
     * many pictures as fit its buffer before showing the first: the doorbell's main stream, at level 5.1 and
     * 2560x1920, nine pictures, 450 ms. Only for a stream that does not reorder, such as the doorbell's: its frames
     * arrive in the order they were filmed.
     */
    public static byte[] lowDelay(byte[] sps) {
        int[] read = read(sps);
        if (read == null || read[4] != 0) return null;
        byte[] rbsp = unescape(sps);
        Writer out = new Writer();
        Bits in = new Bits(rbsp);
        for (int i = 0; i < read[3]; i++) out.write(in.read(1), 1);  // everything up to vui_parameters_present_flag
        out.write(1, 1);  // vui_parameters_present_flag
        out.write(0, 5);  // no aspect ratio, overscan, video signal type, chroma location, timing
        out.write(0, 3);  // no NAL HRD, VCL HRD, pic_struct
        out.write(1, 1);  // bitstream_restriction_flag
        out.write(1, 1);  // motion_vectors_over_pic_boundaries_flag
        out.ue(2);  // max_bytes_per_pic_denom
        out.ue(1);  // max_bits_per_mb_denom
        out.ue(16);  // log2_max_mv_length_horizontal
        out.ue(16);  // log2_max_mv_length_vertical
        out.ue(0);  // max_num_reorder_frames
        out.ue(Math.max(1, read[2]));  // max_dec_frame_buffering
        out.write(1, 1);  // rbsp_stop_one_bit, then zeros to the byte
        return escape(out.toByteArray());
    }

    /** Width, height, max_num_ref_frames, the bit at which vui_parameters_present_flag stands, and that flag; or null. */
    private static int[] read(byte[] sps) {
        try {
            Bits bits = new Bits(unescape(sps));
            bits.skip(8);  // NAL header
            int profile = bits.read(8);
            bits.skip(16);  // constraint flags, level
            bits.ue();  // seq_parameter_set_id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86 || profile == 118 || profile == 128) {
                chroma = bits.ue();
                if (chroma == 3) bits.skip(1);
                bits.ue();
                bits.ue();
                bits.skip(1);
                if (bits.read(1) == 1) {
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (bits.read(1) == 1) skipScalingList(bits, i < 6 ? 16 : 64);
                    }
                }
            }
            bits.ue();  // log2_max_frame_num
            int pocType = bits.ue();
            if (pocType == 0) bits.ue();
            else if (pocType == 1) {
                bits.skip(1);
                bits.se();
                bits.se();
                int cycle = bits.ue();
                for (int i = 0; i < cycle; i++) bits.se();
            }
            int refFrames = bits.ue();
            bits.skip(1);
            int widthMbs = bits.ue() + 1;
            int heightMapUnits = bits.ue() + 1;
            int frameMbsOnly = bits.read(1);
            if (frameMbsOnly == 0) bits.skip(1);
            bits.skip(1);
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (bits.read(1) == 1) {
                cropLeft = bits.ue();
                cropRight = bits.ue();
                cropTop = bits.ue();
                cropBottom = bits.ue();
            }
            int vuiAt = bits.position;
            int vui = bits.read(1);
            int cropUnitX = chroma == 0 || chroma == 3 ? 1 : 2;
            int cropUnitY = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
            int width = widthMbs * 16 - cropUnitX * (cropLeft + cropRight);
            int height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropUnitY * (cropTop + cropBottom);
            return width > 0 && height > 0 ? new int[] {width, height, refFrames, vuiAt, vui} : null;
        } catch (RuntimeException broken) {
            return null;
        }
    }

    private static void skipScalingList(Bits bits, int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + bits.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Removes emulation-prevention bytes (00 00 03). */
    static byte[] unescape(byte[] data) {
        byte[] out = new byte[data.length];
        int n = 0, zeros = 0;
        for (byte b : data) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out[n++] = b;
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Adds emulation-prevention bytes: a 03 after two zeros when the next byte is 03 or less. */
    private static byte[] escape(byte[] rbsp) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(rbsp.length + 4);
        int zeros = 0;
        for (byte b : rbsp) {
            if (zeros >= 2 && (b & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static final class Writer {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int current, count;

        void write(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                if (++count == 8) {
                    out.write(current);
                    current = count = 0;
                }
            }
        }

        void ue(int value) {
            int length = 32 - Integer.numberOfLeadingZeros(value + 1);
            write(0, length - 1);
            write(value + 1, length);
        }

        /** With the last byte filled up with zeros. */
        byte[] toByteArray() {
            if (count > 0) write(0, 8 - count);
            return out.toByteArray();
        }
    }

    static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data) {
            this.data = data;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bit = (data[position / 8] >> (7 - position % 8)) & 1;
                position++;
                value = (value << 1) | bit;
            }
            return value;
        }

        void skip(int count) {
            position += count;
        }

        int ue() {
            int zeros = 0;
            while (read(1) == 0) zeros++;
            return (1 << zeros) - 1 + read(zeros);
        }

        int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }
}
