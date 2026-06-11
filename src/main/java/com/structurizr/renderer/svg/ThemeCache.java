package com.structurizr.renderer.svg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.structurizr.Workspace;
import com.structurizr.view.ThemeUtils;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Caches Structurizr theme JSON files to disk so subsequent renders work offline.
 *
 * On first use for a given URL, downloads the theme JSON, resolves relative icon
 * paths to full HTTPS URLs, and saves the result to
 * {@code ~/.cache/structurizr-renderer/themes/}.  Subsequent renders load from
 * disk via {@link ThemeUtils#inlineTheme}.  Because icon paths are stored as
 * full HTTPS URLs in the cached JSON, {@link IconCache} handles them with its
 * own persistent disk cache — the network is only needed on the very first render
 * of each resource.
 *
 * Delete {@code ~/.cache/structurizr-renderer/} to force re-download.
 */
public class ThemeCache {

    private static final Path CACHE_DIR = Path.of(
        System.getProperty("user.home"), ".cache", "structurizr-renderer", "themes");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Loads all URL-based themes declared in the workspace.  Uses disk cache on
     * all runs after the first download.
     */
    public static void loadThemes(Workspace workspace) throws Exception {
        List<String> themeUrls = Arrays.asList(workspace.getViews().getConfiguration().getThemes());
        for (String url : themeUrls) {
            Path cached = cachedPath(url);
            if (!Files.exists(cached)) {
                downloadAndCache(url, cached);
            }
            ThemeUtils.inlineTheme(workspace, cached.toFile());
        }
    }

    // -------------------------------------------------------------------------

    private static void downloadAndCache(String url, Path target) throws Exception {
        System.out.println("[theme] Downloading " + url);
        byte[] raw;
        try (InputStream in = URI.create(url).toURL().openStream()) {
            raw = in.readAllBytes();
        }

        // Resolve relative icon filenames to absolute HTTPS URLs before saving.
        // ThemeUtils.inlineTheme() leaves http/https icon paths as-is, so icons
        // remain as remote references handled by IconCache.
        String baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
        String json = resolveIconUrls(new String(raw, StandardCharsets.UTF_8), baseUrl);

        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        System.out.println("[theme] Cached to " + target);
    }

    private static String resolveIconUrls(String json, String baseUrl) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode elements = root.get("elements");
        if (elements != null && elements.isArray()) {
            for (JsonNode el : elements) {
                JsonNode iconNode = el.get("icon");
                if (iconNode != null && iconNode.isTextual()) {
                    String icon = iconNode.asText();
                    if (!icon.isEmpty() && !icon.startsWith("http") && !icon.startsWith("data:")) {
                        ((ObjectNode) el).put("icon", baseUrl + icon);
                    }
                }
            }
        }
        return MAPPER.writeValueAsString(root);
    }

    private static Path cachedPath(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return CACHE_DIR.resolve(sb + ".json");
    }
}
