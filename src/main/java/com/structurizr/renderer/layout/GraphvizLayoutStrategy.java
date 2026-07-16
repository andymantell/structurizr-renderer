package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.autolayout.graphviz.DeclarationOrderGraphvizLayout;
import com.structurizr.model.Relationship;
import com.structurizr.view.AutomaticLayout;
import com.structurizr.view.ModelView;
import com.structurizr.view.RelationshipView;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GraphvizLayoutStrategy implements LayoutStrategy {

    // Views containing multiple relationships between the same element pair render
    // them as spread parallel lines with staggered labels; give those views extra
    // rank separation so the labels have breathing room along the line. Node
    // separation is left alone — inflating it scatters unrelated siblings apart.
    private static final int MIN_RANK_SEPARATION_WITH_PARALLEL_PAIRS = 600;

    @Override
    public void applyLayout(Workspace workspace) throws Exception {
        // Unlike the Structurizr web editor — where a view with no autoLayout can
        // still be arranged by hand and its coordinates saved — this renderer has
        // no drag-to-position step, and the DSL alone carries no element
        // coordinates. A view left without autoLayout would otherwise render with
        // every element stacked at the origin, so default it on here.
        for (ModelView view : allModelViews(workspace)) {
            if (view.getAutomaticLayout() == null) {
                // Explicit Graphviz overload: the no-arg enableAutomaticLayout()
                // defaults to Implementation.Dagre, which the rest of this class
                // never engages (everything below gates on Graphviz).
                view.enableAutomaticLayout(AutomaticLayout.RankDirection.TopBottom, 300, 300);
            }
        }

        for (ModelView view : allModelViews(workspace)) {
            AutomaticLayout al = view.getAutomaticLayout();
            if (al != null
                    && al.getImplementation() == AutomaticLayout.Implementation.Graphviz
                    && hasParallelPair(view)) {
                view.enableAutomaticLayout(
                    al.getRankDirection(),
                    Math.max(al.getRankSeparation(), MIN_RANK_SEPARATION_WITH_PARALLEL_PAIRS),
                    al.getNodeSeparation());
            }
        }

        // Declaration-order fork of GraphvizAutomaticLayout: Graphviz seeds its
        // within-rank ordering from input order, and the stock exporter feeds it
        // elements in lexicographic string-ID order ("10" before "2"). Feeding it
        // true declaration order makes layouts track the DSL, like the web UI.
        File workDir = Files.createTempDirectory("structurizr-renderer-layout").toFile();
        try {
            new DeclarationOrderGraphvizLayout(workDir).apply(workspace);
        } finally {
            File[] files = workDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            workDir.delete();
        }

        // Graphviz never interleaves clusters and ranks topology over proximity,
        // which can strand lightly-connected units far from their partners; let
        // them settle closer.
        for (ModelView view : allModelViews(workspace)) {
            AutomaticLayout al = view.getAutomaticLayout();
            if (al != null && al.getImplementation() == AutomaticLayout.Implementation.Graphviz) {
                TensionRelief.apply(view);
            }
        }
    }

    private static List<ModelView> allModelViews(Workspace workspace) {
        List<ModelView> views = new ArrayList<>();
        views.addAll(workspace.getViews().getSystemLandscapeViews());
        views.addAll(workspace.getViews().getSystemContextViews());
        views.addAll(workspace.getViews().getContainerViews());
        views.addAll(workspace.getViews().getComponentViews());
        views.addAll(workspace.getViews().getDynamicViews());
        views.addAll(workspace.getViews().getDeploymentViews());
        return views;
    }

    /** True when the view shows more than one relationship between any element pair. */
    private static boolean hasParallelPair(ModelView view) {
        Set<String> pairs = new HashSet<>();
        for (RelationshipView rv : view.getRelationships()) {
            Relationship rel = rv.getRelationship();
            String a = rel.getSourceId(), b = rel.getDestinationId();
            if (a.equals(b)) continue;
            String key = a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
            if (!pairs.add(key)) return true;
        }
        return false;
    }
}
