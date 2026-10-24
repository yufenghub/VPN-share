package dev.shizzi.adb;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/** ASN.1 construction uses BC; signing and parsing use the platform's JCA providers. */
public final class AdbCertificate {
    private AdbCertificate() {}

    public static X509Certificate create(KeyPair pair, long now)
            throws GeneralSecurityException, OperatorCreationException, IOException {
        X500Name name = new X500Name("CN=VPNShare ADB");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, new BigInteger(128, new SecureRandom()).setBit(0),
                new Date(now - 86400000L), new Date(now + 30L * 365 * 86400000L), name, pair.getPublic());
        // An explicitly constructed BC provider discovers algorithms through reflection.
        // R8 can remove those implementations. Android already supplies SHA256withRSA.
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        byte[] encoded = builder.build(signer).getEncoded();
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
        certificate.verify(pair.getPublic());
        return certificate;
    }
}
