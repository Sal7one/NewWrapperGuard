package com.wrapperguard.acceptance;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** An owned, loopback-only audit fixture. Never serves project files or executes input. */
public final class MainActivity extends Activity {
    private volatile boolean running;
    private ServerSocket server;
    private TextView status;
    private final AtomicInteger requests = new AtomicInteger();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 64, 32, 32);
        TextView title = new TextView(this);
        title.setText("WrapperGuard\nDevice acceptance");
        title.setTextSize(30);
        title.setAccessibilityHeading(true);
        page.addView(title);
        TextView detail = new TextView(this);
        detail.setText("Owned Android test target\nAPI " + android.os.Build.VERSION.SDK_INT
            + "\n\nBound to device loopback only.\n\n/acceptance/secure: security headers present\n/acceptance/weak: deliberately missing headers\n\nNo commands, credentials or project source are accepted.");
        detail.setTextSize(18);
        detail.setPadding(0, 32, 0, 32);
        page.addView(detail);
        status = new TextView(this);
        status.setTextSize(18);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        setContentView(page);
        running = true;
        Thread worker = new Thread(this::serve, "wrapperguard-owned-http-fixture");
        worker.setDaemon(true);
        worker.start();
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket(8765, 4, InetAddress.getByName("127.0.0.1"))) {
            server = listener;
            listener.setSoTimeout(500);
            update("Ready on 127.0.0.1:8765 · 0 requests");
            while (running) {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(2000);
                    InputStream input = client.getInputStream();
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    int suffix = 0;
                    while (received.size() < 8192) {
                        int next = input.read();
                        if (next < 0) break;
                        received.write(next);
                        suffix = (suffix << 8) | next;
                        if (suffix == 0x0d0a0d0a) break;
                    }
                    if (suffix != 0x0d0a0d0a) continue;
                    String request = received.toString(StandardCharsets.US_ASCII.name()).split("\r\n", 2)[0];
                    boolean secure = "GET /acceptance/secure HTTP/1.1".equals(request);
                    boolean weak = "GET /acceptance/weak HTTP/1.1".equals(request);
                    String body = secure || weak ? "<!doctype html><title>Owned audit fixture</title><p>WrapperGuard Android acceptance</p>" : "Not found";
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 " + (secure || weak ? "200 OK" : "404 Not Found")
                        + "\r\nConnection: close\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + payload.length + "\r\n"
                        + (secure ? "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" : "") + "\r\n";
                    client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(payload);
                    update("Ready on 127.0.0.1:8765 · " + requests.incrementAndGet() + " requests");
                } catch (java.net.SocketTimeoutException expected) { /* bounded accept/read */ }
                  catch (java.io.IOException disconnected) { if (!running) break; }
            }
        } catch (Exception error) {
            if (running) update("Fixture unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void update(String message) { runOnUiThread(() -> status.setText(message)); }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (java.io.IOException ignored) { }
        super.onDestroy();
    }
}
