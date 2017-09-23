package com.graphql.diff.reporting;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;

public class DifferenceEvent {

    public enum Level {
        INFO, WARNING, ERROR
    }

    public enum Category {
        MISSING, STRICTER, INVALID
    }

    public enum TypeOfType {

        Operation, Object, Interface, Union, Enum, Scalar, InputObject, Unknown;

        public static TypeOfType getTypeOfType(TypeDefinition def) {
            if (def instanceof ObjectTypeDefinition) {
                return Object;
            }
            if (def instanceof InterfaceTypeDefinition) {
                return Interface;
            }
            if (def instanceof UnionTypeDefinition) {
                return Union;
            }
            if (def instanceof ScalarTypeDefinition) {
                return Scalar;
            }
            if (def instanceof EnumTypeDefinition) {
                return Enum;
            }
            if (def instanceof InputObjectTypeDefinition) {
                return InputObject;
            }
            return Unknown;
        }


    }


    private final Level level;
    private final Category category;
    private final String typeName;
    private final String fieldName;
    private final TypeOfType typeOfType;
    private final String reasonMsg;

    DifferenceEvent(Level level, Category category, String typeName, String fieldName, TypeOfType typeOfType, String reasonMsg) {
        this.level = level;
        this.category = category;
        this.typeName = typeName;
        this.fieldName = fieldName;
        this.typeOfType = typeOfType;
        this.reasonMsg = reasonMsg;
    }

    public String getTypeName() {
        return typeName;
    }

    public TypeOfType getTypeOfType() {
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

    @Override
    public String toString() {
        return "DifferenceEvent{" +
                "level=" + level +
                ", category=" + category +
                ", typeName='" + typeName + '\'' +
                ", typeOfType=" + typeOfType +
                ", fieldName=" + fieldName +
                ", reasonMsg='" + reasonMsg + '\'' +
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
        TypeOfType typeOfType;
        String reasonMsg;
        String fieldName;

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

        public Builder typeOfType(TypeOfType typeOfType) {
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

        public DifferenceEvent build() {
            return new DifferenceEvent(level, category, typeName, fieldName, typeOfType, reasonMsg);
        }


    }
}
