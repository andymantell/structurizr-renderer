package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.autolayout.graphviz.GraphvizAutomaticLayout;

public class GraphvizLayoutStrategy implements LayoutStrategy {

    @Override
    public void applyLayout(Workspace workspace) throws Exception {
        new GraphvizAutomaticLayout().apply(workspace);
    }
}
