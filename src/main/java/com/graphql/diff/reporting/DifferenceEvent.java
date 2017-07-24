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

    public enum TypeOfTypes {

        Operation, Object, Interface, Union, Enum, Scalar, InputObject, Unknown;

        public static TypeOfTypes getTypeOfType(TypeDefinition def) {
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
    private final String typeName;
    private final TypeOfTypes typeOfType;
    private final String reasonMsg;

    DifferenceEvent(Level level, String typeName, TypeOfTypes typeOfType, String reasonMsg) {
        this.level = level;
        this.typeName = typeName;
        this.typeOfType = typeOfType;
        this.reasonMsg = reasonMsg;
    }

    public String getTypeName() {
        return typeName;
    }

    public TypeOfTypes getTypeOfType() {
        return typeOfType;
    }

    public String getReasonMsg() {
        return reasonMsg;
    }

    public Level getLevel() {
        return level;
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

        Level level;
        String typeName;
        TypeOfTypes typeOfType;
        String reasonMsg;

        public Builder level(Level level) {
            this.level = level;
            return this;
        }


        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public Builder typeOfType(TypeOfTypes typeOfType) {
            this.typeOfType = typeOfType;
            return this;
        }

        public Builder reasonMsg(String format, Object... args) {
            this.reasonMsg = String.format(format, args);
            return this;
        }

        public DifferenceEvent build() {
            return new DifferenceEvent(level, typeName, typeOfType, reasonMsg);
        }


    }
}
