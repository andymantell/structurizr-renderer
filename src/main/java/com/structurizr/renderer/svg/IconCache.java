package com.structurizr.renderer.svg;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads icon images from URLs and encodes them as inline base64 data URIs.
 *
 * Icons are cached at two levels:
 *  1. In-memory: each URL is encoded at most once per JVM session.
 *  2. On disk: raw bytes are stored at {@code ~/.cache/structurizr-renderer/icons/}
 *     so subsequent runs (including CI) work without network access after the
 *     first download.
 *
 * Delete {@code ~/.cache/structurizr-renderer/} to force re-download.
 */
public class IconCache {

    private static final Path DISK_CACHE = initDiskCache();
    private static final ConcurrentHashMap<String, String> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final String FAILED = "";

    private static Path initDiskCache() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".cache", "structurizr-renderer", "icons");
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            System.err.println("[icon] Cannot create disk cache: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a {@code "data:image/...;base64,..."} URI for the given URL, or
     * {@code null} if the URL is blank or the resource cannot be loaded.
     */
    public static String toDataUri(String url) {
        if (url == null || url.isBlank()) return null;
        // Already a data URI — return as-is (happens when ThemeUtils inlines icons)
        if (url.startsWith("data:")) return url;
        String result = MEMORY_CACHE.computeIfAbsent(url, IconCache::fetchWithDiskCache);
        return result.isEmpty() ? null : result;
    }

    private static String fetchWithDiskCache(String url) {
        String mime = url.toLowerCase().endsWith(".svg") ? "image/svg+xml" : "image/png";

        // 1. Try disk cache
        if (DISK_CACHE != null) {
            Path cached = DISK_CACHE.resolve(urlToFilename(url));
            if (Files.exists(cached)) {
                try {
                    byte[] bytes = Files.readAllBytes(cached);
                    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                } catch (Exception e) {
                    // fall through to download
                }
            }
        }

        // 2. Download
        try {
            byte[] bytes;
            try (InputStream in = URI.create(url).toURL().openStream()) {
                bytes = in.readAllBytes();
            }

            // Persist to disk cache (best-effort)
            if (DISK_CACHE != null) {
                try {
                    Files.write(DISK_CACHE.resolve(urlToFilename(url)), bytes);
                } catch (Exception ignored) {
                }
            }

            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            System.err.println("[icon] Cannot load " + url + ": " + e.getMessage());
            return FAILED;
        }
    }

    private static String urlToFilename(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            String ext = url.contains(".") ? url.substring(url.lastIndexOf('.')) : ".bin";
            return sb + ext;
        } catch (Exception e) {
            return String.valueOf(url.hashCode()) + ".bin";
        }
    }
}
