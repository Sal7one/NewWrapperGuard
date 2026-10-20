"""Independently selected bundled source rule packs; local files only."""
RULE_PACKS = {
    'legacy-android-ktor': {'title': 'Original Android / Ktor review', 'file': 'android-ktor.yml', 'extensions': ('.kt', '.kts', '.java'), 'fileNames': ()},
    'android-manifest': {'title': 'Android manifest review', 'file': 'android-manifest.yml', 'extensions': (), 'fileNames': ('AndroidManifest.xml',)},
    'android-webview': {'title': 'Android WebView configuration', 'file': 'android-webview.yml', 'extensions': ('.kt', '.java'), 'fileNames': ()},
    'jvm-crypto': {'title': 'JVM cryptography review', 'file': 'jvm-crypto.yml', 'extensions': ('.kt', '.kts', '.java'), 'fileNames': ()},
    'ktor-runtime': {'title': 'Ktor runtime boundaries', 'file': 'ktor-runtime.yml', 'extensions': ('.kt', '.kts'), 'fileNames': ()},
}
