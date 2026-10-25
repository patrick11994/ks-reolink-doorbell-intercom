// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two Baichuan encryptions: a fixed-key XOR for the login, AES-128-CFB with a key derived from the login nonce
 * for everything after. Protocol knowledge from reolink_aio (MIT), reimplemented here.
 */
final class BcCrypto {
    private static final byte[] XML_KEY = {0x1F, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78, (byte) 0xFF};
    private static final byte[] AES_IV = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private BcCrypto() {}

    /** Baichuan XOR; encrypting and decrypting are the same operation. offset is the message's channel byte. */
    static byte[] xor(byte[] data, int offset) {
        offset &= 0xFF;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ XML_KEY[(offset + i) % XML_KEY.length] ^ offset);
        return out;
    }

    /** The Baichuan login hash: MD5 as upper-case hex, cut to 31 characters. */
    static String md5Modern(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return hex.substring(0, 31).toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The session's AES key, from the login nonce and the password. */
    static byte[] aesKey(String nonce, String password) {
        return md5Modern(nonce + "-" + password).substring(0, 16).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] aes(byte[] key, byte[] data, boolean encrypt) {
        if (data.length == 0) return data;
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(AES_IV));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
