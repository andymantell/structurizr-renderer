package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.view.*;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;

import java.util.HashMap;
import java.util.Map;

public class ElkLayoutStrategy implements LayoutStrategy {

    private static boolean initialized = false;

    private static synchronized void ensureInitialized() {
        if (!initialized) {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
                new CoreOptions(),
                new LayeredMetaDataProvider()
            );
            initialized = true;
        }
    }

    @Override
    public void applyLayout(Workspace workspace) {
        ensureInitialized();

        Views views = workspace.getViews();

        for (CustomView view : views.getCustomViews()) applyToView(view, workspace);
        for (SystemLandscapeView view : views.getSystemLandscapeViews()) applyToView(view, workspace);
        for (SystemContextView view : views.getSystemContextViews()) applyToView(view, workspace);
        for (ContainerView view : views.getContainerViews()) applyToView(view, workspace);
        for (ComponentView view : views.getComponentViews()) applyToView(view, workspace);
        for (DynamicView view : views.getDynamicViews()) applyToView(view, workspace);
        for (DeploymentView view : views.getDeploymentViews()) applyToView(view, workspace);
    }

    private void applyToView(ModelView view, Workspace workspace) {
        AutomaticLayout al = view.getAutomaticLayout();
        if (al == null) return;

        ElkNode root = ElkGraphUtil.createGraph();
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(CoreOptions.DIRECTION, toElkDirection(al.getRankDirection()));
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, (double) al.getRankSeparation());
        root.setProperty(LayeredOptions.SPACING_NODE_NODE, (double) al.getNodeSeparation());

        Map<String, ElkNode> nodeMap = new HashMap<>();

        for (ElementView ev : view.getElements()) {
            com.structurizr.model.Element element = ev.getElement();
            ElementStyle style = view.getViewSet().getConfiguration().getStyles().findElementStyle(element);

            int w = style.getWidth() != null ? style.getWidth() : 450;
            int h = style.getHeight() != null ? style.getHeight() : 300;

            ElkNode node = ElkGraphUtil.createNode(root);
            node.setWidth(w);
            node.setHeight(h);
            nodeMap.put(ev.getId(), node);
        }

        for (RelationshipView rv : view.getRelationships()) {
            com.structurizr.model.Relationship rel = rv.getRelationship();
            ElkNode src = nodeMap.get(rel.getSourceId());
            ElkNode dst = nodeMap.get(rel.getDestinationId());
            if (src != null && dst != null) {
                ElkGraphUtil.createSimpleEdge(src, dst);
            }
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        // Write positions back to view
        int i = 0;
        for (ElementView ev : view.getElements()) {
            ElkNode node = nodeMap.get(ev.getId());
            if (node != null) {
                ev.setX((int) node.getX());
                ev.setY((int) node.getY());
            }
        }
    }

    private Direction toElkDirection(AutomaticLayout.RankDirection rankDirection) {
        if (rankDirection == null) return Direction.RIGHT;
        return switch (rankDirection) {
            case TopBottom -> Direction.DOWN;
            case BottomTop -> Direction.UP;
            case LeftRight -> Direction.RIGHT;
            case RightLeft -> Direction.LEFT;
        };
    }
}
