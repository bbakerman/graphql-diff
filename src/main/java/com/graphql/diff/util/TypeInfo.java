package com.graphql.diff.util;

import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.Type;
import graphql.language.TypeName;

import java.util.Stack;

public class TypeInfo {

    public static TypeInfo typeInfo(Type type) {
        return new TypeInfo(type);
    }

    private final Type rawType;
    private final TypeName typeName;
    private final Stack<Class<? extends Type>> decoration = new Stack<>();

    TypeInfo(Type type) {
        this.rawType = type;
        while (!(type instanceof TypeName)) {
            if (type instanceof NonNullType) {
                decoration.push(NonNullType.class);
                type = ((NonNullType) type).getType();
            }
            if (type instanceof ListType) {
                decoration.push(ListType.class);
                type = ((ListType) type).getType();
            }
        }
        this.typeName = (TypeName) type;
    }

    public Type getRawType() {
        return rawType;
    }

    public TypeName getTypeName() {
        return typeName;
    }

    public String getName() {
        return typeName.getName();
    }

    public String getAstDesc() {
        return getAstDesc(rawType);
    }

    public boolean isList() {
        return rawType instanceof ListType;
    }

    public boolean isNonNull() {
        return rawType instanceof NonNullType;
    }

    public boolean isPlain() {
        return !isList() && !isNonNull();
    }

    public static String getAstDesc(Type type) {
        if (type instanceof NonNullType) {
            return getAstDesc(((NonNullType) type).getType()) + "!";
        }
        if (type instanceof ListType) {
            return "[" + getAstDesc(((ListType) type).getType()) + "]";
        }
        return ((TypeName) type).getName();
    }

    public TypeInfo unwrapOne() {
        if (rawType instanceof NonNullType) {
            return typeInfo(((NonNullType) rawType).getType());
        }
        if (rawType instanceof ListType) {
            return typeInfo(((ListType) rawType).getType());
        }
        return this;
    }

    public Stack<Class<? extends Type>> getTypeStack() {
        Stack<Class<? extends Type>> stack = new Stack<>();
        stack.addAll(decoration);
        return stack;
    }

    @Override
    public String toString() {
        return "TypeInfo{" +
                "typename=" + typeName +
                ", nonNull=" + isNonNull() +
                ", list=" + isList() +
                '}';
    }
}

