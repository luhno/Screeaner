package app.screeaner;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing and encryption shared with the Mac (mac/Sources/ScreeanerCore/SecureChannel.swift).
 *
 * Handshake (all P-256 ECDH, SHA-256):
 * <pre>
 * tablet -> mac  SEC_HELLO  [1][staticT 65][commit 32][idLen][id][nameLen][name]   commit = H(ephT || nonceT)
 * mac -> tablet  SEC_SERVER [staticM 65][ephM 65][nonceM 32][flags][nameLen][name]  flags bit0 = mac knows staticT
 * tablet -> mac  SEC_REVEAL [ephT 65][nonceT 32]
 * </pre>
 * T = H(label || len||hello || len||server || len||reveal); keys = HKDF(ECDH(ephT,ephM) || ECDH(staticT,staticM), salt T).
 * Devices that already trust each other's static key are authenticated by
 * the static ECDH. Otherwise both screens show a 6-digit code derived from T;
 * the commitment stops a man in the middle from choosing keys that collide.
 * Then: tablet -> SEC_CONFIRM HMAC(kc, "tablet"||T), mac -> SEC_OK HMAC(kc, "mac"||T).
 *
 * Afterwards every message is ENCRYPTED: AES-256-GCM of [type][payload],
 * one key per direction, nonce = 4 zero bytes + 64-bit message counter.
 * UDP video frames use a third key with nonce = FFFFFFFF + 64-bit frame id.
 */
final class SecureChannel {
    static final int PUBLIC_KEY_SIZE = 65;
    static final int NONCE_SIZE = 32;
    private static final byte[] TRANSCRIPT_LABEL = ascii("Screeaner handshake v1");
    private static final byte[] KEYS_LABEL = ascii("Screeaner session v1");
    private static final byte[] SAS_LABEL = ascii("Screeaner SAS v1");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureChannel() {}

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // region Keys

    static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    static ECParameterSpec curve() throws GeneralSecurityException {
        return ((ECPublicKey) generateKeyPair().getPublic()).getParams();
    }

    /** Uncompressed X9.63 point: 04 || x || y. */
    static byte[] encodePublic(PublicKey key) {
        ECPoint point = ((ECPublicKey) key).getW();
        byte[] out = new byte[PUBLIC_KEY_SIZE];
        out[0] = 4;
        copyUnsigned(point.getAffineX(), out, 1);
        copyUnsigned(point.getAffineY(), out, 33);
        return out;
    }

    private static void copyUnsigned(BigInteger value, byte[] out, int offset) {
        byte[] raw = value.toByteArray();
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, offset + 32 - length, length);
    }

    static PublicKey decodePublic(byte[] data, int offset) throws GeneralSecurityException {
        if (data.length < offset + PUBLIC_KEY_SIZE || data[offset] != 4) throw new GeneralSecurityException("bad key");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(data, offset + 1, offset + 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(data, offset + 33, offset + 65));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve()));
    }

    static PrivateKey privateFromScalar(byte[] scalar) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve()));
    }

    static PrivateKey privateFromPkcs8(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    static byte[] ecdh(PrivateKey mine, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(mine);
        agreement.doPhase(theirs, true);
        return agreement.generateSecret();
    }

    static byte[] randomBytes(int count) {
        byte[] out = new byte[count];
        RANDOM.nextBytes(out);
        return out;
    }

    // endregion

    // region Hashing

    static byte[] sha256(byte[]... parts) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) digest.update(part);
        return digest.digest();
    }

    static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : parts) mac.update(part);
        return mac.doFinal();
    }

    /** RFC 5869. */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            previous = hmac(prk, previous, info, new byte[] {(byte) i});
            out.write(previous, 0, previous.length);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    static byte[] transcript(byte[] hello, byte[] server, byte[] reveal) throws GeneralSecurityException {
        return sha256(TRANSCRIPT_LABEL, u32(hello.length), hello, u32(server.length), server, u32(reveal.length), reveal);
    }

    private static byte[] u32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    /** Six-digit comparison code, e.g. "953187". */
    static String sas(byte[] transcript) throws GeneralSecurityException {
        byte[] hash = sha256(SAS_LABEL, transcript);
        long value = ByteBuffer.wrap(hash, 0, 4).getInt() & 0xFFFFFFFFL;
        return String.format(java.util.Locale.US, "%06d", value % 1_000_000L);
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    // endregion

    // region Handshake messages

    static byte[] buildHello(byte[] staticPublic, byte[] commit, String deviceId, String name) {
        byte[] id = shortUtf8(deviceId);
        byte[] label = shortUtf8(name);
        ByteBuffer buffer = ByteBuffer.allocate(1 + PUBLIC_KEY_SIZE + 32 + 1 + id.length + 1 + label.length);
        buffer.put((byte) 1).put(staticPublic).put(commit);
        buffer.put((byte) id.length).put(id).put((byte) label.length).put(label);
        return buffer.array();
    }

    static byte[] buildReveal(byte[] ephemeralPublic, byte[] nonce) {
        return ByteBuffer.allocate(PUBLIC_KEY_SIZE + NONCE_SIZE).put(ephemeralPublic).put(nonce).array();
    }

    /** Parsed SEC_SERVER. */
    static final class Server {
        final byte[] staticPublic;
        final byte[] ephemeralPublic;
        final byte[] nonce;
        final boolean knowsTablet;
        final String name;

        Server(byte[] payload) throws GeneralSecurityException {
            int fixed = PUBLIC_KEY_SIZE * 2 + NONCE_SIZE + 2;
            if (payload.length < fixed) throw new GeneralSecurityException("short server message");
            staticPublic = Arrays.copyOfRange(payload, 0, PUBLIC_KEY_SIZE);
            ephemeralPublic = Arrays.copyOfRange(payload, PUBLIC_KEY_SIZE, PUBLIC_KEY_SIZE * 2);
            nonce = Arrays.copyOfRange(payload, PUBLIC_KEY_SIZE * 2, PUBLIC_KEY_SIZE * 2 + NONCE_SIZE);
            knowsTablet = (payload[fixed - 2] & 1) != 0;
            int nameLength = payload[fixed - 1] & 0xFF;
            if (payload.length < fixed + nameLength) throw new GeneralSecurityException("short server name");
            name = new String(payload, fixed, nameLength, StandardCharsets.UTF_8);
        }
    }

    private static byte[] shortUtf8(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= 255 ? bytes : Arrays.copyOf(bytes, 255);
    }

    // endregion

    /** Keys and counters of an established channel. Not thread-safe: one sender thread, one receiver thread. */
    static final class Session {
        final byte[] transcript;
        final String sas;
        private final byte[] sendKey;
        private final byte[] receiveKey;
        private final byte[] confirmKey;
        private final byte[] videoKey;
        private long sendCounter;
        private long receiveCounter;
        private final Cipher sendCipher;
        private final Cipher receiveCipher;
        private final Cipher videoCipher;

        /** {@code tabletSide}: true on Android (sends with the tablet->mac key). */
        Session(byte[] ee, byte[] ss, byte[] transcript, boolean tabletSide) throws GeneralSecurityException {
            this.transcript = transcript;
            byte[] ikm = new byte[ee.length + ss.length];
            System.arraycopy(ee, 0, ikm, 0, ee.length);
            System.arraycopy(ss, 0, ikm, ee.length, ss.length);
            byte[] okm = hkdf(ikm, transcript, KEYS_LABEL, 128);
            byte[] t2m = Arrays.copyOfRange(okm, 0, 32);
            byte[] m2t = Arrays.copyOfRange(okm, 32, 64);
            confirmKey = Arrays.copyOfRange(okm, 64, 96);
            videoKey = Arrays.copyOfRange(okm, 96, 128);
            sendKey = tabletSide ? t2m : m2t;
            receiveKey = tabletSide ? m2t : t2m;
            sas = SecureChannel.sas(transcript);
            sendCipher = Cipher.getInstance("AES/GCM/NoPadding");
            receiveCipher = Cipher.getInstance("AES/GCM/NoPadding");
            videoCipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        byte[] tabletConfirmation() throws GeneralSecurityException {
            return hmac(confirmKey, ascii("tablet"), transcript);
        }

        byte[] macConfirmation() throws GeneralSecurityException {
            return hmac(confirmKey, ascii("mac"), transcript);
        }

        private static byte[] nonce(int prefix, long counter) {
            return ByteBuffer.allocate(12).putInt(prefix).putLong(counter).array();
        }

        /** Payload of an ENCRYPTED message carrying (type, payload). */
        byte[] seal(int type, byte[] payload, int offset, int length) throws GeneralSecurityException {
            sendCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sendKey, "AES"),
                new GCMParameterSpec(128, nonce(0, sendCounter++)));
            byte[] plain = new byte[1 + length];
            plain[0] = (byte) type;
            System.arraycopy(payload, offset, plain, 1, length);
            return sendCipher.doFinal(plain);
        }

        /** Opens an ENCRYPTED payload; returns [type][payload]. Throws on tampering or replay. */
        byte[] open(byte[] data, int offset, int length) throws GeneralSecurityException {
            receiveCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(receiveKey, "AES"),
                new GCMParameterSpec(128, nonce(0, receiveCounter)));
            byte[] plain = receiveCipher.doFinal(data, offset, length);
            receiveCounter++;
            if (plain.length < 1) throw new GeneralSecurityException("empty message");
            return plain;
        }

        /** Decrypts one rebuilt UDP video frame. */
        byte[] openVideo(long frameId, byte[] data, int length) throws GeneralSecurityException {
            videoCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(videoKey, "AES"),
                new GCMParameterSpec(128, nonce(0xFFFFFFFF, frameId)));
            return videoCipher.doFinal(data, 0, length);
        }

        /** For tests: the Mac side of the video key. */
        byte[] sealVideo(long frameId, byte[] plain) throws GeneralSecurityException {
            videoCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(videoKey, "AES"),
                new GCMParameterSpec(128, nonce(0xFFFFFFFF, frameId)));
            return videoCipher.doFinal(plain);
        }
    }
}
