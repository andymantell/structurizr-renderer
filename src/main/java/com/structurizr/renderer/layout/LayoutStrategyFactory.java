package com.structurizr.renderer.layout;

public class LayoutStrategyFactory {

    public static LayoutStrategy create() {
        if (isDotAvailable()) {
            System.err.println("[layout] Using Graphviz (dot)");
            return new GraphvizLayoutStrategy();
        }
        System.err.println("[layout] dot not found on PATH; using ELK (pure Java)");
        return new ElkLayoutStrategy();
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
