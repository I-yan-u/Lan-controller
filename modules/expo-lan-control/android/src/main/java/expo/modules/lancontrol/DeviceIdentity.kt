package expo.modules.lancontrol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Per-installation identity: a P-256 key that never leaves the AndroidKeyStore, plus the
 * self-signed certificate the KeyStore issues for it. The SHA-256 of the certificate is the
 * device ID that peers pin.
 */
class DeviceIdentity {
  companion object {
    private const val ALIAS = "lan_identity"
    private const val YEAR_MS = 365L * 24 * 60 * 60 * 1000
  }

  private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  init {
    if (!keyStore.containsAlias(ALIAS)) generate()
  }

  val certificate: X509Certificate = keyStore.getCertificate(ALIAS) as X509Certificate
  val privateKey: PrivateKey = keyStore.getKey(ALIAS, null) as PrivateKey
  val fingerprint: String = Security.sha256Hex(certificate.encoded)

  private fun generate() {
    val now = System.currentTimeMillis()
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      // TLS stacks may sign either pre-hashed data (NONE) or let the KeyStore hash it.
      .setDigests(
        KeyProperties.DIGEST_NONE,
        KeyProperties.DIGEST_SHA256,
        KeyProperties.DIGEST_SHA384,
        KeyProperties.DIGEST_SHA512,
      )
      .setCertificateSubject(X500Principal("CN=lancontrol"))
      .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
      .setCertificateNotBefore(Date(now - YEAR_MS))
      .setCertificateNotAfter(Date(now + 50 * YEAR_MS))
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
      initialize(spec)
      generateKeyPair()
    }
  }

  /** Always presents this installation's key, for both the server and client side of TLS. */
  val keyManager: X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?) = arrayOf(certificate)
    override fun getPrivateKey(alias: String?) = privateKey
  }
}

/**
 * Trust is pinning only: a certificate is accepted iff [accept] approves its fingerprint.
 * Validity dates and issuers are irrelevant because every certificate is self-signed.
 */
class PinnedTrustManager(private val accept: (fingerprint: String) -> Boolean) : X509TrustManager {
  override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
  override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

  private fun check(chain: Array<out X509Certificate>?) {
    val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate presented")
    if (!accept(Security.sha256Hex(leaf.encoded))) throw CertificateException("Certificate is not trusted")
  }
}

object Tls {
  private val PREFERRED = arrayOf("TLSv1.3", "TLSv1.2")

  fun context(identity: DeviceIdentity, trust: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(arrayOf(identity.keyManager), arrayOf(trust), SecureRandom()) }

  fun configure(socket: SSLSocket) {
    socket.enabledProtocols = PREFERRED.filter { it in socket.supportedProtocols }.toTypedArray()
  }

  fun configure(socket: SSLServerSocket) {
    socket.enabledProtocols = PREFERRED.filter { it in socket.supportedProtocols }.toTypedArray()
    socket.needClientAuth = true
  }

  fun peerFingerprint(socket: SSLSocket): String =
    Security.sha256Hex(socket.session.peerCertificates.first().encoded)
}
