package pub.airlock.platform

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import pub.airlock.domain.Ed25519Verifier

object AndroidEd25519Verifier : Ed25519Verifier {
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
}
