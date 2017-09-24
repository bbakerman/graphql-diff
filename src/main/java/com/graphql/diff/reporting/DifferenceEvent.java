package com.graphql.diff.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * This represents the events that the {@link com.graphql.diff.SchemaDiff} outputs.
 */
public class DifferenceEvent {

    private final DifferenceLevel level;
    private final DifferenceCategory category;
    private final TypeKind typeOfType;
    private final String typeName;
    private final String fieldName;
    private final String reasonMsg;
    private final List<String> components;

    DifferenceEvent(DifferenceLevel level, DifferenceCategory category, String typeName, String fieldName, TypeKind typeOfType, String reasonMsg, List<String> components) {
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

    public TypeKind getTypeKind() {
        return typeOfType;
    }

    public String getReasonMsg() {
        return reasonMsg;
    }

    public DifferenceLevel getLevel() {
        return level;
    }

    public String getFieldName() {
        return fieldName;
    }

    public DifferenceCategory getCategory() {
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
        return new Builder().level(DifferenceLevel.INFO);
    }

    public static Builder apiDanger() {
        return new Builder().level(DifferenceLevel.DANGEROUS);
    }

    public static Builder apiBreakage() {
        return new Builder().level(DifferenceLevel.BREAKING);
    }


    public static class Builder {

        DifferenceCategory category;
        DifferenceLevel level;
        String typeName;
        TypeKind typeOfType;
        String reasonMsg;
        String fieldName;
        List<String> components = new ArrayList<>();

        public Builder level(DifferenceLevel level) {
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

        public Builder category(DifferenceCategory category) {
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
