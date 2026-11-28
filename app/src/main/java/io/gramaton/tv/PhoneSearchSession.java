package io.gramaton.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Accepts a draft search query from a phone that scanned this editor's private link. */
final class PhoneSearchSession implements AutoCloseable {
    private static final int MAX_FORM_BYTES = 2048;
    private static final int MAX_QUERY_LENGTH = 160;
    private static final String PAGE = """
        <!doctype html><html lang="en"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Gramaton TV search</title>
        <style>
          body { color-scheme: dark; margin: 0; background: #000; color: #f5f5f5; font: 16px system-ui; }
          main { max-width: 28rem; margin: 12vh auto 0; padding: 1.5rem; }
          h1 { font-size: 1.75rem; } label { display: block; margin-bottom: .5rem; }
          input, button { box-sizing: border-box; width: 100%%; padding: .8rem 1rem; border-radius: .5rem; font: inherit; }
          input { background: #202020; border: 1px solid #666; color: inherit; }
          button { margin-top: .75rem; border: 0; background: #f5f5f5; color: #000; font-weight: 650; }
          :focus-visible { outline: 2px solid #f5f5f5; outline-offset: 2px; }
        </style>
        <main><h1>Gramaton TV search</h1>%s
          <form method="post"><label for="query">Movie or TV show</label>
          <input id="query" name="query" type="search" inputmode="search" maxlength="%d" autocomplete="off">
          <button type="submit">Send to TV</button></form>
        </main></html>
        """;

    private final Inet4Address address;
    private final String path;
    private final ServerSocket server;
    private final Consumer<String> onQuery;

    PhoneSearchSession(Inet4Address address, Consumer<String> onQuery) throws IOException {
        this.address = address;
        this.onQuery = onQuery;
        byte[] secret = new byte[18];
        new SecureRandom().nextBytes(secret);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = new ServerSocket(0, 4);
    }

    String url() { return "http://" + address.getHostAddress() + ":" + server.getLocalPort() + path; }

    void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                if (!client.getLocalAddress().equals(address) && !client.getLocalAddress().isLoopbackAddress()) continue;
                client.setSoTimeout(1600);
                handle(client);
            } catch (IOException ignored) { /* Closed sessions and incomplete clients need no response. */ }
        }
    }

    private void handle(Socket client) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        String[] request = line(input).split(" ");
        if (request.length != 3 || !path.equals(request[1])) {
            reply(client, "404 Not Found", "Phone typing session unavailable.");
            return;
        }
        Map<String, String> headers = headers(input);
        if ("GET".equals(request[0])) {
            reply(client, "200 OK", String.format(Locale.ROOT, PAGE, "", MAX_QUERY_LENGTH));
            return;
        }
        if (!"POST".equals(request[0]) || headers.containsKey("transfer-encoding")
            || "cross-site".equals(headers.get("sec-fetch-site"))
            || !headers.getOrDefault("content-type", "").startsWith("application/x-www-form-urlencoded")) {
            reply(client, "400 Bad Request", "Use the phone typing form.");
            return;
        }
        try {
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length < 6 || length > MAX_FORM_BYTES) throw new IllegalArgumentException();
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            String form = new String(bytes, StandardCharsets.UTF_8);
            if (!form.startsWith("query=") || form.indexOf('&') >= 0) throw new IllegalArgumentException();
            String query = URLDecoder.decode(form.substring(6), StandardCharsets.UTF_8.name());
            if (query.length() > MAX_QUERY_LENGTH || query.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            if (server.isClosed()) return;
            onQuery.accept(query);
            reply(client, "200 OK", String.format(Locale.ROOT, PAGE, "<p role=\"status\">Sent to TV.</p>", MAX_QUERY_LENGTH));
        } catch (IllegalArgumentException invalid) {
            reply(client, "400 Bad Request", "Enter a title and try again.");
        }
    }

    private static Map<String, String> headers(InputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            String line = line(input);
            if (line.isEmpty()) return headers;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("Invalid header");
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (headers.putIfAbsent(name, value) != null) throw new IOException("Duplicate header");
        }
        throw new IOException("Too many headers");
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int value = input.read();
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (value == -1) break;
            if (value != '\r') bytes.write(value);
        }
        throw new IOException("Incomplete header");
    }

    private static void reply(Socket client, String status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
            + "\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
            + "\r\nX-Content-Type-Options: nosniff"
            + "\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n\r\n";
        OutputStream output = client.getOutputStream();
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
    }
}
