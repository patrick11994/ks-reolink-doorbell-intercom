// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

public final class AvcConfigTest {
    public static void main(String[] args) {
        // The Video Doorbell PoE's sub stream: High profile, level 5.1, though it is only 640x480.
        byte[] sps = Base64.getDecoder().decode("Z2QAM6wVFKCgPZA=");
        byte[] pps = {0x68, (byte) 0xEE, 0x3C, (byte) 0x80};
        byte[] idr = {0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x33};

        // 4-byte start codes, as the doorbell sends them.
        AvcConfig config = AvcConfig.of(concat(start4(), sps, start4(), pps, start4(), idr));
        check(config.complete(), true);
        check(Arrays.equals(config.sps, sps), true);
        check(Arrays.equals(config.pps, pps), true);
        check(config.profile(), 100);
        check(config.level(), 51);
        check(config.describe(), "High profile, level 5.1, 640x480");

        // 3-byte start codes, and zero padding before the next 4-byte start code.
        byte[] ppsZero = {0x68, (byte) 0xEE, 0x3C, 0x00};
        AvcConfig mixed = AvcConfig.of(concat(new byte[] {0, 0, 1}, sps, new byte[] {0, 0, 1}, ppsZero, start4(), idr));
        check(Arrays.equals(mixed.sps, sps), true);
        check(Arrays.equals(mixed.pps, new byte[] {0x68, (byte) 0xEE, 0x3C}), true);  // the padding is not part of it

        // csd-0 is the NAL unit behind a 4-byte start code.
        check(Arrays.equals(AvcConfig.withStartCode(sps), concat(start4(), sps)), true);

        // A frame without parameter sets: nothing up front.
        AvcConfig none = AvcConfig.of(concat(start4(), idr));
        check(none.complete(), false);
        check(none.describe(), "no SPS");
        check(AvcConfig.of(new byte[0]).complete(), false);
        check(AvcConfig.of(new byte[] {0, 0, 1}).complete(), false);

        // The SPS with no reordering promised: the same picture, now with VUI, and left alone once it has it.
        for (String base64 : new String[] {"Z2QAM6wVFKCgPZA=", "Z2QAM6wVFKAoAPGQ"}) {  // sub and main stream
            byte[] original = Base64.getDecoder().decode(base64);
            byte[] lowDelay = SpsInfo.lowDelay(original);
            check(SpsInfo.parse(lowDelay).width, SpsInfo.parse(original).width);
            check(SpsInfo.parse(lowDelay).height, SpsInfo.parse(original).height);
            check(SpsInfo.lowDelay(lowDelay) == null, true);
        }
        check(SpsInfo.lowDelay(new byte[] {0x67}) == null, true);

        // An access unit gets the new SPS in place of its own; one without an SPS stays as it is.
        byte[] newSps = {0x67, 1, 2, 3, 4, 5, 6};
        check(Arrays.equals(AvcConfig.withSps(concat(start4(), sps, start4(), pps, start4(), idr), newSps), concat(start4(), newSps, start4(), pps, start4(), idr)), true);
        byte[] plain = concat(start4(), idr);
        check(AvcConfig.withSps(plain, newSps) == plain, true);

        System.out.println("AvcConfigTest passed");
    }

    private static byte[] start4() {
        return new byte[] {0, 0, 0, 1};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
