package com.structurizr.renderer.svg;

import com.structurizr.export.Diagram;
import com.structurizr.view.ModelView;

public class SvgDiagram extends Diagram {

    public SvgDiagram(ModelView view, String definition) {
        super(view, definition);
    }

    @Override
    public String getFileExtension() {
        return "svg";
    }
}
