package org.vdcapps.issuer.infrastructure.multipaz

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 発行者（Issuer）の署名鍵と X.509 証明書を管理する。
 * PKCS12 キーストアに永続化し、起動時に存在しなければ自動生成する。
 *
 * 生成する鍵: P-256 (ES256)、自己署名証明書（有効期間 10 年）
 */
class IssuerKeyStore(
    private val keyStorePath: String,
    private val keyStorePassword: String,
) {
    private val logger = LoggerFactory.getLogger(IssuerKeyStore::class.java)
    private val keyAlias = "issuer-signing-key"

    val privateKey: PrivateKey
    val certificate: X509Certificate
    val certChainDer: List<ByteArray>

    init {
        val (pk, cert) = loadOrCreate()
        privateKey = pk
        certificate = cert
        certChainDer = listOf(cert.encoded)
        logger.info(
            "発行者証明書: subject=${cert.subjectX500Principal.name}, " +
                "serial=${cert.serialNumber}, expires=${cert.notAfter}",
        )
    }

    private fun loadOrCreate(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        val file = File(keyStorePath)

        if (file.exists() && file.length() > 0) {
            file.inputStream().use { ks.load(it, keyStorePassword.toCharArray()) }
            val cert = ks.getCertificate(keyAlias) as X509Certificate
            val pk = ks.getKey(keyAlias, keyStorePassword.toCharArray()) as PrivateKey
            logger.info("既存の発行者キーストアを読み込みました: $keyStorePath")
            return pk to cert
        }

        logger.info("発行者キーを生成します: $keyStorePath")
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Multipaz Issuer, O=Multipaz, C=JP")

        val certHolder =
            X509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now),
                Date(now + TEN_YEARS_MILLIS),
                subject,
                SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            }.build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))

        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        ks.load(null, keyStorePassword.toCharArray())
        ks.setKeyEntry(keyAlias, keyPair.private, keyStorePassword.toCharArray(), arrayOf(cert))
        file.parentFile?.mkdirs()
        file.outputStream().use { ks.store(it, keyStorePassword.toCharArray()) }

        return keyPair.private to cert
    }

    companion object {
        private const val TEN_YEARS_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
