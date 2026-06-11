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
    private static final int BOUNDARY_PADDING = 30;
    private static final int BOUNDARY_LABEL_HEIGHT = 55; // extra bottom space for the 33px bold label + margin

    // Placeholder tokens replaced in createDiagram once actual bounds are known
    private static final String W_TOKEN = "__SVG_CANVAS_W__";
    private static final String H_TOKEN = "__SVG_CANVAS_H__";

    // Per-view state (reset in writeHeader)
    private ModelView currentView;
    private Deque<BoundaryState> boundaryStack;
    // Tracks max right/bottom edge in group (translated) space across all drawn content
    private int actualMaxX;
    private int actualMaxY;

    // -------------------------------------------------------------------------
    // Header / Footer
    // -------------------------------------------------------------------------

    @Override
    protected void writeHeader(ModelView view, IndentingWriter writer) {
        this.currentView = view;
        this.boundaryStack = new ArrayDeque<>();
        this.actualMaxX = 0;
        this.actualMaxY = 0;

        String bg = "#ffffff";

        // Dimensions are placeholders; createDiagram() replaces them with actual tracked bounds.
        writer.writeLine(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "version=\"1.1\" width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" " +
            "viewBox=\"0 0 " + W_TOKEN + " " + H_TOKEN + "\">");
        writer.writeLine(String.format("<rect width=\"" + W_TOKEN + "\" height=\"" + H_TOKEN + "\" fill=\"%s\"/>", bg));
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
        int[] dims = Shapes.defaultDimensions(element, style);
        int w = dims[0], h = dims[1];

        actualMaxX = Math.max(actualMaxX, ev.getX() + w);
        actualMaxY = Math.max(actualMaxY, ev.getY() + h);

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
        if (state.elementIds.isEmpty() && !state.hasChildBounds()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (String id : state.elementIds) {
            Element element = view.getModel().getElement(id);
            if (element == null) continue;
            ElementView ev = view.getElementView(element);
            if (ev == null) continue;

            ElementStyle style = findElementStyle(view, element);
            int[] dims = Shapes.defaultDimensions(element, style);
            int w = dims[0], h = dims[1];

            minX = Math.min(minX, ev.getX());
            minY = Math.min(minY, ev.getY());
            maxX = Math.max(maxX, ev.getX() + w);
            maxY = Math.max(maxY, ev.getY() + h);
        }

        // Expand to include any nested child boundary rects that were already drawn
        if (state.hasChildBounds()) {
            minX = Math.min(minX, state.childMinX);
            minY = Math.min(minY, state.childMinY);
            maxX = Math.max(maxX, state.childMaxX);
            maxY = Math.max(maxY, state.childMaxY);
        }

        if (minX == Integer.MAX_VALUE) return;

        int bx = minX - BOUNDARY_PADDING;
        int by = minY - BOUNDARY_PADDING;
        int bw = (maxX - minX) + BOUNDARY_PADDING * 2;
        int bh = (maxY - minY) + BOUNDARY_PADDING * 2 + BOUNDARY_LABEL_HEIGHT;
        int fontSize = 33;

        // Track actual drawn extent so createDiagram() can size the canvas correctly
        actualMaxX = Math.max(actualMaxX, bx + bw);
        actualMaxY = Math.max(actualMaxY, by + bh);

        // Propagate this boundary's rect to the parent boundary (if nested)
        if (!boundaryStack.isEmpty()) {
            boundaryStack.peek().expandChildBounds(bx, by, bx + bw, by + bh);
        }

        String dashAttr = dashArray.isEmpty() ? "" : String.format(" stroke-dasharray=\"%s\"", dashArray);
        writer.writeLine(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"0\" " +
            "fill=\"none\" stroke=\"%s\" stroke-width=\"2\"%s/>",
            bx, by, bw, bh, strokeColor, dashAttr));
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
        // Replace placeholder dimensions with the actual bounds tracked during rendering.
        // actualMaxX/Y are in group (translated) space; add PADDING for the right/bottom margins.
        int cw = actualMaxX + PADDING * 2;
        int ch = actualMaxY + PADDING * 2;
        String fixed = definition
            .replace(W_TOKEN, String.valueOf(cw))
            .replace(H_TOKEN, String.valueOf(ch));
        return new SvgDiagram(view, fixed);
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
        int childMinX = Integer.MAX_VALUE, childMinY = Integer.MAX_VALUE;
        int childMaxX = Integer.MIN_VALUE, childMaxY = Integer.MIN_VALUE;

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray) {
            this.label       = label;
            this.type        = type;
            this.strokeColor = strokeColor;
            this.dashArray   = dashArray;
        }

        void addElement(String id) { elementIds.add(id); }

        boolean hasChildBounds() { return childMinX != Integer.MAX_VALUE; }

        void expandChildBounds(int x1, int y1, int x2, int y2) {
            childMinX = Math.min(childMinX, x1);
            childMinY = Math.min(childMinY, y1);
            childMaxX = Math.max(childMaxX, x2);
            childMaxY = Math.max(childMaxY, y2);
        }
    }
}
