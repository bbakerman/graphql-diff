package com.graphql.diff;

import com.graphql.diff.reporting.DifferenceEvent;
import com.graphql.diff.reporting.DifferenceEvent.TypeOfType;
import com.graphql.diff.reporting.DifferenceReporter;
import com.graphql.diff.util.TypeInfo;
import graphql.introspection.IntrospectionResultToSchema;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.graphql.diff.reporting.DifferenceEvent.Category.INVALID;
import static com.graphql.diff.reporting.DifferenceEvent.Category.MISSING;
import static com.graphql.diff.reporting.DifferenceEvent.Category.STRICTER;
import static com.graphql.diff.reporting.DifferenceEvent.apiBreakage;
import static com.graphql.diff.reporting.DifferenceEvent.newInfo;
import static com.graphql.diff.util.TypeInfo.getAstDesc;
import static com.graphql.diff.util.TypeInfo.typeInfo;

@SuppressWarnings("ConstantConditions")
public class SchemaDiff {

    public static class Options {

        final boolean enforceDirectives;

        Options(boolean enforceDirectives) {
            this.enforceDirectives = enforceDirectives;
        }

        public Options enforceDirectives() {
            return new Options(true);
        }

        public static Options defaultOptions() {
            return new Options(false);
        }

    }

    private static class CallContext {
        final List<String> examinedTypes = new ArrayList<>();
        final Stack<String> currentTypes = new Stack<>();
        final Document leftDoc;
        final Document rightDoc;

        private CallContext(Document leftDoc, Document rightDoc) {
            this.leftDoc = leftDoc;
            this.rightDoc = rightDoc;
        }

        boolean examiningType(String typeName) {
            if (examinedTypes.contains(typeName)) {
                return true;
            }
            examinedTypes.add(typeName);
            currentTypes.push(typeName);
            return false;
        }

        void exitType() {
            currentTypes.pop();
        }

        <T extends TypeDefinition> Optional<T> getLeftTypeDef(Type type, Class<T> typeDefClass) {
            return getType(getTypeName(type), typeDefClass, leftDoc);
        }

        <T extends TypeDefinition> Optional<T> getRightTypeDef(Type type, Class<T> typeDefClass) {
            return getType(getTypeName(type), typeDefClass, rightDoc);
        }

        private <T extends TypeDefinition> Optional<T> getType(String typeName, Class<T> typeDefClass, Document doc) {
            if (typeName == null) {
                return Optional.empty();
            }
            return doc.getDefinitions().stream()
                    .filter(def -> typeDefClass.isAssignableFrom(def.getClass()))
                    .map(typeDefClass::cast)
                    .filter(defT -> defT.getName().equals(typeName))
                    .findFirst();
        }
    }

    private final Options options;

    private final DifferenceReporter differenceReporter;

    public SchemaDiff(DifferenceReporter differenceReporter) {
        this(differenceReporter, Options.defaultOptions());
    }


    public SchemaDiff(DifferenceReporter differenceReporter, Options options) {
        this.options = options;
        this.differenceReporter = differenceReporter;
    }

    @SuppressWarnings("unchecked")
    public void diffSchema(DiffSet diffSet) {

        Map<String, Object> left = diffSet.getLeft();
        Map<String, Object> right = diffSet.getRight();

        diffSchemaImpl(left, right);
    }

    private void diffSchemaImpl(Map<String, Object> left, Map<String, Object> right) {
        Document leftDoc = new IntrospectionResultToSchema().createSchemaDefinition(left);
        Document rightDoc = new IntrospectionResultToSchema().createSchemaDefinition(right);

        CallContext callContext = new CallContext(leftDoc, rightDoc);


        Optional<SchemaDefinition> leftSchemaDef = getSchemaDef(leftDoc);
        Optional<SchemaDefinition> rightSchemaDef = getSchemaDef(rightDoc);


        // check query operation
        checkOperation("query", callContext, leftSchemaDef, rightSchemaDef);
        checkOperation("mutation", callContext, leftSchemaDef, rightSchemaDef);
        checkOperation("subscription", callContext, leftSchemaDef, rightSchemaDef);
    }

    private void checkOperation(String opName, CallContext callContext, Optional<SchemaDefinition> leftSchemaDef, Optional<SchemaDefinition> rightSchemaDef) {
        // if schema declaration is missing then it is assumed to contain Query / Mutation / Subscription
        Optional<OperationTypeDefinition> leftOpTypeDef;
        leftOpTypeDef = leftSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> callContext.getLeftTypeDef(type, ObjectTypeDefinition.class), opName));

        Optional<OperationTypeDefinition> rightOpTypeDef;
        rightOpTypeDef = rightSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> callContext.getRightTypeDef(type, ObjectTypeDefinition.class), opName));

        // must be new
        if (!leftOpTypeDef.isPresent()) {
            return;
        }

        if (leftOpTypeDef.isPresent() && !rightOpTypeDef.isPresent()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(capitalize(opName))
                    .fieldName(opName)
                    .typeOfType(TypeOfType.Operation)
                    .reasonMsg("The new API no longer has the operation '%s'", opName)
                    .build());
            return;
        }

        OperationTypeDefinition leftOpTypeDefinition = leftOpTypeDef.get();
        OperationTypeDefinition rightOpTypeDefinition = rightOpTypeDef.get();

        Type leftType = leftOpTypeDefinition.getType();
        //
        // if we have no left op, then it must have been added (which is ok)
        Optional<TypeDefinition> leftTD = callContext.getLeftTypeDef(leftType, TypeDefinition.class);
        if (!leftTD.isPresent()) {
            return;
        }
        checkType(callContext, leftType, rightOpTypeDefinition.getType());
    }

    private void checkType(CallContext callContext, Type leftType, Type rightType) {
        String typeName = getTypeName(leftType);

        if (callContext.examiningType(typeName)) {
            return;
        }
        if (isSystemScalar(typeName)) {
            return;
        }
        if (isReservedType(typeName)) {
            return;
        }
        Optional<TypeDefinition> leftTD = callContext.getLeftTypeDef(leftType, TypeDefinition.class);
        Optional<TypeDefinition> rightTD = callContext.getRightTypeDef(rightType, TypeDefinition.class);

        if (!leftTD.isPresent()) {
            differenceReporter.report(newInfo()
                    .typeName(typeName)
                    .reasonMsg("Type '%s' is missing ???", typeName)
                    .build());
            return;

        }
        TypeDefinition left = leftTD.get();

        differenceReporter.report(newInfo()
                .typeName(typeName)
                .typeOfType(getTypeOfType(left))
                .reasonMsg("Examining type '%s' ...", typeName)
                .build());

        if (!rightTD.isPresent()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(typeName)
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API does not have a type called '%s'", typeName)
                    .build());
            callContext.exitType();
            return;
        }
        TypeDefinition right = rightTD.get();
        if (!left.getClass().equals(right.getClass())) {
            differenceReporter.report(apiBreakage()
                    .category(INVALID)
                    .typeName(typeName)
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has changed '%s' from a '%s' to a '%s'", typeName, getTypeOfType(left), getTypeOfType(right))
                    .build());
            callContext.exitType();
            return;
        }
        if (left instanceof ObjectTypeDefinition) {
            checkObjectType(callContext, (ObjectTypeDefinition) left, (ObjectTypeDefinition) right);
        }
        if (left instanceof InterfaceTypeDefinition) {
            checkInterfaceType(callContext, (InterfaceTypeDefinition) left, (InterfaceTypeDefinition) right);
        }
        if (left instanceof UnionTypeDefinition) {
            checkUnionType((UnionTypeDefinition) left, (UnionTypeDefinition) right);
        }
        if (left instanceof InputObjectTypeDefinition) {
            checkInputObjectType((InputObjectTypeDefinition) left, (InputObjectTypeDefinition) right);
        }
        if (left instanceof EnumTypeDefinition) {
            checkEnumType((EnumTypeDefinition) left, (EnumTypeDefinition) right);
        }
        if (left instanceof ScalarTypeDefinition) {
            checkScalarType((ScalarTypeDefinition) left, (ScalarTypeDefinition) right);
        }
        callContext.exitType();
    }

    private boolean isReservedType(String typeName) {
        return typeName.startsWith("__");
    }

    private final static Set<String> SYSTEM_SCALARS = new HashSet<>();

    static {
        SYSTEM_SCALARS.add("ID");
        SYSTEM_SCALARS.add("Boolean");
        SYSTEM_SCALARS.add("String");
        SYSTEM_SCALARS.add("Byte");
        SYSTEM_SCALARS.add("Char");
        SYSTEM_SCALARS.add("Short");
        SYSTEM_SCALARS.add("Int");
        SYSTEM_SCALARS.add("Long");
        SYSTEM_SCALARS.add("Float");
        SYSTEM_SCALARS.add("Double");
        SYSTEM_SCALARS.add("BigInteger");
        SYSTEM_SCALARS.add("BigDecimal");
    }

    private boolean isSystemScalar(String typeName) {
        return SYSTEM_SCALARS.contains(typeName);
    }

    private void checkObjectType(CallContext callContext, ObjectTypeDefinition left, ObjectTypeDefinition right) {
        Map<String, FieldDefinition> leftFields = sortedMap(left.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> rightFields = sortedMap(right.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(callContext, left, leftFields, rightFields);

        checkImplements(callContext, left, left.getImplements(), right.getImplements());

        checkDirectives(left, right);
    }

    private void checkInterfaceType(CallContext callContext, InterfaceTypeDefinition left, InterfaceTypeDefinition right) {
        Map<String, FieldDefinition> leftFields = sortedMap(left.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> rightFields = sortedMap(right.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(callContext, left, leftFields, rightFields);

        checkDirectives(left, right);
    }

    private void checkUnionType(UnionTypeDefinition left, UnionTypeDefinition right) {
        Map<String, Type> leftMemberTypes = sortedMap(left.getMemberTypes(), SchemaDiff::getTypeName);
        Map<String, Type> rightMemberTypes = sortedMap(right.getMemberTypes(), SchemaDiff::getTypeName);

        for (Map.Entry<String, Type> entry : leftMemberTypes.entrySet()) {
            String leftMemberTypeName = entry.getKey();
            if (!rightMemberTypes.containsKey(leftMemberTypeName)) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API does not contain union member type '%s'", leftMemberTypeName)
                        .build());
            }
        }
        checkDirectives(left, right);
    }


    private void checkInputObjectType(InputObjectTypeDefinition left, InputObjectTypeDefinition right) {

        checkInputFields(left, left.getInputValueDefinitions(), right.getInputValueDefinitions());

        checkDirectives(left, right);
    }

    private void checkInputFields(TypeDefinition left, List<InputValueDefinition> leftIVD, List<InputValueDefinition> rightIVD) {
        Map<String, InputValueDefinition> leftDefinitionMap = sortedMap(leftIVD, InputValueDefinition::getName);
        Map<String, InputValueDefinition> rightDefinitionMap = sortedMap(rightIVD, InputValueDefinition::getName);

        for (String inputFieldName : leftDefinitionMap.keySet()) {
            InputValueDefinition leftField = leftDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> rightField = Optional.ofNullable(rightDefinitionMap.get(inputFieldName));

            if (!rightField.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .fieldName(leftField.getName())
                        .reasonMsg("The new API is missing an input field '%s'", leftField.getName())
                        .build());
            } else {
                DifferenceEvent.Category category = checkTypeWithNonNullAndList(leftField.getType(), rightField.get().getType());
                if (category != null) {
                    differenceReporter.report(apiBreakage()
                            .category(category)
                            .typeName(left.getName())
                            .typeOfType(getTypeOfType(left))
                            .fieldName(leftField.getName())
                            .reasonMsg("The new API has changed input field '%s' from type '%s' to '%s'",
                                    leftField.getName(), getAstDesc(leftField.getType()), getAstDesc(rightField.get().getType()))
                            .build());
                }
            }
        }

        // check new fields are not mandatory
        for (String inputFieldName : rightDefinitionMap.keySet()) {
            InputValueDefinition rightField = rightDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> leftField = Optional.ofNullable(leftDefinitionMap.get(inputFieldName));

            if (!leftField.isPresent()) {
                // new fields MUST not be mandatory
                if (typeInfo(rightField.getType()).isNonNull()) {
                    differenceReporter.report(apiBreakage()
                            .category(STRICTER)
                            .typeName(left.getName())
                            .typeOfType(getTypeOfType(left))
                            .fieldName(rightField.getName())
                            .reasonMsg("The new API has made the new input field '%s' non null (aka mandatory)", rightField.getName())
                            .build());
                }
            }
        }
    }

    private void checkEnumType(EnumTypeDefinition left, EnumTypeDefinition right) {
        Map<String, EnumValueDefinition> leftDefinitionMap = sortedMap(left.getEnumValueDefinitions(), EnumValueDefinition::getName);
        Map<String, EnumValueDefinition> rightDefinitionMap = sortedMap(right.getEnumValueDefinitions(), EnumValueDefinition::getName);

        for (String enumName : leftDefinitionMap.keySet()) {
            EnumValueDefinition leftEnum = leftDefinitionMap.get(enumName);
            Optional<EnumValueDefinition> rightEnum = Optional.ofNullable(rightDefinitionMap.get(enumName));

            if (!rightEnum.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API is missing an enum value '%s'", leftEnum.getName())
                        .build());
            }

            checkDirectives(left, leftEnum.getDirectives(), rightEnum.get().getDirectives());
        }
        checkDirectives(left, right);
    }

    private void checkScalarType(ScalarTypeDefinition left, ScalarTypeDefinition right) {
        checkDirectives(left, right);
    }

    private void checkImplements(CallContext callContext, ObjectTypeDefinition left, List<Type> leftImplements, List<Type> rightImplements) {
        Map<String, Type> leftImplementsMap = sortedMap(leftImplements, t -> ((TypeName) t).getName());
        Map<String, Type> rightImplementsMap = sortedMap(rightImplements, t -> ((TypeName) t).getName());

        for (Map.Entry<String, Type> entry : leftImplementsMap.entrySet()) {
            InterfaceTypeDefinition leftInterface = callContext.getLeftTypeDef(entry.getValue(), InterfaceTypeDefinition.class).get();
            Optional<InterfaceTypeDefinition> rightInterface = callContext.getRightTypeDef(rightImplementsMap.get(entry.getKey()), InterfaceTypeDefinition.class);
            if (!rightInterface.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API is missing the interface named '%s'", leftInterface.getName())
                        .build());
            } else {
                checkInterfaceType(callContext, leftInterface, rightInterface.get());
            }
        }
    }


    private void checkFields(CallContext callContext, TypeDefinition leftDef, Map<String, FieldDefinition> leftFields, Map<String, FieldDefinition> rightFields) {
        for (Map.Entry<String, FieldDefinition> entry : leftFields.entrySet()) {

            String fieldName = entry.getKey();
            differenceReporter.report(newInfo()
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .fieldName(fieldName)
                    .reasonMsg("\tfield '%s' ...", fieldName)
                    .build());


            FieldDefinition rightField = rightFields.get(fieldName);
            if (rightField == null) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(leftDef.getName())
                        .typeOfType(getTypeOfType(leftDef))
                        .fieldName(fieldName)
                        .reasonMsg("The new API is missing the field '%s'", fieldName)
                        .build());
            } else {
                checkField(callContext, leftDef, entry.getValue(), rightField);
            }
        }
    }


    private void checkField(CallContext callContext, TypeDefinition left, FieldDefinition leftField, FieldDefinition rightField) {
        Type leftFieldType = leftField.getType();
        Type rightFieldType = rightField.getType();

        DifferenceEvent.Category category = checkTypeWithNonNullAndList(leftFieldType, rightFieldType);
        if (category != null) {
            differenceReporter.report(apiBreakage()
                    .category(category)
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .fieldName(leftField.getName())
                    .reasonMsg("The new API has changed field '%s' from type '%s' to '%s'", leftField.getName(), getAstDesc(leftFieldType), getAstDesc(rightFieldType))
                    .build());
        }

        checkFieldArguments(left, leftField, leftField.getInputValueDefinitions(), rightField.getInputValueDefinitions());

        checkDirectives(left, leftField.getDirectives(), rightField.getDirectives());
        //
        // and down we go again recursively via fields
        //
        checkType(callContext, leftFieldType, rightFieldType);
    }

    private void checkFieldArguments(TypeDefinition leftDef, FieldDefinition leftField, List<InputValueDefinition> leftInputValueDefinitions, List<InputValueDefinition> rightInputValueDefinitions) {
        Map<String, InputValueDefinition> leftArgsMap = sortedMap(leftInputValueDefinitions, InputValueDefinition::getName);
        Map<String, InputValueDefinition> rightArgMap = sortedMap(rightInputValueDefinitions, InputValueDefinition::getName);

        if (leftArgsMap.size() > rightArgMap.size()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .fieldName(leftField.getName())
                    .reasonMsg("The new API has less arguments on field '%s' of type '%s' than the old API", leftField.getName(), leftDef.getName())
                    .build());
            return;
        }

        for (Map.Entry<String, InputValueDefinition> entry : leftArgsMap.entrySet()) {

            String argName = entry.getKey();
            differenceReporter.report(newInfo()
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .fieldName(leftField.getName())
                    .reasonMsg("\tfield argument '%s' ...", argName)
                    .build());


            InputValueDefinition rightArg = rightArgMap.get(argName);
            if (rightArg == null) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(leftDef.getName())
                        .typeOfType(getTypeOfType(leftDef))
                        .fieldName(leftField.getName())
                        .reasonMsg("The new API is missing the field argument '%s'", argName)
                        .build());
            } else {
                checkFieldArg(leftDef, leftField, entry.getValue(), rightArg);
            }
        }
    }

    private void checkFieldArg(TypeDefinition leftDef, FieldDefinition leftField, InputValueDefinition leftArg, InputValueDefinition rightArg) {

        Type leftArgType = leftArg.getType();
        Type rightArgType = rightArg.getType();

        DifferenceEvent.Category category = checkTypeWithNonNullAndList(leftArgType, rightArgType);
        if (category != null) {
            differenceReporter.report(apiBreakage()
                    .category(category)
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .fieldName(leftField.getName())
                    .reasonMsg("The new API has changed field '%s' argument '%s' from type '%s' to '%s'", leftField.getName(), getAstDesc(leftArgType), getAstDesc(rightArgType))
                    .build());
        }

        Value leftValue = leftArg.getDefaultValue();
        Value rightValue = rightArg.getDefaultValue();
        if (leftValue != null && rightValue != null) {
            if (!leftValue.getClass().equals(rightValue.getClass())) {
                differenceReporter.report(apiBreakage()
                        .category(INVALID)
                        .typeName(leftDef.getName())
                        .typeOfType(getTypeOfType(leftDef))
                        .fieldName(leftField.getName())
                        .reasonMsg("The new API has changed default value types on argument named '%s' on field '%s' of type '%s", leftArg.getName(), leftField.getName(), leftDef.getName())
                        .build());
            }
        }

        checkDirectives(leftDef, leftArg.getDirectives(), rightArg.getDirectives());
    }

    private void checkDirectives(TypeDefinition left, TypeDefinition right) {
        List<Directive> leftDirectives = left.getDirectives();
        List<Directive> rightDirectives = right.getDirectives();

        checkDirectives(left, leftDirectives, rightDirectives);
    }

    void checkDirectives(TypeDefinition left, List<Directive> leftDirectives, List<Directive> rightDirectives) {
        if (!options.enforceDirectives) {
            return;
        }

        Map<String, Directive> leftDirectivesMap = sortedMap(leftDirectives, Directive::getName);
        Map<String, Directive> rightDirectivesMap = sortedMap(rightDirectives, Directive::getName);

        for (String directiveName : leftDirectivesMap.keySet()) {
            Directive leftDirective = leftDirectivesMap.get(directiveName);
            Optional<Directive> rightDirective = Optional.ofNullable(rightDirectivesMap.get(directiveName));
            if (!rightDirective.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API does not have a directive named '%s", directiveName)
                        .build());
                continue;
            }


            Map<String, Argument> leftArgumentsByName = new TreeMap<>(leftDirective.getArgumentsByName());
            Map<String, Argument> rightArgumentsByName = new TreeMap<>(rightDirective.get().getArgumentsByName());

            if (leftArgumentsByName.size() > rightArgumentsByName.size()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API has less arguments on directive '%s' than the old API", directiveName)
                        .build());
                return;
            }

            for (String argName : leftArgumentsByName.keySet()) {
                Argument leftArgument = leftArgumentsByName.get(argName);
                Optional<Argument> rightArgument = Optional.ofNullable(rightArgumentsByName.get(argName));

                if (!rightArgument.isPresent()) {
                    differenceReporter.report(apiBreakage()
                            .category(MISSING)
                            .typeName(left.getName())
                            .typeOfType(getTypeOfType(left))
                            .reasonMsg("The new API does not have an argument named '%s' on directive '%s", argName, directiveName)
                            .build());
                } else {
                    Value leftValue = leftArgument.getValue();
                    Value rightValue = rightArgument.get().getValue();
                    if (leftValue != null && rightValue != null) {
                        if (!leftValue.getClass().equals(rightValue.getClass())) {
                            differenceReporter.report(apiBreakage()
                                    .category(INVALID)
                                    .typeName(left.getName())
                                    .typeOfType(getTypeOfType(left))
                                    .reasonMsg("The new API has changed value types on argument named '%s' on directive '%s", argName, directiveName)
                                    .build());
                        }
                    }
                }
            }
        }
    }

    DifferenceEvent.Category checkTypeWithNonNullAndList(Type leftType, Type rightType) {
        TypeInfo leftTypeInfo = typeInfo(leftType);
        TypeInfo rightTypeInfo = typeInfo(rightType);

        if (!leftTypeInfo.getName().equals(rightTypeInfo.getName())) {
            return INVALID;
        }

        while (true) {
            //
            // its allowed to get more less strict in the new but not more strict
            if (leftTypeInfo.isNonNull() && rightTypeInfo.isNonNull()) {
                leftTypeInfo = leftTypeInfo.unwrapOne();
                rightTypeInfo = rightTypeInfo.unwrapOne();
            } else if (leftTypeInfo.isNonNull() && !rightTypeInfo.isNonNull()) {
                leftTypeInfo = leftTypeInfo.unwrapOne();
            } else if (!leftTypeInfo.isNonNull() && rightTypeInfo.isNonNull()) {
                return STRICTER;
            }
            // lists
            if (leftTypeInfo.isList() && !rightTypeInfo.isList()) {
                return INVALID;
            }
            // plain
            if (leftTypeInfo.isPlain()) {
                if (!rightTypeInfo.isPlain()) {
                    return INVALID;
                }
                break;
            }
            leftTypeInfo = leftTypeInfo.unwrapOne();
            rightTypeInfo = rightTypeInfo.unwrapOne();
        }
        return null;
    }

    private TypeOfType getTypeOfType(TypeDefinition def) {
        return TypeOfType.getTypeOfType(def);
    }


    private static String getTypeName(Type type) {
        if (type == null) {
            return null;
        }
        return typeInfo(type).getName();
    }

    @SuppressWarnings("ConstantConditions")
    private Optional<SchemaDefinition> getSchemaDef(Document document) {
        return document.getDefinitions().stream()
                .filter(d -> d instanceof SchemaDefinition)
                .map(SchemaDefinition.class::cast)
                .findFirst();
    }

    private Optional<OperationTypeDefinition> getOpDef(String opName, SchemaDefinition schemaDef) {
        return schemaDef.getOperationTypeDefinitions().stream().filter(otd -> otd.getName().equals(opName)).findFirst();
    }


    // looks for a type called `Query|Mutation|Subscription` and if it exist then assumes it as an operation def
    private Optional<OperationTypeDefinition> synthOperationTypeDefinition(Function<Type, Optional<ObjectTypeDefinition>> typeReteriver, String opName) {
        TypeName type = new TypeName(capitalize(opName));
        Optional<ObjectTypeDefinition> typeDef = typeReteriver.apply(type);
        if (typeDef.isPresent()) {
            return Optional.of(new OperationTypeDefinition(opName, type));
        } else {
            return Optional.empty();
        }
    }

    private <T> Map<String, T> sortedMap(List<T> listOfNamedThings, Function<T, String> nameFunc) {
        Map<String, T> map = listOfNamedThings.stream().collect(Collectors.toMap(nameFunc, Function.identity(), (x, y) -> y));
        return new TreeMap<>(map);
    }

    private static String capitalize(String name) {
        if (name != null && name.length() != 0) {
            char[] chars = name.toCharArray();
            chars[0] = Character.toUpperCase(chars[0]);
            return new String(chars);
        } else {
            return name;
        }
    }

}