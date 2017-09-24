package com.graphql.diff.reporting;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;

/**
 * The kind of things that can be in a graphql type system
 */
public enum TypeKind {

    Operation, Object, Interface, Union, Enum, Scalar, InputObject, Unknown;

    public static TypeKind getTypeKind(TypeDefinition def) {
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
