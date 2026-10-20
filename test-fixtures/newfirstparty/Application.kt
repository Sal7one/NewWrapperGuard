// STATIC SCANNER FIXTURE. No main function, build files or application startup.
// Inspect as source only; intentionally contains unsafe review examples.
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

fun configureCors() {
    anyHost() // nosemgrep: fixture suppression must not authorize trust
}

class TrustAll : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) { }
}
