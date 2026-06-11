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
    private List<Connectors.LabelInfo> pendingRelationships;
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
        this.pendingRelationships = new ArrayList<>();
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
        repelLabels(pendingRelationships);
        for (Connectors.LabelInfo li : pendingRelationships) {
            writer.writeLine(Connectors.renderLayout(li));
        }
        pendingRelationships.clear();

        writer.writeLine("</g>");
        writer.writeLine("</svg>");
    }

    /**
     * Iteratively push apart label bounding boxes that overlap.
     * Each iteration nudges every overlapping pair in opposite directions until
     * none overlap or the iteration limit is reached.
     */
    private static void repelLabels(List<Connectors.LabelInfo> labels) {
        final int MAX_ITER = 30;
        for (int iter = 0; iter < MAX_ITER; iter++) {
            boolean moved = false;
            for (int i = 0; i < labels.size(); i++) {
                for (int j = i + 1; j < labels.size(); j++) {
                    Connectors.LabelInfo a = labels.get(i);
                    Connectors.LabelInfo b = labels.get(j);
                    if (!a.hasLabel || !b.hasLabel) continue;

                    double ax1 = a.labelX - a.labelW / 2.0, ax2 = a.labelX + a.labelW / 2.0;
                    double ay1 = a.labelY - a.labelH / 2.0, ay2 = a.labelY + a.labelH / 2.0;
                    double bx1 = b.labelX - b.labelW / 2.0, bx2 = b.labelX + b.labelW / 2.0;
                    double by1 = b.labelY - b.labelH / 2.0, by2 = b.labelY + b.labelH / 2.0;

                    double overlapX = Math.min(ax2, bx2) - Math.max(ax1, bx1);
                    double overlapY = Math.min(ay2, by2) - Math.max(ay1, by1);

                    if (overlapX > 0 && overlapY > 0) {
                        moved = true;
                        double dx = b.labelX - a.labelX;
                        double dy = b.labelY - a.labelY;
                        double len = Math.sqrt(dx * dx + dy * dy);
                        if (len < 1) { dx = 0; dy = 1; len = 1; }
                        // Push each label half the overlap distance plus a small gap
                        double pushX = (overlapX / 2.0 + 4) * (dx / len);
                        double pushY = (overlapY / 2.0 + 4) * (dy / len);
                        a.labelX -= pushX;  a.labelY -= pushY;
                        b.labelX += pushX;  b.labelY += pushY;
                    }
                }
            }
            if (!moved) break;
        }
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

        // Defer rendering: collect layout data so we can run a repulsion pass over all labels
        // before writing anything, ensuring crossing-edge labels don't overlap.
        pendingRelationships.add(Connectors.computeLayout(rv, srcEv, dstEv, style, view));
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
        ElementStyle style = findElementStyle(view, deploymentNode);
        String strokeColor = style.getStroke() != null ? style.getStroke()
                           : style.getColor()  != null ? style.getColor()
                           : "#444444";
        String iconDataUri = null;
        if (style.getIcon() != null && !style.getIcon().isBlank()) {
            iconDataUri = IconCache.toDataUri(style.getIcon());
        }
        boundaryStack.push(new BoundaryState(deploymentNode.getName(), BoundaryType.DeploymentNode,
                                              strokeColor, "", iconDataUri));
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

        if (state.iconDataUri != null) {
            // Small icon to the left of the label at the bottom of the boundary box
            int iconSize = 36;
            int iconX    = bx + 8;
            int iconY    = by + bh - iconSize - 8;
            writer.writeLine(String.format(
                "<image x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" href=\"%s\" xlink:href=\"%s\"/>",
                iconX, iconY, iconSize, iconSize, state.iconDataUri, state.iconDataUri));
            writer.writeLine(String.format(
                "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
                "font-weight=\"bold\" fill=\"%s\">%s</text>",
                iconX + iconSize + 6, by + bh - 15, Shapes.DEFAULT_FONT, fontSize,
                strokeColor, Shapes.htmlEscape(state.label)));
        } else {
            writer.writeLine(String.format(
                "<text x=\"%d\" y=\"%d\" font-family=\"%s\" font-size=\"%d\" " +
                "font-weight=\"bold\" fill=\"%s\">%s</text>",
                bx + 15, by + bh - 15, Shapes.DEFAULT_FONT, fontSize,
                strokeColor, Shapes.htmlEscape(state.label)));
        }
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
        final String iconDataUri;  // nullable; base64 data URI for boundary label icon
        final List<String> elementIds = new ArrayList<>();
        int childMinX = Integer.MAX_VALUE, childMinY = Integer.MAX_VALUE;
        int childMaxX = Integer.MIN_VALUE, childMaxY = Integer.MIN_VALUE;

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray) {
            this(label, type, strokeColor, dashArray, null);
        }

        BoundaryState(String label, BoundaryType type, String strokeColor, String dashArray, String iconDataUri) {
            this.label       = label;
            this.type        = type;
            this.strokeColor = strokeColor;
            this.dashArray   = dashArray;
            this.iconDataUri = iconDataUri;
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
