package app.screeaner;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import org.junit.Test;

/**
 * Known-answer test computed independently with Python's `cryptography`
 * package; mac/Tests/ScreeanerCoreTests checks the same values in Swift.
 */
public class SecureChannelTest {
    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] filled(int value) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static PrivateKey key(String scalarHex) throws GeneralSecurityException {
        return SecureChannel.privateFromScalar(hex(scalarHex));
    }

    static PublicKey pub(PrivateKey key) throws GeneralSecurityException {
        // Derive the public point by agreeing with the generator: simplest is a KeyFactory round trip.
        java.security.interfaces.ECPrivateKey ec = (java.security.interfaces.ECPrivateKey) key;
        java.security.spec.ECPoint g = ec.getParams().getGenerator();
        java.math.BigInteger p = ((java.security.spec.ECFieldFp) ec.getParams().getCurve().getField()).getP();
        java.math.BigInteger a = ec.getParams().getCurve().getA();
        java.security.spec.ECPoint q = multiply(g, ec.getS(), p, a);
        return java.security.KeyFactory.getInstance("EC")
            .generatePublic(new java.security.spec.ECPublicKeySpec(q, ec.getParams()));
    }

    private static java.security.spec.ECPoint multiply(java.security.spec.ECPoint g, java.math.BigInteger k,
                                                       java.math.BigInteger p, java.math.BigInteger a) {
        java.security.spec.ECPoint r = java.security.spec.ECPoint.POINT_INFINITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r, p, a);
            if (k.testBit(i)) r = add(r, g, p, a);
        }
        return r;
    }

    private static java.security.spec.ECPoint add(java.security.spec.ECPoint s, java.security.spec.ECPoint t,
                                                  java.math.BigInteger p, java.math.BigInteger a) {
        if (s.equals(java.security.spec.ECPoint.POINT_INFINITY)) return t;
        if (t.equals(java.security.spec.ECPoint.POINT_INFINITY)) return s;
        java.math.BigInteger x1 = s.getAffineX(), y1 = s.getAffineY(), x2 = t.getAffineX(), y2 = t.getAffineY();
        java.math.BigInteger l;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) return java.security.spec.ECPoint.POINT_INFINITY;
            l = x1.pow(2).multiply(java.math.BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(p)).mod(p);
        } else {
            l = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        }
        java.math.BigInteger x3 = l.pow(2).subtract(x1).subtract(x2).mod(p);
        java.math.BigInteger y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new java.security.spec.ECPoint(x3, y3);
    }

    @Test public void knownAnswer() throws Exception {
        PrivateKey sT = key("ab2e00154017c257fd8761a83f64aa750819db6a8e083104e8605785928fb24d");
        PrivateKey eT = key("8c933dcfc1daee1a0c7a465bbe3ed3d559ad3b8bea7be809557855355b42ee80");
        PrivateKey sM = key("c34730b39aeb04c5bee2ff40a18b43004e731927a18c366a94df82cf90871dd1");
        PrivateKey eM = key("c51349aa7d9eface324a875250d53ce05ba301692442be56f8b6727ea775fa3d");
        byte[] nT = filled(0x11);
        byte[] nM = filled(0x22);
        byte[] ephT = SecureChannel.encodePublic(pub(eT));
        byte[] commit = SecureChannel.sha256(ephT, nT);
        byte[] hello = SecureChannel.buildHello(SecureChannel.encodePublic(pub(sT)), commit, "dev-1", "Tab");
        assertEquals(108, hello.length);
        byte[] serverPayload = concat(SecureChannel.encodePublic(pub(sM)), SecureChannel.encodePublic(pub(eM)), nM,
            new byte[] {0, 3}, "Mac".getBytes(StandardCharsets.UTF_8));
        SecureChannel.Server server = new SecureChannel.Server(serverPayload);
        assertEquals("Mac", server.name);
        byte[] reveal = SecureChannel.buildReveal(ephT, nT);
        byte[] t = SecureChannel.transcript(hello, serverPayload, reveal);
        assertEquals("41e1daa08ea71a0fed09b893aaac71028c4d9d98f67aa48d2ba14745b1dedce9", hex(t));

        byte[] ee = SecureChannel.ecdh(eT, SecureChannel.decodePublic(server.ephemeralPublic, 0));
        byte[] ss = SecureChannel.ecdh(sT, SecureChannel.decodePublic(server.staticPublic, 0));
        SecureChannel.Session tablet = new SecureChannel.Session(ee, ss, t, true);
        assertEquals("953187", tablet.sas);
        assertEquals("c113a3d1eb54ae1d1d9a1d7146104fa7acb6dd7fbda1825e1c3aa95efe8b1faa", hex(tablet.tabletConfirmation()));
        assertEquals("da83f0f334c53fac2e66801821ab7214a34e99bf02c71122145bf45932d9548e", hex(tablet.macConfirmation()));
        byte[] hi = "hello".getBytes(StandardCharsets.US_ASCII);
        assertEquals("baf9e6cfa474b08d7e6fb8dae81ac5dd7a84dfd0ef6b", hex(tablet.seal(1, hi, 0, hi.length)));
        assertEquals("6055aeefc0c479b35339cf1cfd64e25597a2eb134e",
            hex(tablet.sealVideo(5, "frame".getBytes(StandardCharsets.US_ASCII))));

        // The Mac's second message (counter 1) opens on the tablet; replaying counter 0 fails.
        byte[] msg1 = hex("2f94b7a3100a949c90823c20ac20eefbbd368bfa44d2");
        try {
            tablet.open(msg1, 0, msg1.length);
            fail("counter 0 expected");
        } catch (GeneralSecurityException expected) {
        }
        SecureChannel.Session mac = new SecureChannel.Session(
            SecureChannel.ecdh(eM, SecureChannel.decodePublic(ephT, 0)),
            SecureChannel.ecdh(sM, pub(sT)), t, false);
        byte[] first = mac.seal(2, new byte[0], 0, 0);
        byte[] opened0 = tablet.open(first, 0, first.length);
        assertEquals(2, opened0[0]);
        byte[] opened1 = tablet.open(msg1, 0, msg1.length);
        assertArrayEquals(concat(new byte[] {3}, "video".getBytes(StandardCharsets.US_ASCII)), opened1);
    }

    @Test public void roundTripWithFreshKeys() throws Exception {
        KeyPair sT = SecureChannel.generateKeyPair(), eT = SecureChannel.generateKeyPair();
        KeyPair sM = SecureChannel.generateKeyPair(), eM = SecureChannel.generateKeyPair();
        byte[] t = SecureChannel.sha256("x".getBytes(StandardCharsets.US_ASCII));
        SecureChannel.Session tablet = new SecureChannel.Session(
            SecureChannel.ecdh(eT.getPrivate(), eM.getPublic()), SecureChannel.ecdh(sT.getPrivate(), sM.getPublic()), t, true);
        SecureChannel.Session mac = new SecureChannel.Session(
            SecureChannel.ecdh(eM.getPrivate(), eT.getPublic()), SecureChannel.ecdh(sM.getPrivate(), sT.getPublic()), t, false);
        assertEquals(tablet.sas, mac.sas);
        byte[] msg = new byte[100_000];
        for (int i = 0; i < 5; i++) {
            byte[] sealed = tablet.seal(4, msg, 0, msg.length);
            assertEquals(msg.length + 1, mac.open(sealed, 0, sealed.length).length);
        }
        byte[] sealed = mac.seal(9, msg, 0, 10);
        sealed[3] ^= 1;
        try {
            tablet.open(sealed, 0, sealed.length);
            fail("tampering must be detected");
        } catch (GeneralSecurityException expected) {
        }
        // Encoding round trip of public keys.
        byte[] encoded = SecureChannel.encodePublic(sT.getPublic());
        assertArrayEquals(encoded, SecureChannel.encodePublic(SecureChannel.decodePublic(encoded, 0)));
        assertTrue(SecureChannel.sas(t).matches("\\d{6}"));
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
