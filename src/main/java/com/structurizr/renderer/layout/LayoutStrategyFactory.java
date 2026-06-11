package com.structurizr.renderer.layout;

public class LayoutStrategyFactory {

    public static LayoutStrategy create() {
        if (!isDotAvailable()) {
            throw new IllegalStateException(
                "Graphviz 'dot' was not found on PATH. Install Graphviz and ensure 'dot' is available.");
        }
        return new GraphvizLayoutStrategy();
    }

    private static boolean isDotAvailable() {
        try {
            Process p = new ProcessBuilder("dot", "-V")
                .redirectErrorStream(true)
                .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
