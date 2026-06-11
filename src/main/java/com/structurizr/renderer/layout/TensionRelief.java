package com.structurizr.renderer.layout;

import com.structurizr.model.DeploymentNode;
import com.structurizr.model.Element;
import com.structurizr.model.GroupableElement;
import com.structurizr.renderer.svg.Shapes;
import com.structurizr.view.ElementStyle;
import com.structurizr.view.ElementView;
import com.structurizr.view.ModelView;
import com.structurizr.view.RelationshipView;
import com.structurizr.view.Vertex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-layout refinement. Graphviz assigns ranks from the graph topology and
 * never interleaves clusters, so a lightly-connected element — or a whole
 * cluster such as a top-level deployment node — can end up stranded on the far
 * side of the diagram from the only thing it connects to.
 *
 * This pass treats each top-level cluster / group / loose element as a rigid
 * unit, pulls every movable unit towards the centroid of its connection
 * partners, and then nudges it out of any box overlaps. A move is only kept
 * when it makes the unit's external connections meaningfully shorter.
 */
final class TensionRelief {

    private static final int MARGIN = 80;              // minimum gap kept between units
    private static final int BASE_PADDING = 40;        // boundary frame + label allowance
    private static final int PADDING_PER_LEVEL = 60;   // each nesting level adds another frame
    private static final double MIN_IMPROVEMENT = 0.9; // keep a move only if edges get >10% shorter
    private static final int PASSES = 2;

    private TensionRelief() {
    }

    /** A rigid group of element views that move together. */
    private static final class Unit {
        final List<ElementView> members = new ArrayList<>();
        final Set<String> memberIds = new LinkedHashSet<>();
        final boolean padded;
        double minX, minY, maxX, maxY;

        Unit(boolean padded) {
            this.padded = padded;
        }
    }

    static void apply(ModelView view) {
        Map<String, Unit> units = buildUnits(view);
        if (units.size() < 2) return;

        Map<String, Unit> unitByElement = new LinkedHashMap<>();
        for (Unit u : units.values()) {
            for (String id : u.memberIds) unitByElement.put(id, u);
        }

        // The unit with the most elements anchors the diagram; everything else may move.
        Unit anchor = units.values().stream()
            .max((a, b) -> Integer.compare(a.members.size(), b.members.size()))
            .orElseThrow();

        for (int pass = 0; pass < PASSES; pass++) {
            for (Unit unit : units.values()) {
                if (unit == anchor) continue;
                relax(view, unit, units, unitByElement);
            }
        }
    }

    private static Map<String, Unit> buildUnits(ModelView view) {
        Map<String, Unit> units = new LinkedHashMap<>();

        for (ElementView ev : view.getElements()) {
            Element element = ev.getElement();
            Element top = topLevelAncestor(element);

            String key;
            boolean padded;
            if (top != element) {
                key = "cluster:" + top.getId();
                padded = true;
            } else if (element instanceof GroupableElement ge
                    && ge.getGroup() != null && !ge.getGroup().isBlank()) {
                key = "group:" + ge.getGroup();
                padded = true;
            } else {
                key = "element:" + element.getId();
                padded = false;
            }

            Unit unit = units.computeIfAbsent(key, k -> new Unit(padded || k.startsWith("cluster:")));
            unit.members.add(ev);
            unit.memberIds.add(element.getId());
        }

        for (Unit unit : units.values()) {
            computeBounds(view, unit);
        }
        // Units with no measurable bounds (e.g. only boundary-rendered nodes) can't move
        units.values().removeIf(u -> u.minX > u.maxX);
        return units;
    }

    private static void relax(ModelView view, Unit unit, Map<String, Unit> units,
                              Map<String, Unit> unitByElement) {
        // External connections: (own endpoint centre, partner endpoint centre)
        List<double[]> springs = new ArrayList<>();
        for (RelationshipView rv : view.getRelationships()) {
            String srcId = rv.getRelationship().getSourceId();
            String dstId = rv.getRelationship().getDestinationId();
            boolean srcIn = unit.memberIds.contains(srcId);
            boolean dstIn = unit.memberIds.contains(dstId);
            if (srcIn == dstIn) continue;

            Element own = view.getModel().getElement(srcIn ? srcId : dstId);
            Element other = view.getModel().getElement(srcIn ? dstId : srcId);
            double[] ownC = center(view, own);
            double[] otherC = center(view, other);
            if (ownC == null || otherC == null) continue;
            springs.add(new double[]{ownC[0], ownC[1], otherC[0], otherC[1]});
        }
        if (springs.isEmpty()) return;

        // Pull towards the centroid of partners
        double tx = 0, ty = 0;
        for (double[] s : springs) {
            tx += s[2] - s[0];
            ty += s[3] - s[1];
        }
        tx /= springs.size();
        ty /= springs.size();
        if (Math.abs(tx) < 20 && Math.abs(ty) < 20) return;

        // Translate the unit's box, then push it out of overlaps with other units
        double[] bb = {unit.minX + tx, unit.minY + ty, unit.maxX + tx, unit.maxY + ty};
        if (!separate(bb, unit, units)) return;

        int dx = (int) Math.round(bb[0] - unit.minX);
        int dy = (int) Math.round(bb[1] - unit.minY);
        if (dx == 0 && dy == 0) return;

        double before = 0, after = 0;
        for (double[] s : springs) {
            before += Math.hypot(s[2] - s[0], s[3] - s[1]);
            after += Math.hypot(s[2] - (s[0] + dx), s[3] - (s[1] + dy));
        }
        if (after > before * MIN_IMPROVEMENT) return;

        move(view, unit, unitByElement, dx, dy);
    }

    /**
     * Padded (cluster/group) units already carry empty frame space that gives
     * relationship labels room; bare elements don't, so they need a wider gap
     * for the label block between them.
     */
    private static int requiredGap(Unit a, Unit b) {
        int gap = MARGIN;
        if (!a.padded) gap += 90;
        if (!b.padded) gap += 90;
        return gap;
    }

    /** Pushes bb out of any overlapping unit boxes; false if no clear spot found. */
    private static boolean separate(double[] bb, Unit self, Map<String, Unit> units) {
        for (int i = 0; i < 150; i++) {
            Unit hit = null;
            int gap = 0;
            for (Unit other : units.values()) {
                if (other == self) continue;
                int g = requiredGap(self, other);
                if (bb[0] < other.maxX + g && bb[2] > other.minX - g
                        && bb[1] < other.maxY + g && bb[3] > other.minY - g) {
                    hit = other;
                    gap = g;
                    break;
                }
            }
            if (hit == null) return true;

            double pushRight = (hit.maxX + gap) - bb[0];
            double pushLeft  = bb[2] - (hit.minX - gap);
            double pushDown  = (hit.maxY + gap) - bb[1];
            double pushUp    = bb[3] - (hit.minY - gap);
            double min = Math.min(Math.min(pushRight, pushLeft), Math.min(pushDown, pushUp));

            if (min == pushRight)     { bb[0] += pushRight; bb[2] += pushRight; }
            else if (min == pushLeft) { bb[0] -= pushLeft;  bb[2] -= pushLeft; }
            else if (min == pushDown) { bb[1] += pushDown;  bb[3] += pushDown; }
            else                      { bb[1] -= pushUp;    bb[3] -= pushUp; }
        }
        return false;
    }

    private static void move(ModelView view, Unit unit, Map<String, Unit> unitByElement,
                             int dx, int dy) {
        for (ElementView ev : unit.members) {
            ev.setX(ev.getX() + dx);
            ev.setY(ev.getY() + dy);
        }
        unit.minX += dx;
        unit.maxX += dx;
        unit.minY += dy;
        unit.maxY += dy;

        // Internal edges keep their routing (translated); external edges are re-routed
        // directly because their Graphviz bend points no longer make sense.
        for (RelationshipView rv : view.getRelationships()) {
            boolean srcIn = unit == unitByElement.get(rv.getRelationship().getSourceId());
            boolean dstIn = unit == unitByElement.get(rv.getRelationship().getDestinationId());
            if (!srcIn && !dstIn) continue;
            if (rv.getVertices() == null || rv.getVertices().isEmpty()) continue;

            if (srcIn && dstIn) {
                List<Vertex> translated = new ArrayList<>();
                for (Vertex v : rv.getVertices()) {
                    translated.add(new Vertex(v.getX() + dx, v.getY() + dy));
                }
                rv.setVertices(translated);
            } else {
                rv.setVertices(new ArrayList<>());
            }
        }
    }

    private static void computeBounds(ModelView view, Unit unit) {
        unit.minX = unit.minY = Double.MAX_VALUE;
        unit.maxX = unit.maxY = -Double.MAX_VALUE;
        int maxDepth = 0;
        for (ElementView ev : unit.members) {
            if (ev.getElement() instanceof DeploymentNode) continue; // boundary box derives from children
            int[] rect = rect(view, ev);
            unit.minX = Math.min(unit.minX, rect[0]);
            unit.minY = Math.min(unit.minY, rect[1]);
            unit.maxX = Math.max(unit.maxX, rect[0] + rect[2]);
            unit.maxY = Math.max(unit.maxY, rect[1] + rect[3]);
            maxDepth = Math.max(maxDepth, nestingDepth(ev.getElement()));
        }
        if (unit.padded && unit.minX <= unit.maxX) {
            // Every nesting level wraps another boundary frame around the elements
            int padding = BASE_PADDING + PADDING_PER_LEVEL * maxDepth;
            unit.minX -= padding;
            unit.minY -= padding;
            unit.maxX += padding;
            unit.maxY += padding;
        }
    }

    private static int nestingDepth(Element element) {
        int depth = 0;
        for (Element current = element; current.getParent() != null; current = current.getParent()) {
            depth++;
        }
        return depth;
    }

    private static double[] center(ModelView view, Element element) {
        if (element == null || element instanceof DeploymentNode) return null;
        ElementView ev = view.getElementView(element);
        if (ev == null) return null;
        int[] rect = rect(view, ev);
        return new double[]{rect[0] + rect[2] / 2.0, rect[1] + rect[3] / 2.0};
    }

    /** The element rect as the renderer will draw it, including text-fit growth. */
    private static int[] rect(ModelView view, ElementView ev) {
        Element element = ev.getElement();
        ElementStyle style = view.getViewSet().getConfiguration().getStyles().findElementStyle(element);
        return Shapes.elementRect(view, element, style, ev.getX(), ev.getY());
    }

    private static Element topLevelAncestor(Element element) {
        Element current = element;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current;
    }
}
