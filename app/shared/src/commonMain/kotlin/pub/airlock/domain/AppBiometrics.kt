package pub.airlock.domain

/** Native biometric UI authenticates only app access; hardware PINs never enter this API. */
interface AppBiometrics {
    fun available(): Boolean

    fun authenticate(reason: String, pinLabel: String, completion: (Boolean) -> Unit)
}

object NoAppBiometrics : AppBiometrics {
    override fun available() = false

    override fun authenticate(reason: String, pinLabel: String, completion: (Boolean) -> Unit) =
        completion(false)
}
