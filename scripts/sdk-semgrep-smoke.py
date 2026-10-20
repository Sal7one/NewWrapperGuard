#!/usr/bin/env python3
"""Run installed Semgrep on inert Kotlin/Java fixture data through the SDK."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-python', required=True)
    parser.add_argument('--semgrep', required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='wg-semgrep-smoke-') as temporary:
        user = Path(temporary).resolve()
        project = user / 'project'
        project.mkdir()
        (project / 'Application.kt').write_text('''import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
fun configure() {
  anyHost()
}
class TrustAll : X509TrustManager {
  override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) { }
}
''')
        (project / 'Browser.java').write_text('''class Browser {
  void configure(WebView web, Database db, Object bridge, String user) {
    web.addJavascriptInterface(bridge, "app");
    db.rawQuery("SELECT * FROM users WHERE id=" + user, null);
  }
}
''')
        (project / 'Risks.kt').write_text("""fun unsafe(settings: Settings, call: Call, error: Throwable) {
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
""")
        (project / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:debuggable="true" android:usesCleartextTraffic="true" android:allowBackup="true"/></manifest>')
        (project / 'Safe.kt').write_text("""fun safe(settings: Settings, call: Call) {
  settings.allowFileAccessFromFileURLs = false
  settings.allowUniversalAccessFromFileURLs = false
  WebView.setWebContentsDebuggingEnabled(false)
  Cipher.getInstance("AES/GCM/NoPadding")
  MessageDigest.getInstance("SHA-256")
  call.respondText("Request failed")
}
""")
        # Source control/configuration and source comments must not suppress the rules.
        (project / '.semgrepignore').write_text('*\n')
        (project / 'Application.kt').write_text((project / 'Application.kt').read_text().replace('anyHost()', 'anyHost() // nosemgrep'))
        profile = user / 'semgrep.json'
        profile.write_text(json.dumps({'id':'sast.semgrep','displayName':'Semgrep test','version':'0.3.0',
            'analysisKind':'SAST','format':'sarif','runner':{'tool':'semgrep','executable':str(Path(args.semgrep).absolute()),'timeoutMs':60000,'rulePacks':['legacy-android-ktor','android-manifest','android-webview','jvm-crypto','ktor-runtime']},
            'allowedOperations':['SCAN'],'requestedActions':['WARNING']}))
        result = subprocess.run([args.sdk_python,'-I','-m','wrapperguard_sdk','--profile',str(profile)],
            input=json.dumps({'apiVersion':1,'projectPath':str(project),'operation':'SCAN'}),
            text=True,capture_output=True,timeout=75)
        if result.returncode:
            raise AssertionError('SDK/Semgrep did not complete: ' + result.stderr)
        report=json.loads(result.stdout)
        required={'wrapperguard.ktor.unrestricted-cors','wrapperguard.android.javascript-bridge',
                  'wrapperguard.android.raw-query-concatenation','wrapperguard.kotlin.empty-server-trust-check'}
        required.update({'wrapperguard.android.manifest-debuggable','wrapperguard.android.manifest-cleartext',
            'wrapperguard.android.manifest-backup','wrapperguard.android.webview-file-origin',
            'wrapperguard.android.webview-universal-origin','wrapperguard.android.webview-debugging',
            'wrapperguard.jvm.weak-cipher','wrapperguard.jvm.weak-digest','wrapperguard.jvm.constant-random-seed',
            'wrapperguard.ktor.stacktrace-response','wrapperguard.ktor.credentialed-unrestricted-cors'})
        found={f['ruleId'] for f in report['findings']}
        if not required.issubset(found) or not report['complete']:
            raise AssertionError('Missing expected findings or incomplete report: ' + json.dumps(report))
        if any(f['location']['path'] not in {'Application.kt','Browser.java','Risks.kt','AndroidManifest.xml'} for f in report['findings']):
            raise AssertionError('Scanner locations were not mapped back to source')
        if any(f.get('actions') != ['WARNING'] for f in report['findings']):
            raise AssertionError('Unexpected action requests')
        print('Real Semgrep / SDK: five independently selectable packs, positive/negative Kotlin + Java + manifest fixtures, source mapping and suppression isolation passed.')


if __name__=='__main__':
    main()
