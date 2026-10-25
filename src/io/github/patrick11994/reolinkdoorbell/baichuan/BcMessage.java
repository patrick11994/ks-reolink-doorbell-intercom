// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * One Baichuan message: a little-endian header (magic f0debc0a, command, body length, channel + message id, then
 * status/encryption, message class and, for the 24-byte header, the payload offset) followed by the body.
 */
final class BcMessage {
    static final int MAGIC = 0x0abcdef0;  // f0 de bc 0a on the wire
    static final int CLASS_MODERN = 0x6414;  // "1464": 24-byte header
    static final int CLASS_LEGACY = 0x6514;  // "1465": 20-byte header, used once to fetch the nonce
    static final int CLASS_MODERN_20 = 0x6614;  // "1466": 20-byte header
    static final int CLASS_ZERO = 0x0000;  // 24-byte header
    static final int MAX_BODY = 1024 * 1024;

    final int command;
    final int messageId;  // channel byte + 24-bit counter, little-endian, as one int
    final int statusOrEncryption;
    final int messageClass;
    final int headerLength;
    final byte[] body;  // still encrypted when received: the XML part up to the payload offset
    final byte[] payload;  // binary payload after the XML, if any

    BcMessage(int command, int messageId, int statusOrEncryption, int messageClass, int headerLength, byte[] body, byte[] payload) {
        this.command = command;
        this.messageId = messageId;
        this.statusOrEncryption = statusOrEncryption;
        this.messageClass = messageClass;
        this.headerLength = headerLength;
        this.body = body;
        this.payload = payload;
    }

    int channelByte() {
        return messageId & 0xFF;
    }

    /** A request with a 24-byte header. extension and body are already encrypted; the payload offset is the extension's length. */
    static byte[] encodeModern(int command, int messageId, byte[] extension, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(24 + extension.length + body.length);
        writeInt(out, MAGIC);
        writeInt(out, command);
        writeInt(out, extension.length + body.length);
        writeInt(out, messageId);
        writeShort(out, 0);
        writeShort(out, CLASS_MODERN);
        writeInt(out, extension.length);
        out.write(extension, 0, extension.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** The header-only legacy request that makes the device answer with its login nonce. */
    static byte[] encodeNonceRequest(int messageId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(20);
        writeInt(out, MAGIC);
        writeInt(out, 1);
        writeInt(out, 0);
        writeInt(out, messageId);
        writeShort(out, 0xdc12);  // "12dc"
        writeShort(out, CLASS_LEGACY);
        return out.toByteArray();
    }

    /** Reads one message, resynchronizing on the magic if stray bytes arrive. */
    static BcMessage read(DataInputStream in) throws IOException {
        int magic = readInt(in);
        while (magic != MAGIC) magic = (magic >>> 8) | ((in.readUnsignedByte()) << 24);
        int command = readInt(in);
        int length = readInt(in);
        int messageId = readInt(in);
        int status = readShort(in);
        int messageClass = readShort(in);
        int headerLength = 20;
        int payloadOffset = 0;
        if (messageClass == CLASS_MODERN || messageClass == CLASS_ZERO) {
            headerLength = 24;
            payloadOffset = readInt(in);
        } else if (messageClass != CLASS_MODERN_20 && messageClass != CLASS_LEGACY) {
            throw new IOException("unknown Baichuan message class " + Integer.toHexString(messageClass));
        }
        // Pushes and replies are a few KB; a device must not make the Kiosk Satellite process allocate more.
        if (length < 0 || length > MAX_BODY) throw new IOException("bad Baichuan body length " + length);
        if (payloadOffset <= 0 || payloadOffset > length) payloadOffset = length;
        byte[] body = new byte[payloadOffset];
        in.readFully(body);
        byte[] payload = new byte[length - payloadOffset];
        in.readFully(payload);
        return new BcMessage(command, messageId, status, messageClass, headerLength, body, payload);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    private static int readInt(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte(), b2 = in.readUnsignedByte(), b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readShort(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }
}
