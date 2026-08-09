/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

final class RaopCrypto {
    private static final String RSA_MODULUS =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC"
                    + "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR"
                    + "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB"
                    + "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ"
                    + "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh"
                    + "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew==";
    private static final String RSA_EXPONENT = "AQAB";
    private static final OAEPParameterSpec OAEP_SHA1 = new OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);

    private final byte[] keyBytes;
    private final byte[] ivBytes;
    private final SecretKeySpec aesKey;
    private final PublicKey publicKey;

    RaopCrypto(SecureRandom random) throws GeneralSecurityException {
        this(randomBytes(random, 16), randomBytes(random, 16));
    }

    RaopCrypto(byte[] keyBytes, byte[] ivBytes) throws GeneralSecurityException {
        if (keyBytes.length != 16 || ivBytes.length != 16) {
            throw new IllegalArgumentException("RAOP AES key and IV must be 16 bytes");
        }
        this.keyBytes = keyBytes.clone();
        this.ivBytes = ivBytes.clone();
        this.aesKey = new SecretKeySpec(this.keyBytes, "AES");
        this.publicKey = buildPublicKey();
    }

    String aesIvBase64() {
        return Base64.getEncoder().encodeToString(ivBytes);
    }

    String encryptedKeyBase64(SecureRandom random) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA1, random);
        byte[] encrypted = cipher.doFinal(keyBytes);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    String encryptedKeyBase64() throws GeneralSecurityException {
        return encryptedKeyBase64(new SecureRandom());
    }

    int encryptAudioPayload(byte[] packet, int offset, int payloadLength) throws GeneralSecurityException {
        int encryptedLength = payloadLength - (payloadLength % 16);
        if (encryptedLength == 0) {
            return 0;
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(ivBytes.clone()));
        byte[] encrypted = cipher.doFinal(packet, offset, encryptedLength);
        System.arraycopy(encrypted, 0, packet, offset, encryptedLength);
        return encryptedLength;
    }

    byte[] keyBytes() {
        return keyBytes.clone();
    }

    byte[] ivBytes() {
        return ivBytes.clone();
    }

    private static byte[] randomBytes(SecureRandom random, int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static PublicKey buildPublicKey() throws GeneralSecurityException {
        byte[] modulus = Base64.getDecoder().decode(RSA_MODULUS);
        byte[] exponent = Base64.getDecoder().decode(RSA_EXPONENT);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(
                new java.math.BigInteger(1, modulus),
                new java.math.BigInteger(1, exponent));
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}
