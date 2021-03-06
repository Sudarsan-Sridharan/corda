package net.corda.node.utilities

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import net.corda.core.crypto.random63BitValue
import net.corda.core.utilities.cert
import net.corda.core.utilities.days
import net.corda.core.utilities.millis
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemReader
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStream
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.security.cert.*
import java.security.cert.Certificate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

object X509Utilities {
    val DEFAULT_IDENTITY_SIGNATURE_SCHEME = Crypto.EDDSA_ED25519_SHA512
    val DEFAULT_TLS_SIGNATURE_SCHEME = Crypto.ECDSA_SECP256R1_SHA256

    // Aliases for private keys and certificates.
    val CORDA_ROOT_CA = "cordarootca"
    val CORDA_INTERMEDIATE_CA = "cordaintermediateca"
    val CORDA_CLIENT_TLS = "cordaclienttls"
    val CORDA_CLIENT_CA = "cordaclientca"

    val CORDA_CLIENT_CA_CN = "Corda Client CA Certificate"

    private val DEFAULT_VALIDITY_WINDOW = Pair(0.millis, 3650.days)
    /**
     * Helper function to return the latest out of an instant and an optional date.
     */
    private fun max(first: Instant, second: Date?): Date {
        return if (second != null && second.time > first.toEpochMilli())
            second
        else
            Date(first.toEpochMilli())
    }

    /**
     * Helper function to return the earliest out of an instant and an optional date.
     */
    private fun min(first: Instant, second: Date?): Date {
        return if (second != null && second.time < first.toEpochMilli())
            second
        else
            Date(first.toEpochMilli())
    }

    /**
     * Helper method to get a notBefore and notAfter pair from current day bounded by parent certificate validity range.
     * @param before duration to roll back returned start date relative to current date.
     * @param after duration to roll forward returned end date relative to current date.
     * @param parent if provided certificate whose validity should bound the date interval returned.
     */
    fun getCertificateValidityWindow(before: Duration, after: Duration, parent: X509CertificateHolder? = null): Pair<Date, Date> {
        val startOfDayUTC = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val notBefore = max(startOfDayUTC - before, parent?.notBefore)
        val notAfter = min(startOfDayUTC + after, parent?.notAfter)
        return Pair(notBefore, notAfter)
    }

    /*
     * Create a de novo root self-signed X509 v3 CA cert.
     */
    @JvmStatic
    fun createSelfSignedCACertificate(subject: X500Name, keyPair: KeyPair, validityWindow: Pair<Duration, Duration> = DEFAULT_VALIDITY_WINDOW): X509CertificateHolder {
        val window = getCertificateValidityWindow(validityWindow.first, validityWindow.second)
        return createCertificate(CertificateType.ROOT_CA, subject, keyPair, subject, keyPair.public, window)
    }

    /**
     * Create a X509 v3 cert.
     * @param issuerCertificate The Public certificate of the root CA above this used to sign it.
     * @param issuerKeyPair The KeyPair of the root CA above this used to sign it.
     * @param subject subject of the generated certificate.
     * @param subjectPublicKey subject 's public key.
     * @param validityWindow The certificate's validity window. Default to [DEFAULT_VALIDITY_WINDOW] if not provided.
     * @return A data class is returned containing the new intermediate CA Cert and its KeyPair for signing downstream certificates.
     * Note the generated certificate tree is capped at max depth of 1 below this to be in line with commercially available certificates.
     */
    @JvmStatic
    fun createCertificate(certificateType: CertificateType,
                          issuerCertificate: X509CertificateHolder, issuerKeyPair: KeyPair,
                          subject: X500Name, subjectPublicKey: PublicKey,
                          validityWindow: Pair<Duration, Duration> = DEFAULT_VALIDITY_WINDOW,
                          nameConstraints: NameConstraints? = null): X509CertificateHolder {
        val window = getCertificateValidityWindow(validityWindow.first, validityWindow.second, issuerCertificate)
        return createCertificate(certificateType, issuerCertificate.subject, issuerKeyPair, subject, subjectPublicKey, window, nameConstraints)
    }

    @Throws(CertPathValidatorException::class)
    fun validateCertificateChain(trustedRoot: X509CertificateHolder, vararg certificates: Certificate) {
        require(certificates.isNotEmpty()) { "Certificate path must contain at least one certificate" }
        val certFactory = CertificateFactory.getInstance("X509")
        val params = PKIXParameters(setOf(TrustAnchor(trustedRoot.cert, null)))
        params.isRevocationEnabled = false
        val certPath = certFactory.generateCertPath(certificates.toList())
        val pathValidator = CertPathValidator.getInstance("PKIX")
        pathValidator.validate(certPath, params)
    }

    /**
     * Helper method to store a .pem/.cer format file copy of a certificate if required for import into a PC/Mac, or for inspection.
     * @param x509Certificate certificate to save.
     * @param filename Target filename.
     */
    @JvmStatic
    fun saveCertificateAsPEMFile(x509Certificate: X509CertificateHolder, filename: Path) {
        FileWriter(filename.toFile()).use {
            JcaPEMWriter(it).use {
                it.writeObject(x509Certificate)
            }
        }
    }

    /**
     * Helper method to load back a .pem/.cer format file copy of a certificate.
     * @param filename Source filename.
     * @return The X509Certificate that was encoded in the file.
     */
    @JvmStatic
    fun loadCertificateFromPEMFile(filename: Path): X509CertificateHolder {
        val reader = PemReader(FileReader(filename.toFile()))
        val pemObject = reader.readPemObject()
        val cert = X509CertificateHolder(pemObject.content)
        return cert.apply {
            isValidOn(Date())
        }
    }

    /**
     * Build a partial X.509 certificate ready for signing.
     *
     * @param issuer name of the issuing entity.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createCertificate(certificateType: CertificateType, issuer: X500Name,
                          subject: X500Name, subjectPublicKey: PublicKey,
                          validityWindow: Pair<Date, Date>,
                          nameConstraints: NameConstraints? = null): X509v3CertificateBuilder {

        val serial = BigInteger.valueOf(random63BitValue())
        val keyPurposes = DERSequence(ASN1EncodableVector().apply { certificateType.purposes.forEach { add(it) } })
        val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(ASN1Sequence.getInstance(subjectPublicKey.encoded))

        val builder = JcaX509v3CertificateBuilder(issuer, serial, validityWindow.first, validityWindow.second, subject, subjectPublicKey)
                .addExtension(Extension.subjectKeyIdentifier, false, BcX509ExtensionUtils().createSubjectKeyIdentifier(subjectPublicKeyInfo))
                .addExtension(Extension.basicConstraints, certificateType.isCA, BasicConstraints(certificateType.isCA))
                .addExtension(Extension.keyUsage, false, certificateType.keyUsage)
                .addExtension(Extension.extendedKeyUsage, false, keyPurposes)

        if (nameConstraints != null) {
            builder.addExtension(Extension.nameConstraints, true, nameConstraints)
        }
        return builder
    }

    /**
     * Build and sign an X.509 certificate with the given signer.
     *
     * @param issuer name of the issuing entity.
     * @param issuerSigner content signer to sign the certificate with.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createCertificate(certificateType: CertificateType, issuer: X500Name, issuerSigner: ContentSigner,
                          subject: X500Name, subjectPublicKey: PublicKey,
                          validityWindow: Pair<Date, Date>,
                          nameConstraints: NameConstraints? = null): X509CertificateHolder {
        val builder = createCertificate(certificateType, issuer, subject, subjectPublicKey, validityWindow, nameConstraints)
        return builder.build(issuerSigner).apply {
            require(isValidOn(Date()))
        }
    }

    /**
     * Build and sign an X.509 certificate with CA cert private key.
     *
     * @param issuer name of the issuing entity.
     * @param issuerKeyPair the public & private key to sign the certificate with.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createCertificate(certificateType: CertificateType, issuer: X500Name, issuerKeyPair: KeyPair,
                          subject: X500Name, subjectPublicKey: PublicKey,
                          validityWindow: Pair<Date, Date>,
                          nameConstraints: NameConstraints? = null): X509CertificateHolder {

        val signatureScheme = Crypto.findSignatureScheme(issuerKeyPair.private)
        val provider = Crypto.findProvider(signatureScheme.providerName)
        val builder = createCertificate(certificateType, issuer, subject, subjectPublicKey, validityWindow, nameConstraints)

        val signer = ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
        return builder.build(signer).apply {
            require(isValidOn(Date()))
            require(isSignatureValid(JcaContentVerifierProviderBuilder().build(issuerKeyPair.public)))
        }
    }

    /**
     * Create certificate signing request using provided information.
     */
    fun createCertificateSigningRequest(subject: X500Name, email: String, keyPair: KeyPair, signatureScheme: SignatureScheme): PKCS10CertificationRequest {
        val signer = ContentSignerBuilder.build(signatureScheme, keyPair.private, Crypto.findProvider(signatureScheme.providerName))
        return JcaPKCS10CertificationRequestBuilder(subject, keyPair.public).addAttribute(BCStyle.E, DERUTF8String(email)).build(signer)
    }

    fun createCertificateSigningRequest(subject: X500Name, email: String, keyPair: KeyPair) = createCertificateSigningRequest(subject, email, keyPair, DEFAULT_TLS_SIGNATURE_SCHEME)
}


class CertificateStream(val input: InputStream) {
    private val certificateFactory = CertificateFactory.getInstance("X.509")

    fun nextCertificate(): X509Certificate = certificateFactory.generateCertificate(input) as X509Certificate
}

enum class CertificateType(val keyUsage: KeyUsage, vararg val purposes: KeyPurposeId, val isCA: Boolean) {
    ROOT_CA(KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign), KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth, KeyPurposeId.anyExtendedKeyUsage, isCA = true),
    INTERMEDIATE_CA(KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign), KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth, KeyPurposeId.anyExtendedKeyUsage, isCA = true),
    CLIENT_CA(KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign), KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth, KeyPurposeId.anyExtendedKeyUsage, isCA = true),
    TLS(KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment or KeyUsage.keyAgreement), KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth, KeyPurposeId.anyExtendedKeyUsage, isCA = false),
    // TODO: Identity certs should have only limited depth (i.e. 1) CA signing capability, with tight name constraints
    IDENTITY(KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign), KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth, KeyPurposeId.anyExtendedKeyUsage, isCA = true)
}
