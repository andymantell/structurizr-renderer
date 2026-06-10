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
    private static final int BOUNDARY_LABEL_HEIGHT = 55; // extra bottom space for the 33px bold label + margin

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
        // Track element in ALL open boundaries (innermost and all ancestors)
        for (BoundaryState state : boundaryStack) {
            state.addElement(element.getId());
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
        boundaryStack.push(new BoundaryState(enterpriseName, BoundaryType.Enterprise, "#444444", "2,4"));
    }

    @Override
    protected void endEnterpriseBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startGroupBoundary(ModelView view, String group, IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(group, BoundaryType.Group, "#444444", "2,4"));
    }

    @Override
    protected void endGroupBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startSoftwareSystemBoundary(ModelView view, SoftwareSystem softwareSystem,
                                                IndentingWriter writer) {
        ElementStyle style = findElementStyle(view, softwareSystem);
        String bg = style.getBackground() != null ? style.getBackground() : "#1168bd";
        String stroke = Shapes.shadeColor(bg, -10);
        boundaryStack.push(new BoundaryState(softwareSystem.getName(), BoundaryType.SoftwareSystem, stroke, ""));
    }

    @Override
    protected void endSoftwareSystemBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startContainerBoundary(ModelView view, Container container, IndentingWriter writer) {
        ElementStyle style = findElementStyle(view, container);
        String bg = style.getBackground() != null ? style.getBackground() : "#438dd5";
        String stroke = Shapes.shadeColor(bg, -10);
        boundaryStack.push(new BoundaryState(container.getName(), BoundaryType.Container, stroke, ""));
    }

    @Override
    protected void endContainerBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    @Override
    protected void startDeploymentNodeBoundary(DeploymentView view, DeploymentNode deploymentNode,
                                               IndentingWriter writer) {
        boundaryStack.push(new BoundaryState(deploymentNode.getName(), BoundaryType.DeploymentNode, "#444444", ""));
    }

    @Override
    protected void endDeploymentNodeBoundary(ModelView view, IndentingWriter writer) {
        if (boundaryStack.isEmpty()) return;
        BoundaryState state = boundaryStack.pop();
        writeBoundaryRect(view, state, writer);
    }

    // -------------------------------------------------------------------------
    // Boundary rect drawing
    // -------------------------------------------------------------------------

    private void writeBoundaryRect(ModelView view, BoundaryState state, IndentingWriter writer) {
        String strokeColor = state.strokeColor;
        String dashArray   = state.dashArray;
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
        int bh = (maxY - minY) + BOUNDARY_PADDING * 2 + BOUNDARY_LABEL_HEIGHT;
        // Reference uses ~33.6px (fontSize*1.4 where fontSize=24) for boundary labels
        int fontSize = 33;

        String dashAttr = dashArray.isEmpty() ? "" : String.format(" stroke-dasharray=\"%s\"", dashArray);
        writer.writeLine(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"0\" " +
            "fill=\"none\" stroke=\"%s\" stroke-width=\"2\"%s/>",
            bx, by, bw, bh, strokeColor, dashAttr));
        // Label at bottom-left, bold, 15px margin (matches reference placement)
        writer.writeLine(String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
            "font-weight=\"bold\" fill=\"%s\">%s</text>",
            bx + 15, by + bh - 15, Shapes.DEFAULT_FONT, fontSize,
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
        final String strokeColor;
        final String dashArray;
        final List<String> elementIds = new ArrayList<>();

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray) {
            this.label       = label;
            this.type        = type;
            this.strokeColor = strokeColor;
            this.dashArray   = dashArray;
        }

        void addElement(String id) {
            elementIds.add(id);
        }
    }
}
