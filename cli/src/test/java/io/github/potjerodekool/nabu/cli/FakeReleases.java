package io.github.potjerodekool.nabu.cli;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A GitHub releases page on localhost: {@code /releases/latest} redirects, the files are under {@code download}. */
final class FakeReleases implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> files = new HashMap<>();
    private volatile String latest = "v0.2.0";

    FakeReleases() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            if (path.equals("/releases/latest")) {
                exchange.getResponseHeaders().add("Location", "/releases/tag/" + latest);
                exchange.sendResponseHeaders(302, -1);
            } else if (files.containsKey(path)) {
                final byte[] body = files.get(path);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";
    }

    FakeReleases latest(final String tag) {
        latest = tag;
        return this;
    }

    /** Publishes a file of a release, and puts it in the {@code SHA256SUMS} of that release. */
    FakeReleases publish(final String tag, final String name, final byte[] content) {
        files.put("/releases/download/" + tag + "/" + name, content);
        sums(tag, name, sha256(content));
        return this;
    }

    /** Publishes a file with a wrong checksum in {@code SHA256SUMS}. */
    FakeReleases publishCorrupt(final String tag, final String name, final byte[] content) {
        files.put("/releases/download/" + tag + "/" + name, content);
        sums(tag, name, "0".repeat(64));
        return this;
    }

    private final Map<String, Map<String, String>> sums = new HashMap<>();

    private void sums(final String tag, final String name, final String sum) {
        sums.computeIfAbsent(tag, t -> new LinkedHashMap<>()).put(name, sum);
        final StringBuilder text = new StringBuilder();
        sums.get(tag).forEach((n, s) -> text.append(s).append("  ").append(n).append('\n'));
        files.put("/releases/download/" + tag + "/SHA256SUMS", text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static byte[] zip(final String... namesAndContents) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(final byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
