package dev.shizzi.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.*
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate)

/** Identity is reused across process restarts and app updates, never regenerated on connection errors. */
class AdbIdentityStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "adb-identity.v1"))
    private val alias = "${context.packageName}.adb.identity.aes.v1"

    @Synchronized
    fun loadOrCreate(): AdbIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        val saved = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        check(existing != null || !saved) { "ADB 密钥不可用；请清除应用数据后重新配对" }
        val master = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
        if (saved) {
            return decode(AdbIdentityCrypto.open(master, file.readFully()))
        }
        val generated = generate()
        val encrypted = AdbIdentityCrypto.seal(master, encode(generated))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        return generated
    }

    private fun generate(): AdbIdentity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val certificate = AdbCertificate.create(pair, System.currentTimeMillis())
        return AdbIdentity(pair.private, certificate)
    }

    private fun encode(identity: AdbIdentity): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            val key = identity.privateKey.encoded
            val cert = identity.certificate.encoded
            out.writeInt(key.size); out.write(key); out.writeInt(cert.size); out.write(cert)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): AdbIdentity = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        fun block(): ByteArray {
            val size = input.readInt()
            require(size in 1..16384)
            return ByteArray(size).also(input::readFully)
        }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(block()))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(block()))
        require(input.available() == 0)
        AdbIdentity(privateKey, certificate)
    }
}
