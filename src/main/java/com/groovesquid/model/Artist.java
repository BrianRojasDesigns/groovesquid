package com.groovesquid.model;

public class Artist {
    private String id;
    private String name;

    public Artist(Object id, Object name) {
        this.id = id.toString();
        this.name = name.toString().trim();
    }
    
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }

    public String toString() {
        return getName();
    }
}
