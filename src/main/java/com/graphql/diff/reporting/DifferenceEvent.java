package com.graphql.diff.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.toList;

public class DifferenceEvent {

    public enum Level {
        INFO, WARNING, ERROR
    }

    public enum Category {
        MISSING, STRICTER, INVALID
    }


    private final Level level;
    private final Category category;
    private final String typeName;
    private final String fieldName;
    private final TypeKind typeOfType;
    private final String reasonMsg;
    private final List<String> components;

    DifferenceEvent(Level level, Category category, String typeName, String fieldName, TypeKind typeOfType, String reasonMsg, List<String> components) {
        this.level = level;
        this.category = category;
        this.typeName = typeName;
        this.fieldName = fieldName;
        this.typeOfType = typeOfType;
        this.reasonMsg = reasonMsg;
        this.components = components;
    }

    public String getTypeName() {
        return typeName;
    }

    public TypeKind getTypeOfType() {
        return typeOfType;
    }

    public String getReasonMsg() {
        return reasonMsg;
    }

    public Level getLevel() {
        return level;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Category getCategory() {
        return category;
    }

    public List<String> getComponents() {
        return new ArrayList<>(components);
    }

    @Override
    public String toString() {
        return "DifferenceEvent{" +
                " reasonMsg='" + reasonMsg + '\'' +
                ", level=" + level +
                ", category=" + category +
                ", typeName='" + typeName + '\'' +
                ", typeKind=" + typeOfType +
                ", fieldName=" + fieldName +
                '}';
    }

    public static Builder newInfo() {
        return new Builder().level(Level.INFO);
    }

    public static Builder newWarning() {
        return new Builder().level(Level.WARNING);
    }

    public static Builder apiBreakage() {
        return new Builder().level(Level.ERROR);
    }


    public static class Builder {

        Category category;
        Level level;
        String typeName;
        TypeKind typeOfType;
        String reasonMsg;
        String fieldName;
        List<String> components = new ArrayList<>();

        public Builder level(Level level) {
            this.level = level;
            return this;
        }


        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder typeKind(TypeKind typeOfType) {
            this.typeOfType = typeOfType;
            return this;
        }

        public Builder category(Category category) {
            this.category = category;
            return this;
        }

        public Builder reasonMsg(String format, Object... args) {
            this.reasonMsg = String.format(format, args);
            return this;
        }

        public Builder components(Object... args) {
            components.addAll(Arrays.stream(args).map(String::valueOf).collect(toList()));
            return this;
        }

        public DifferenceEvent build() {
            return new DifferenceEvent(level, category, typeName, fieldName, typeOfType, reasonMsg, components);
        }


    }
}
