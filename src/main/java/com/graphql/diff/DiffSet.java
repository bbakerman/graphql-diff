package com.graphql.diff;

import java.util.Map;

/**
 */
public class DiffSet {

    private final Map<String, Object> introspectionOld;
    private final Map<String, Object> introspectionNew;

    public DiffSet(Map<String, Object> introspectionOld, Map<String, Object> introspectionNew) {
        this.introspectionOld = introspectionOld;
        this.introspectionNew = introspectionNew;
    }

    public Map<String, Object> getOld() {
        return introspectionOld;
    }

    public Map<String, Object> getNew() {
        return introspectionNew;
    }
}
