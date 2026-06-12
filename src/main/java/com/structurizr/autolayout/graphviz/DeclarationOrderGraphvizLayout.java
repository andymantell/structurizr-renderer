package com.structurizr.autolayout.graphviz;

import com.structurizr.Workspace;
import com.structurizr.export.Diagram;
import com.structurizr.view.AutomaticLayout;
import com.structurizr.view.ComponentView;
import com.structurizr.view.ContainerView;
import com.structurizr.view.CustomView;
import com.structurizr.view.DeploymentView;
import com.structurizr.view.DynamicView;
import com.structurizr.view.ModelView;
import com.structurizr.view.SystemContextView;
import com.structurizr.view.SystemLandscapeView;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Drop-in replacement for {@link GraphvizAutomaticLayout} that uses
 * {@link DeclarationOrderDOTExporter}, so Graphviz receives elements in model
 * declaration order (see that class for why). Lives in this package because
 * the pieces it reuses — {@link DOTExporter} and {@link SVGReader} — are
 * package-private.
 *
 * Working files are written to the supplied directory (use a temp dir rather
 * than the upstream default of the current working directory).
 */
public class DeclarationOrderGraphvizLayout {

    private final File path;
    private final int margin = 400;
    private final boolean changePaperSize = true;

    public DeclarationOrderGraphvizLayout(File path) {
        this.path = path;
    }

    public void apply(Workspace workspace) throws Exception {
        for (CustomView view : workspace.getViews().getCustomViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (SystemLandscapeView view : workspace.getViews().getSystemLandscapeViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (SystemContextView view : workspace.getViews().getSystemContextViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (ContainerView view : workspace.getViews().getContainerViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (ComponentView view : workspace.getViews().getComponentViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (DynamicView view : workspace.getViews().getDynamicViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
        for (DeploymentView view : workspace.getViews().getDeploymentViews()) {
            if (hasGraphvizLayout(view)) layout(view, view.getAutomaticLayout());
        }
    }

    private static boolean hasGraphvizLayout(ModelView view) {
        return view.getAutomaticLayout() != null
            && view.getAutomaticLayout().getImplementation() == AutomaticLayout.Implementation.Graphviz;
    }

    private void layout(ModelView view, AutomaticLayout automaticLayout) throws Exception {
        DeclarationOrderDOTExporter exporter = new DeclarationOrderDOTExporter(
            RankDirection.valueOf(automaticLayout.getRankDirection().name()),
            automaticLayout.getRankSeparation(),
            automaticLayout.getNodeSeparation());
        exporter.setLocale(Locale.US);

        Diagram diagram = switch (view) {
            case CustomView v          -> exporter.export(v);
            case SystemLandscapeView v -> exporter.export(v);
            case SystemContextView v   -> exporter.export(v);
            case ContainerView v       -> exporter.export(v);
            case ComponentView v       -> exporter.export(v);
            case DynamicView v         -> exporter.export(v);
            case DeploymentView v      -> exporter.export(v);
            default -> throw new IllegalArgumentException("Unsupported view type: " + view.getClass());
        };

        File dotFile = new File(path, diagram.getKey() + ".dot");
        try (BufferedWriter writer = Files.newBufferedWriter(dotFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(diagram.getDefinition());
        }

        runGraphviz(dotFile);

        new SVGReader(path, margin, changePaperSize).parseAndApplyLayout(view);
    }

    private void runGraphviz(File dotFile) throws Exception {
        List<String> command = List.of("dot", dotFile.getAbsolutePath(), "-Tsvg", "-O");
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Graphviz failed with exit code " + exitCode
                + " for " + dotFile.getName());
        }
    }
}
