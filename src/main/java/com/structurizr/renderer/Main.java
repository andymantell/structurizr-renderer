package com.structurizr.renderer;

import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new RenderCommand()).execute(args);
        System.exit(exitCode);
    }
}
