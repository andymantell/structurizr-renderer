package com.structurizr.renderer.layout;

import com.structurizr.Workspace;

public interface LayoutStrategy {
    void applyLayout(Workspace workspace) throws Exception;
}
