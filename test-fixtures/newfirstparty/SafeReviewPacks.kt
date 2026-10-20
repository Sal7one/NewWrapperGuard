// Negative cases for the new targeted packs. This is source data, not an application.
fun reviewSafe(settings: Settings, call: Call) {
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    WebView.setWebContentsDebuggingEnabled(false)
    Cipher.getInstance("AES/GCM/NoPadding")
    MessageDigest.getInstance("SHA-256")
    call.respondText("Request failed")
}
