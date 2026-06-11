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
import java.util.Map;

/**
 * Loads Structurizr theme JSON files for use during rendering.
 *
 * Known themes (listed in BUNDLED_THEMES) are served directly from the JAR's
 * bundled-themes/ classpath resources — no network access is required.
 * The theme JSON and its icons are extracted side-by-side into a local cache
 * directory on first use, because ThemeUtils.inlineTheme() resolves relative
 * icon filenames against the theme file's own directory.
 *
 * For any other theme URL, the JSON is downloaded once and cached to
 * ~/.cache/structurizr-renderer/themes/.  Icons referenced from downloaded
 * themes are resolved to their original HTTPS URLs and fetched by IconCache.
 *
 * Delete ~/.cache/structurizr-renderer/ to force extraction / re-download.
 *
 * To add a new bundled theme:
 *   1. Place theme.json and icon files under
 *      src/main/resources/bundled-themes/<name>/
 *   2. Add an entry to BUNDLED_THEMES: "<name>", "/bundled-themes/<name>"
 */
public class ThemeCache {

    private static final Map<String, String> BUNDLED_THEMES = Map.of(
        "amazon-web-services-2020.04.30", "/bundled-themes/amazon-web-services-2020.04.30"
    );

    private static final Path CACHE_ROOT = Path.of(
        System.getProperty("user.home"), ".cache", "structurizr-renderer");
    private static final Path THEME_CACHE_DIR   = CACHE_ROOT.resolve("themes");
    private static final Path BUNDLED_EXTRACT_DIR = CACHE_ROOT.resolve("bundled-themes");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Loads all URL-based themes declared in the workspace. */
    public static void loadThemes(Workspace workspace) throws Exception {
        List<String> themeUrls = Arrays.asList(workspace.getViews().getConfiguration().getThemes());
        for (String url : themeUrls) {
            String bundledRoot = findBundled(url);
            Path themeJson;
            if (bundledRoot != null) {
                themeJson = extractBundled(bundledRoot);
            } else {
                themeJson = cachedPath(url);
                if (!Files.exists(themeJson)) {
                    downloadAndCache(url, themeJson);
                }
            }
            ThemeUtils.inlineTheme(workspace, themeJson.toFile());
        }
    }

    // -------------------------------------------------------------------------

    private static String findBundled(String url) {
        for (Map.Entry<String, String> e : BUNDLED_THEMES.entrySet()) {
            if (url.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Extracts the bundled theme's JSON and every icon it references from the JAR
     * classpath into a single cache directory, preserving the relative icon
     * filenames. ThemeUtils.inlineTheme() resolves icons relative to the theme
     * file's directory, so the side-by-side layout works without any path
     * rewriting — and no HTTP requests.
     *
     * @return the path to the extracted theme.json
     */
    private static Path extractBundled(String classpathRoot) throws Exception {
        String json;
        try (InputStream in = ThemeCache.class.getResourceAsStream(classpathRoot + "/theme.json")) {
            if (in == null) throw new IllegalStateException(
                "Bundled theme not found in JAR: " + classpathRoot + "/theme.json");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String themeName = classpathRoot.substring(classpathRoot.lastIndexOf('/') + 1);
        Path themeDir = BUNDLED_EXTRACT_DIR.resolve(themeName);
        Files.createDirectories(themeDir);

        JsonNode root = MAPPER.readTree(json);
        JsonNode elements = root.get("elements");
        if (elements != null && elements.isArray()) {
            for (JsonNode el : elements) {
                JsonNode iconNode = el.get("icon");
                if (iconNode == null || !iconNode.isTextual()) continue;
                String icon = iconNode.asText();
                if (icon.isEmpty() || icon.startsWith("http") || icon.startsWith("data:")) continue;

                // Always overwrite so a newer bundled theme in an upgraded JAR
                // replaces previously extracted icons rather than serving stale ones.
                Path dest = themeDir.resolve(icon);
                try (InputStream in = ThemeCache.class.getResourceAsStream(
                        classpathRoot + "/" + icon)) {
                    if (in != null) Files.write(dest, in.readAllBytes());
                }
            }
        }

        Path themeJson = themeDir.resolve("theme.json");
        Files.writeString(themeJson, json, StandardCharsets.UTF_8);
        return themeJson;
    }

    private static void downloadAndCache(String url, Path target) throws Exception {
        System.out.println("[theme] Downloading " + url);
        byte[] raw;
        try (InputStream in = URI.create(url).toURL().openStream()) {
            raw = in.readAllBytes();
        }
        String baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
        String json = resolveIconUrlsToHttps(new String(raw, StandardCharsets.UTF_8), baseUrl);
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        System.out.println("[theme] Cached to " + target);
    }

    private static String resolveIconUrlsToHttps(String json, String baseUrl) throws Exception {
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
        return THEME_CACHE_DIR.resolve(sb + ".json");
    }
}
