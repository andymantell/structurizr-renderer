package com.structurizr.renderer.layout;

import com.structurizr.Workspace;
import com.structurizr.model.Element;
import com.structurizr.view.*;
import com.structurizr.view.Shape;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;

import java.util.*;

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

        ViewSet views = workspace.getViews();

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

            int[] dims = defaultDimensions(element, style);
            int w = dims[0];
            int h = dims[1];

            ElkNode node = ElkGraphUtil.createNode(root);
            node.setWidth(w);
            node.setHeight(h);
            nodeMap.put(ev.getId(), node);
        }

        List<RelationshipView> rvList = new ArrayList<>();
        List<ElkEdge> edgeList = new ArrayList<>();

        for (RelationshipView rv : view.getRelationships()) {
            com.structurizr.model.Relationship rel = rv.getRelationship();
            ElkNode src = nodeMap.get(rel.getSourceId());
            ElkNode dst = nodeMap.get(rel.getDestinationId());
            if (src != null && dst != null) {
                ElkEdge edge = ElkGraphUtil.createSimpleEdge(src, dst);
                rvList.add(rv);
                edgeList.add(edge);
            }
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        // Write node positions back to view
        for (ElementView ev : view.getElements()) {
            ElkNode node = nodeMap.get(ev.getId());
            if (node != null) {
                ev.setX((int) node.getX());
                ev.setY((int) node.getY());
            }
        }

        // Store ELK's full edge path (port exit → bend points → port entry).
        // Including bend points is essential: ELK routes edges through separate channels to avoid
        // crossings, but only when all waypoints are preserved. Throwing away bends and drawing
        // straight diagonals discards that crossing-avoidance work and creates a tangled mess.
        for (int i = 0; i < rvList.size(); i++) {
            RelationshipView rv = rvList.get(i);
            ElkEdge edge = edgeList.get(i);
            if (!edge.getSections().isEmpty()) {
                ElkEdgeSection section = edge.getSections().get(0);
                List<Vertex> vertices = new ArrayList<>();
                vertices.add(new Vertex((int) section.getStartX(), (int) section.getStartY()));
                for (ElkBendPoint bp : section.getBendPoints()) {
                    vertices.add(new Vertex((int) bp.getX(), (int) bp.getY()));
                }
                vertices.add(new Vertex((int) section.getEndX(), (int) section.getEndY()));
                rv.setVertices(vertices);
            }
        }
    }

    /** Returns [width, height] with shape-aware defaults matching the reference renderer. */
    public static int[] defaultDimensions(com.structurizr.model.Element element, ElementStyle style) {
        int w = style.getWidth()  != null ? style.getWidth()  : 0;
        int h = style.getHeight() != null ? style.getHeight() : 0;
        if (w > 0 && h > 0) return new int[]{w, h};
        Shape shape = style.getShape() != null ? style.getShape() : Shape.Box;
        // Person/Robot use a taller element (400×400) matching the reference
        if (shape == Shape.Person || shape == Shape.Robot) {
            return new int[]{w > 0 ? w : 400, h > 0 ? h : 400};
        }
        return new int[]{w > 0 ? w : 450, h > 0 ? h : 300};
    }

    private Direction toElkDirection(AutomaticLayout.RankDirection rankDirection) {
        if (rankDirection == null) return Direction.RIGHT;
        // Note: the 4.1.0 structurizr-dsl JAR defaults to TopBottom for plain "autoLayout"
        // but the official Structurizr rendering uses left-to-right for that case.
        // Map TopBottom→RIGHT to match official output; explicit "autoLayout lr" also gets RIGHT.
        return switch (rankDirection) {
            case TopBottom -> Direction.RIGHT;
            case BottomTop -> Direction.LEFT;
            case LeftRight -> Direction.RIGHT;
            case RightLeft -> Direction.LEFT;
        };
    }
}
