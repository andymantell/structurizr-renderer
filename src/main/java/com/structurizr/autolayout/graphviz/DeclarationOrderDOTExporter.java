package com.structurizr.autolayout.graphviz;

import com.structurizr.export.IndentingWriter;
import com.structurizr.model.Container;
import com.structurizr.model.Element;
import com.structurizr.model.GroupableElement;
import com.structurizr.model.SoftwareSystem;
import com.structurizr.view.DynamicView;
import com.structurizr.view.ModelView;
import com.structurizr.view.RelationshipView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link DOTExporter} that feeds Graphviz elements and relationships in
 * model declaration order instead of lexicographic ID order.
 *
 * The stock exporter sorts with {@code Comparator.comparing(Element::getId)} —
 * but IDs are numeric strings, so "10" sorts before "2" and any model with ten
 * or more elements reaches Graphviz in a semi-arbitrary shuffle. Graphviz
 * seeds its within-rank ordering and tie-breaking from input order, so that
 * shuffle leaks into the layout. Sorting numerically restores true declaration
 * order, which makes the layout track the order things are written in the DSL
 * — the same behaviour as the Structurizr web renderer, which feeds Dagre in
 * view order.
 */
class DeclarationOrderDOTExporter extends DOTExporter {

    private static final Comparator<Element> DECLARATION_ORDER =
        Comparator.comparingLong(e -> numericId(e.getId()));

    DeclarationOrderDOTExporter(RankDirection rankDirection, double rankSeparation, double nodeSeparation) {
        super(rankDirection, rankSeparation, nodeSeparation);
    }

    private static long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE; // non-numeric ids keep their relative order (stable sort)
        }
    }

    /**
     * The superclass re-sorts the list it is given lexicographically before
     * writing, so hand it a list whose sort() substitutes declaration order.
     * This keeps the (non-trivial) group-nesting logic in the superclass.
     */
    @Override
    protected void writeElements(ModelView view, List<GroupableElement> elements, IndentingWriter writer) {
        List<GroupableElement> declarationOrdered = new ArrayList<>(elements) {
            @Override
            public void sort(Comparator<? super GroupableElement> ignored) {
                super.sort(DECLARATION_ORDER);
            }
        };
        super.writeElements(view, declarationOrdered, writer);
    }

    @Override
    protected void writeRelationships(ModelView view, IndentingWriter writer) {
        Collection<RelationshipView> relationshipList;
        if (view instanceof DynamicView) {
            relationshipList = view.getRelationships(); // step order, already meaningful
        } else {
            relationshipList = view.getRelationships().stream()
                .sorted(Comparator.comparingLong(rv -> numericId(rv.getRelationship().getId())))
                .collect(Collectors.toList());
        }
        for (RelationshipView relationshipView : relationshipList) {
            writeRelationship(view, relationshipView, writer);
        }
    }

    @Override
    protected List<SoftwareSystem> getBoundarySoftwareSystems(ModelView view) {
        List<SoftwareSystem> systems = new ArrayList<>(super.getBoundarySoftwareSystems(view));
        systems.sort(DECLARATION_ORDER);
        return systems;
    }

    @Override
    protected List<Container> getBoundaryContainers(ModelView view) {
        List<Container> containers = new ArrayList<>(super.getBoundaryContainers(view));
        containers.sort(DECLARATION_ORDER);
        return containers;
    }
}
