// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * The H.265 parameter sets at the start of a keyframe (Annex B): VPS, SPS and PPS, which a decoder takes together as
 * csd-0, and the profile, level and size the SPS declares. The Video Doorbell WiFi Gen 2 sends its main stream as H.265.
 */
public final class HevcConfig {
    static final int VPS = 32, SPS = 33, PPS = 34;
    private static final byte[] START = {0, 0, 0, 1};

    /** Each NAL unit with its two-byte header, without a start code; null when the keyframe lacks it. */
    public final byte[] vps, sps, pps;
    public final SpsInfo size;
    private int profile = -1, level = -1;

    private HevcConfig(byte[] vps, byte[] sps, byte[] pps) {
        this.vps = vps;
        this.sps = sps;
        this.pps = pps;
        size = sps == null ? null : parse(sps);
    }

    public static HevcConfig of(byte[] annexB) {
        return new HevcConfig(nal(annexB, VPS), nal(annexB, SPS), nal(annexB, PPS));
    }

    /** Whether an access unit is H.265: it holds a VPS, which H.264 has no such NAL unit for. */
    public static boolean is(byte[] annexB) {
        return nal(annexB, VPS) != null;
    }

    public boolean complete() {
        return vps != null && sps != null && pps != null;
    }

    /** csd-0: VPS, SPS and PPS, each behind a 4-byte start code. */
    public byte[] csd() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] set : new byte[][] {vps, sps, pps}) {
            out.write(START, 0, START.length);
            out.write(set, 0, set.length);
        }
        return out.toByteArray();
    }

    /** Such as "H.265 Main profile, level 5.1, 2560x1920". */
    public String describe() {
        if (sps == null) return "H.265, no SPS";
        String name = profile == 1 ? "Main" : profile == 2 ? "Main 10" : profile == 3 ? "Main Still Picture" : "profile " + profile;
        String text = String.format(Locale.ROOT, "H.265 %s profile, level %.1f", name, level / 30.0);
        return size == null ? text : text + ", " + size.width + "x" + size.height;
    }

    /** The picture size, reading the SPS up to its conformance window; null when it cannot be read. */
    private SpsInfo parse(byte[] nal) {
        try {
            SpsInfo.Bits bits = new SpsInfo.Bits(SpsInfo.unescape(nal));
            bits.skip(16);  // NAL header
            bits.skip(4);  // sps_video_parameter_set_id
            int subLayers = bits.read(3);  // sps_max_sub_layers_minus1
            bits.skip(1);  // sps_temporal_id_nesting_flag
            bits.skip(3);  // general_profile_space, general_tier_flag
            profile = bits.read(5);
            bits.skip(32 + 48);  // compatibility flags, constraint flags
            level = bits.read(8);
            boolean[] profilePresent = new boolean[subLayers], levelPresent = new boolean[subLayers];
            for (int i = 0; i < subLayers; i++) {
                profilePresent[i] = bits.read(1) == 1;
                levelPresent[i] = bits.read(1) == 1;
            }
            if (subLayers > 0) bits.skip(2 * (8 - subLayers));
            for (int i = 0; i < subLayers; i++) {
                if (profilePresent[i]) bits.skip(88);
                if (levelPresent[i]) bits.skip(8);
            }
            bits.ue();  // sps_seq_parameter_set_id
            int chroma = bits.ue();
            if (chroma == 3) bits.skip(1);  // separate_colour_plane_flag
            int width = bits.ue(), height = bits.ue();
            if (bits.read(1) == 1) {  // conformance_window_flag: offsets in chroma samples
                int left = bits.ue(), right = bits.ue(), top = bits.ue(), bottom = bits.ue();
                width -= (chroma == 1 || chroma == 2 ? 2 : 1) * (left + right);
                height -= (chroma == 1 ? 2 : 1) * (top + bottom);
            }
            return width > 0 && height > 0 ? new SpsInfo(width, height) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The first NAL unit of a type, found behind a 3- or 4-byte start code. */
    static byte[] nal(byte[] annexB, int type) {
        int i = 0;
        while ((i = next(annexB, i)) >= 0) {
            int start = i;
            int end = next(annexB, start);
            end = end < 0 ? annexB.length : end - 3;
            while (end > start && annexB[end - 1] == 0) end--;  // a 4-byte start code's first byte, or padding
            if (end - start >= 2 && H265Depacketizer.type(annexB[start]) == type && (annexB[start] & 0x80) == 0 && (annexB[start + 1] & 0x07) != 0) {
                return Arrays.copyOfRange(annexB, start, end);
            }
            i = start;
        }
        return null;
    }

    private static int next(byte[] data, int i) {
        for (int j = i; j + 2 < data.length; j++) {
            if (data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 1) return j + 3;
        }
        return -1;
    }
}
