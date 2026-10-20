// Deliberately unsafe, non-runnable review fixture. No build or application entry point.
fun review(settings: Settings, call: Call, error: Throwable) {
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = true
    WebView.setWebContentsDebuggingEnabled(true)
    Cipher.getInstance("DES")
    MessageDigest.getInstance("MD5")
    random.setSeed(1234)
    call.respondText(error.stackTraceToString())
    install(CORS) {
        anyHost()
        allowCredentials = true
    }
}
