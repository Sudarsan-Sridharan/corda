package net.corda.node.services.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.copyTo
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.utilities.loggerFor
import net.corda.node.utilities.*
import net.corda.nodeapi.config.SSLConfiguration
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.NameConstraints
import java.nio.file.Path
import java.security.KeyStore

fun configOf(vararg pairs: Pair<String, Any?>): Config = ConfigFactory.parseMap(mapOf(*pairs))
operator fun Config.plus(overrides: Map<String, Any?>): Config = ConfigFactory.parseMap(overrides).withFallback(this)

object ConfigHelper {
    private val log = loggerFor<ConfigHelper>()

    fun loadConfig(baseDirectory: Path,
                   configFile: Path = baseDirectory / "node.conf",
                   allowMissingConfig: Boolean = false,
                   configOverrides: Config = ConfigFactory.empty()): Config {
        val parseOptions = ConfigParseOptions.defaults()
        val defaultConfig = ConfigFactory.parseResources("reference.conf", parseOptions.setAllowMissing(false))
        val appConfig = ConfigFactory.parseFile(configFile.toFile(), parseOptions.setAllowMissing(allowMissingConfig))
        val finalConfig = configOf(
                // Add substitution values here
                "basedir" to baseDirectory.toString())
                .withFallback(configOverrides)
                .withFallback(appConfig)
                .withFallback(defaultConfig)
                .resolve()
        log.info("Config:\n${finalConfig.root().render(ConfigRenderOptions.defaults())}")
        return finalConfig
    }
}

/**
 * Strictly for dev only automatically construct a server certificate/private key signed from
 * the CA certs in Node resources. Then provision KeyStores into certificates folder under node path.
 */
fun NodeConfiguration.configureWithDevSSLCertificate() = configureDevKeyAndTrustStores(myLegalName)

fun SSLConfiguration.configureDevKeyAndTrustStores(myLegalName: CordaX500Name) {
    certificatesDirectory.createDirectories()
    if (!trustStoreFile.exists()) {
        javaClass.classLoader.getResourceAsStream("net/corda/node/internal/certificates/cordatruststore.jks").copyTo(trustStoreFile)
    }
    if (!sslKeystore.exists() || !nodeKeystore.exists()) {
        val caKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("net/corda/node/internal/certificates/cordadevcakeys.jks"), "cordacadevpass")
        createKeystoreForCordaNode(sslKeystore, nodeKeystore, keyStorePassword, keyStorePassword, caKeyStore, "cordacadevkeypass", myLegalName)
    }
}

/**
 * An all in wrapper to manufacture a server certificate and keys all stored in a KeyStore suitable for running TLS on the local machine.
 * @param sslKeyStorePath KeyStore path to save ssl key and cert to.
 * @param clientCAKeystorePath KeyStore path to save client CA key and cert to.
 * @param storePassword access password for KeyStore.
 * @param keyPassword PrivateKey access password for the generated keys.
 * It is recommended that this is the same as the storePassword as most TLS libraries assume they are the same.
 * @param caKeyStore KeyStore containing CA keys generated by createCAKeyStoreAndTrustStore.
 * @param caKeyPassword password to unlock private keys in the CA KeyStore.
 * @return The KeyStore created containing a private key, certificate chain and root CA public cert for use in TLS applications.
 */
fun createKeystoreForCordaNode(sslKeyStorePath: Path,
                               clientCAKeystorePath: Path,
                               storePassword: String,
                               keyPassword: String,
                               caKeyStore: KeyStore,
                               caKeyPassword: String,
                               legalName: CordaX500Name,
                               signatureScheme: SignatureScheme = X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME) {

    val rootCACert = caKeyStore.getX509Certificate(X509Utilities.CORDA_ROOT_CA)
    val (intermediateCACert, intermediateCAKeyPair) = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, caKeyPassword)

    val clientKey = Crypto.generateKeyPair(signatureScheme)
    val clientName = legalName.copy(CN = null)

    val nameConstraints = NameConstraints(arrayOf(GeneralSubtree(GeneralName(GeneralName.directoryName, clientName.x500Name))), arrayOf())
    val clientCACert = X509Utilities.createCertificate(CertificateType.INTERMEDIATE_CA,
            intermediateCACert,
            intermediateCAKeyPair,
            clientName.copy(CN = X509Utilities.CORDA_CLIENT_CA_CN).x500Name,
            clientKey.public,
            nameConstraints = nameConstraints)

    val tlsKey = Crypto.generateKeyPair(signatureScheme)
    val clientTLSCert = X509Utilities.createCertificate(CertificateType.TLS, clientCACert, clientKey, clientName.x500Name, tlsKey.public)

    val keyPass = keyPassword.toCharArray()

    val clientCAKeystore = loadOrCreateKeyStore(clientCAKeystorePath, storePassword)
    clientCAKeystore.addOrReplaceKey(
            X509Utilities.CORDA_CLIENT_CA,
            clientKey.private,
            keyPass,
            arrayOf(clientCACert, intermediateCACert, rootCACert))
    clientCAKeystore.save(clientCAKeystorePath, storePassword)

    val tlsKeystore = loadOrCreateKeyStore(sslKeyStorePath, storePassword)
    tlsKeystore.addOrReplaceKey(
            X509Utilities.CORDA_CLIENT_TLS,
            tlsKey.private,
            keyPass,
            arrayOf(clientTLSCert, clientCACert, intermediateCACert, rootCACert))
    tlsKeystore.save(sslKeyStorePath, storePassword)
}
