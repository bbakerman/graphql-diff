package com.graphql.diff;

import java.util.Map;

/**
 */
public class DiffSet {

    private final Map<String, Object> introspectionLeft;
    private final Map<String, Object> introspectionRight;

    public DiffSet(Map<String, Object> introspectionLeft, Map<String, Object> introspectionRight) {
        this.introspectionLeft = introspectionLeft;
        this.introspectionRight = introspectionRight;
    }

    public Map<String, Object> getLeft() {
        return introspectionLeft;
    }

    public Map<String, Object> getRight() {
        return introspectionRight;
    }
}
