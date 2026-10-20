// STATIC SCANNER FIXTURE. No main function or application startup.
// WebView/Database names are illustrative and need no Android installation for analysis.
class Browser {
    void configure(WebView web, Database db, Object bridge, String user) {
        web.addJavascriptInterface(bridge, "app");
        db.rawQuery("SELECT * FROM users WHERE id=" + user, null);
    }
}
