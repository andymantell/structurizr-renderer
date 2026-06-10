package com.structurizr.renderer.svg;

import com.structurizr.export.AbstractDiagramExporter;
import com.structurizr.export.Diagram;
import com.structurizr.export.IndentingWriter;
import com.structurizr.model.*;
import com.structurizr.view.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class SvgDiagramExporter extends AbstractDiagramExporter {

    private static final int PADDING = 50;
    private static final int BOUNDARY_PADDING = 20;

    // Per-view state (reset in writeHeader)
    private ModelView currentView;
    private Deque<BoundaryState> boundaryStack;

    // -------------------------------------------------------------------------
    // Header / Footer
    // -------------------------------------------------------------------------

    @Override
    protected void writeHeader(ModelView view, IndentingWriter writer) {
        this.currentView = view;
        this.boundaryStack = new ArrayDeque<>();

        // Compute canvas from element positions + style dimensions
        int maxX = 0, maxY = 0;
        for (ElementView ev : view.getElements()) {
            ElementStyle style = findElementStyle(view, ev.getElement());
            int w = style.getWidth()  != null ? style.getWidth()  : 450;
            int h = style.getHeight() != null ? style.getHeight() : 300;
            maxX = Math.max(maxX, ev.getX() + w);
            maxY = Math.max(maxY, ev.getY() + h);
        }
        int cw = maxX + PADDING * 2;
        int ch = maxY + PADDING * 2;

        String bg = "#ffffff";

        writer.writeLine(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "version=\"1.1\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">",
            cw, ch, cw, ch));
        writer.writeLine(String.format("<rect width=\"%d\" height=\"%d\" fill=\"%s\"/>", cw, ch, bg));
        writer.writeLine(Connectors.DEFS_BLOCK);
        writer.writeLine(String.format("<g transform=\"translate(%d,%d)\">", PADDING, PADDING));
    }

    @Override
    protected void writeFooter(ModelView view, IndentingWriter writer) {
        writer.writeLine("</g>");
        writer.writeLine("</svg>");
    }

    // -------------------------------------------------------------------------
    // Elements
    // -------------------------------------------------------------------------

    @Override
    protected void writeElement(ModelView view, Element element, IndentingWriter writer) {
        // Track element for open boundary
        if (!boundaryStack.isEmpty()) {
            boundaryStack.peek().addElement(element.getId());
        }

        ElementView ev = view.getElementView(element);
        if (ev == null) return;

        ElementStyle style = findElementStyle(view, element);
        int w = style.getWidth()  != null ? style.getWidth()  : 450;
        int h = style.getHeight() != null ? style.getHeight() : 300;

        writer.writeLine(Shapes.render(view, element, style, ev.getX(), ev.getY(), w, h));
    }

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    @Override
    protected void writeRelationship(ModelView view, RelationshipView rv, IndentingWriter writer) {
        Relationship rel = rv.getRelationship();

        ElementView srcEv = view.getElementView(view.getModel().getElement(rel.getSourceId()));
        ElementView dstEv = view.getElementView(view.getModel().getElement(rel.getDestinationId()));
        if (srcEv == null || dstEv == null) return;

        RelationshipStyle style = findRelationshipStyle(view, rel);

        writer.writeLine(Connectors.render(rv, srcEv, dstEv, style, view));
    }

    // -------------------------------------------------------------------------
    // Boundaries: push/pop a state; emit rect at end when bbox is known
    // -------------------------------------------------------------------------

    @Override
    protected void startEnterpriseBoundary(ModelView view, String enterpriseName, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(enterpriseName, BoundaryType.Enterprise));
    }

    @Override
    protected void endEnterpriseBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, "#888888", "8,4", writer);
    }

    @Override
    protected void startGroupBoundary(ModelView view, String group, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(group, BoundaryType.Group));
    }

    @Override
    protected void endGroupBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, "#dddddd", "8,4", writer);
    }

    @Override
    protected void startSoftwareSystemBoundary(ModelView view, SoftwareSystem softwareSystem,
                                                IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(softwareSystem.getName(), BoundaryType.SoftwareSystem));
    }

    @Override
    protected void endSoftwareSystemBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, "#1168bd", "8,4", writer);
    }

    @Override
    protected void startContainerBoundary(ModelView view, Container container, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(container.getName(), BoundaryType.Container));
    }

    @Override
    protected void endContainerBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, "#438dd5", "8,4", writer);
    }

    @Override
    protected void startDeploymentNodeBoundary(DeploymentView view, DeploymentNode deploymentNode,
                                               IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(deploymentNode.getName(), BoundaryType.DeploymentNode));
    }

    @Override
    protected void endDeploymentNodeBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, "#999999", "4,4", writer);
    }

    // -------------------------------------------------------------------------
    // Boundary rect drawing
    // -------------------------------------------------------------------------

    private void writeBoundaryRect(ModelView view, BoundaryState state,
                                    String strokeColor, String dashArray,
                                    IndentingWriter writer) {
        if (state.elementIds.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (String id : state.elementIds) {
            Element element = view.getModel().getElement(id);
            if (element == null) continue;
            ElementView ev = view.getElementView(element);
            if (ev == null) continue;

            ElementStyle style = findElementStyle(view, element);
            int w = style.getWidth()  != null ? style.getWidth()  : 450;
            int h = style.getHeight() != null ? style.getHeight() : 300;

            minX = Math.min(minX, ev.getX());
            minY = Math.min(minY, ev.getY());
            maxX = Math.max(maxX, ev.getX() + w);
            maxY = Math.max(maxY, ev.getY() + h);
        }

        if (minX == Integer.MAX_VALUE) return;

        int bx = minX - BOUNDARY_PADDING;
        int by = minY - BOUNDARY_PADDING;
        int bw = (maxX - minX) + BOUNDARY_PADDING * 2;
        int bh = (maxY - minY) + BOUNDARY_PADDING * 2;
        int fontSize = 18;

        writer.writeLine(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"6\" " +
            "fill=\"none\" stroke=\"%s\" stroke-width=\"2\" stroke-dasharray=\"%s\"/>",
            bx, by, bw, bh, strokeColor, dashArray));
        writer.writeLine(String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
            "font-style=\"italic\" fill=\"%s\">%s</text>",
            bx + 8, by + fontSize + 4, Shapes.DEFAULT_FONT, fontSize,
            strokeColor, Shapes.htmlEscape(state.label)));
    }

    // -------------------------------------------------------------------------
    // createDiagram
    // -------------------------------------------------------------------------

    @Override
    protected Diagram createDiagram(ModelView view, String definition) {
        return new SvgDiagram(view, definition);
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private enum BoundaryType { Enterprise, Group, SoftwareSystem, Container, DeploymentNode }

    private static class BoundaryState {
        final String label;
        final BoundaryType type;
        final List<String> elementIds = new ArrayList<>();

        BoundaryState(String label, BoundaryType type) {
            this.label = label;
            this.type  = type;
        }

        void addElement(String id) {
            elementIds.add(id);
        }
    }
}
