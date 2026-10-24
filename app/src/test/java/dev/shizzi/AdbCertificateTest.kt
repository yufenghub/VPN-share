package dev.shizzi

import dev.shizzi.adb.AdbCertificate
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class AdbCertificateTest {
    @Test fun `platform signer creates a valid RSA identity certificate`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val certificate = AdbCertificate.create(pair, now)
        certificate.checkValidity(Date(now))
        certificate.verify(pair.public)
        assertEquals("1.2.840.113549.1.1.11", certificate.sigAlgOID)
        assertArrayEquals(pair.public.encoded, certificate.publicKey.encoded)
        assertEquals("CN=VPNShare ADB", certificate.subjectX500Principal.name)
    }
    @Test fun `serialized key and certificate remain usable by platform signatures`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = AdbCertificate.create(pair, System.currentTimeMillis())
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pair.private.encoded))
        val decoded = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate.encoded))
        val challenge = byteArrayOf(1, 3, 5, 7, 9)
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(challenge) }.sign()
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(decoded.publicKey); update(challenge) }.verify(signature))
    }
}
