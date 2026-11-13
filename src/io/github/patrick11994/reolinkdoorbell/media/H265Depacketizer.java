// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.io.ByteArrayOutputStream;

/**
 * RTP H.265 (RFC 7798) to Annex-B access units: single NAL units, aggregation packets (type 48) and fragmentation units
 * (type 49), without DONL fields (the doorbell sends no sprop-max-don-diff). An access unit is complete on the RTP
 * marker bit or when the timestamp moves on, as for H.264. A lost fragment drops that NAL, not the stream.
 */
public final class H265Depacketizer {
    private static final byte[] START = {0, 0, 0, 1};
    private static final int AP = 48, FU = 49;

    private final H264Depacketizer.Sink sink;
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(256 * 1024);
    private final ByteArrayOutputStream fragment = new ByteArrayOutputStream(256 * 1024);
    private byte[] parameterSets;  // VPS, SPS and PPS from the SDP, in Annex-B form, for keyframes that lack them
    private boolean fragmenting;
    private boolean keyframe;
    private boolean picture;  // the unit holds a slice, not only parameter sets or SEI
    private boolean hasParameterSets;
    private long timestamp = -1;
    private int lastSequence = -1;

    public H265Depacketizer(H264Depacketizer.Sink sink) {
        this.sink = sink;
    }

    /** sprop-vps, sprop-sps and sprop-pps, each without a start code: put before a keyframe that does not carry its own. */
    public void parameterSets(byte[]... sets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] set : sets) {
            if (set == null || set.length < 2) continue;
            out.write(START, 0, START.length);
            out.write(set, 0, set.length);
        }
        parameterSets = out.size() > 0 ? out.toByteArray() : null;
    }

    /** The NAL unit type from the first byte of its two-byte header. */
    static int type(byte first) {
        return (first >> 1) & 0x3F;
    }

    public void onRtp(byte[] packet, int offset, int length) {
        if (length < 12) return;
        int csrc = packet[offset] & 0x0F;
        boolean extension = (packet[offset] & 0x10) != 0;
        boolean marker = (packet[offset + 1] & 0x80) != 0;
        int sequence = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
        long ts = ((packet[offset + 4] & 0xFFL) << 24) | ((packet[offset + 5] & 0xFFL) << 16) | ((packet[offset + 6] & 0xFFL) << 8) | (packet[offset + 7] & 0xFFL);
        int header = 12 + 4 * csrc;
        if (extension && length >= header + 4) header += 4 + 4 * (((packet[offset + header + 2] & 0xFF) << 8) | (packet[offset + header + 3] & 0xFF));
        int padding = (packet[offset] & 0x20) != 0 ? packet[offset + length - 1] & 0xFF : 0;
        int start = offset + header, end = offset + length - padding;
        if (end - start < 2) return;

        if (lastSequence >= 0 && sequence != ((lastSequence + 1) & 0xFFFF)) fragmenting = false;  // a gap breaks the fragment
        lastSequence = sequence;
        if (timestamp >= 0 && ts != timestamp && picture) flush();
        timestamp = ts;

        int type = type(packet[start]);
        if (type < AP) {
            nal(packet, start, end - start);
        } else if (type == AP) {
            int at = start + 2;
            while (at + 2 <= end) {
                int size = ((packet[at] & 0xFF) << 8) | (packet[at + 1] & 0xFF);
                at += 2;
                if (size < 2 || at + size > end) break;
                nal(packet, at, size);
                at += size;
            }
        } else if (type == FU && end - start >= 3) {
            int fuHeader = packet[start + 2] & 0xFF;
            boolean first = (fuHeader & 0x80) != 0, last = (fuHeader & 0x40) != 0;
            if (first) {
                fragment.reset();
                fragment.write((packet[start] & 0x81) | ((fuHeader & 0x3F) << 1));  // the NAL header with the real type
                fragment.write(packet[start + 1]);
                fragmenting = true;
            }
            if (fragmenting && fragment.size() + (end - start - 3) > H264Depacketizer.MAX_UNIT) {
                fragment.reset();
                fragmenting = false;
                dropUnit();
            }
            if (fragmenting) {
                fragment.write(packet, start + 3, end - start - 3);
                if (last) {
                    byte[] nal = fragment.toByteArray();
                    nal(nal, 0, nal.length);
                    fragmenting = false;
                }
            }
        }
        if (marker && picture) flush();
    }

    private void nal(byte[] data, int offset, int length) {
        if (unit.size() + START.length + length > H264Depacketizer.MAX_UNIT) {
            dropUnit();
            return;
        }
        int type = type(data[offset]);
        if (type >= 16 && type <= 21) keyframe = true;  // IRAP: IDR, CRA or BLA
        if (type <= 31) picture = true;
        if (type == HevcConfig.VPS) hasParameterSets = true;
        unit.write(START, 0, START.length);
        unit.write(data, offset, length);
    }

    private void dropUnit() {
        unit.reset();
        keyframe = false;
        picture = false;
        hasParameterSets = false;
    }

    private void flush() {
        if (unit.size() > 0) {
            byte[] annexB = unit.toByteArray();
            if (keyframe && !hasParameterSets && parameterSets != null) {
                byte[] whole = new byte[parameterSets.length + annexB.length];
                System.arraycopy(parameterSets, 0, whole, 0, parameterSets.length);
                System.arraycopy(annexB, 0, whole, parameterSets.length, annexB.length);
                annexB = whole;
            }
            sink.onAccessUnit(annexB, timestamp, keyframe);
        }
        dropUnit();
    }
}
