package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.autolayout.graphviz.GraphvizAutomaticLayout;
import com.structurizr.model.Relationship;
import com.structurizr.view.AutomaticLayout;
import com.structurizr.view.ModelView;
import com.structurizr.view.RelationshipView;

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

        new GraphvizAutomaticLayout().apply(workspace);
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
