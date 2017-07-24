package com.graphql.diff;

import com.graphql.diff.reporting.DifferenceEvent.TypeOfTypes;
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

import static com.graphql.diff.reporting.DifferenceEvent.apiBreakage;
import static com.graphql.diff.reporting.DifferenceEvent.newInfo;
import static com.graphql.diff.util.TypeInfo.getAstDesc;

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

        if (!preChecks(left, right)) {
            return;
        }

        diffSchemaImpl(left, right);
    }

    private boolean preChecks(Map<String, Object> left, Map<String, Object> right) {
        return true;
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

    private void checkOperation(String opName, CallContext callContext, Optional<SchemaDefinition> leftSchemaDef, Optional<SchemaDefinition> rightSchemaDef) {
        // if schema decl is missing then it is assumed to contain Query / Mutation / Subscription
        Optional<OperationTypeDefinition> leftOpTypeDef;
        leftOpTypeDef = leftSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> Optional.of(new OperationTypeDefinition(opName, new TypeName(capitalize(opName)))));

        Optional<OperationTypeDefinition> rightOpTypeDef;
        rightOpTypeDef = rightSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> Optional.of(new OperationTypeDefinition(opName, new TypeName(capitalize(opName)))));

        if (!leftOpTypeDef.isPresent() && !rightOpTypeDef.isPresent()) {
            return;
        }

        if (leftOpTypeDef.isPresent() != rightOpTypeDef.isPresent()) {
            differenceReporter.report(apiBreakage()
                    .typeName(opName)
                    .typeOfType(TypeOfTypes.Operation)
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
                    .reasonMsg("Examining type '%s' ...", typeName)
                    .build());

        }
        TypeDefinition left = leftTD.get();

        differenceReporter.report(newInfo()
                .typeName(typeName)
                .typeOfType(getTypeOfType(left))
                .reasonMsg("Examining type '%s' ...", typeName)
                .build());

        if (!rightTD.isPresent()) {
            differenceReporter.report(apiBreakage()
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

        if (leftMemberTypes.size() > rightMemberTypes.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has changed enum from '%s' to '%s'", left.getName(), right.getName())
                    .build());
        }

        for (Map.Entry<String, Type> entry : leftMemberTypes.entrySet()) {
            String leftMemberTypeName = entry.getKey();
            if (!rightMemberTypes.containsKey(leftMemberTypeName)) {
                differenceReporter.report(apiBreakage()
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API does not contain union member type '%s'", leftMemberTypeName)
                        .build());
            }
        }
        checkDirectives(left, right);
    }

    private void checkInputObjectType(InputObjectTypeDefinition left, InputObjectTypeDefinition right) {

        Map<String, InputValueDefinition> leftDefinitionMap = sortedMap(left.getInputValueDefinitions(), InputValueDefinition::getName);
        Map<String, InputValueDefinition> rightDefinitionMap = sortedMap(right.getInputValueDefinitions(), InputValueDefinition::getName);

        if (leftDefinitionMap.size() > rightDefinitionMap.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has more input fields")
                    .build());
        }

        for (String inputFieldName : leftDefinitionMap.keySet()) {
            InputValueDefinition leftInputField = leftDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> rightInputField = Optional.ofNullable(rightDefinitionMap.get(inputFieldName));

            if (!rightInputField.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API is missing an input field '%s'", leftInputField.getName())
                        .build());
            } else {
                if (!checkNonNullAndList(leftInputField.getType(), rightInputField.get().getType())) {
                    differenceReporter.report(apiBreakage()
                            .typeName(left.getName())
                            .typeOfType(getTypeOfType(left))
                            .reasonMsg("The new API has changed field '%s' from type '%s' to '%s'",
                                    leftInputField.getName(), getAstDesc(leftInputField.getType()), getAstDesc(rightInputField.get().getType()))
                            .build());
                }
            }
        }

        checkDirectives(left, right);
    }

    private void checkEnumType(EnumTypeDefinition left, EnumTypeDefinition right) {
        if (!left.getName().equals(right.getName())) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has changed enum from '%s' to '%s'", left.getName(), right.getName())
                    .build());
        }

        Map<String, EnumValueDefinition> leftDefinitionMap = sortedMap(left.getEnumValueDefinitions(), EnumValueDefinition::getName);
        Map<String, EnumValueDefinition> rightDefinitionMap = sortedMap(right.getEnumValueDefinitions(), EnumValueDefinition::getName);

        if (leftDefinitionMap.size() > rightDefinitionMap.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has more enum values than the old API")
                    .build());
        }

        for (String enumName : leftDefinitionMap.keySet()) {
            EnumValueDefinition leftEnum = leftDefinitionMap.get(enumName);
            Optional<EnumValueDefinition> rightEnum = Optional.ofNullable(rightDefinitionMap.get(enumName));

            if (!rightEnum.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API is missing an enum value '%s'", leftEnum.getName())
                        .build());
            }
        }

        checkDirectives(left, right);

    }

    private void checkScalarType(ScalarTypeDefinition left, ScalarTypeDefinition right) {
        if (!left.getName().equals(right.getName())) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has changed scalar from '%s' to '%s'", left.getName(), right.getName())
                    .build());
        }

        checkDirectives(left, right);
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

        if (leftDirectivesMap.size() > rightDirectivesMap.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API implements less directives than the old API")
                    .build());
            return;
        }

        for (String directiveName : leftDirectivesMap.keySet()) {
            Directive leftDirective = leftDirectivesMap.get(directiveName);
            Optional<Directive> rightDirective = Optional.ofNullable(rightDirectivesMap.get(directiveName));
            if (!rightDirective.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .typeName(left.getName())
                        .typeOfType(getTypeOfType(left))
                        .reasonMsg("The new API does not have a directive named '%s", directiveName)
                        .build());
            }


            Map<String, Argument> leftArgumentsByName = leftDirective.getArgumentsByName();
            Map<String, Argument> rightArgumentsByName = rightDirective.get().getArgumentsByName();

            if (leftArgumentsByName.size() > rightArgumentsByName.size()) {
                differenceReporter.report(apiBreakage()
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
                            .typeName(left.getName())
                            .typeOfType(getTypeOfType(left))
                            .reasonMsg("The new API does not have an argument named '%s' on directive '%s", argName, directiveName)
                            .build());
                } else {
                    if (leftArgument.getValue() != null && rightArgument.get().getValue() != null) {
                        if (!leftArgument.getValue().getClass().equals(rightArgument.get().getValue().getClass())) {
                            differenceReporter.report(apiBreakage()
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

    private void checkImplements(CallContext callContext, ObjectTypeDefinition left, List<Type> leftImplements, List<Type> rightImplements) {
        if (leftImplements.size() > rightImplements.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API implements less interfaces than the old API")
                    .build());
            return;
        }
        Map<String, Type> leftImplementsMap = sortedMap(leftImplements, t -> ((TypeName) t).getName());
        Map<String, Type> rightImplementsMap = sortedMap(rightImplements, t -> ((TypeName) t).getName());

        for (Map.Entry<String, Type> entry : leftImplementsMap.entrySet()) {
            InterfaceTypeDefinition leftInterface = callContext.getLeftTypeDef(entry.getValue(), InterfaceTypeDefinition.class).get();
            Optional<InterfaceTypeDefinition> rightInterface = callContext.getRightTypeDef(rightImplementsMap.get(entry.getKey()), InterfaceTypeDefinition.class);
            if (!rightInterface.isPresent()) {
                differenceReporter.report(apiBreakage()
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
        if (leftFields.size() > rightFields.size()) {
            differenceReporter.report(apiBreakage()
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .reasonMsg("The new API has less fields than the old API")
                    .build());
            return;
        }
        for (Map.Entry<String, FieldDefinition> entry : leftFields.entrySet()) {

            String fieldName = entry.getKey();
            differenceReporter.report(newInfo()
                    .typeName(leftDef.getName())
                    .typeOfType(getTypeOfType(leftDef))
                    .reasonMsg("\tfield '%s' ...", fieldName)
                    .build());


            FieldDefinition rightField = rightFields.get(fieldName);
            if (rightField == null) {
                differenceReporter.report(apiBreakage()
                        .typeName(leftDef.getName())
                        .typeOfType(getTypeOfType(leftDef))
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

        if (!checkNonNullAndList(leftFieldType, rightFieldType)) {
            differenceReporter.report(apiBreakage()
                    .typeName(left.getName())
                    .typeOfType(getTypeOfType(left))
                    .reasonMsg("The new API has changed field '%s' from type '%s' to '%s'", leftField.getName(), getAstDesc(leftFieldType), getAstDesc(rightFieldType))
                    .build());
        }

        checkDirectives(left, leftField.getDirectives(), rightField.getDirectives());

        checkType(callContext, leftFieldType, rightFieldType);
    }

    boolean checkNonNullAndList(Type leftFieldType, Type rightFieldType) {
        TypeInfo leftTypeInfo = TypeInfo.typeInfo(leftFieldType);
        TypeInfo rightTypeInfo = TypeInfo.typeInfo(rightFieldType);

        while (true) {
            //
            // its allowed to get more less strict in the new but not more strict
            if (leftTypeInfo.isNonNull() && rightTypeInfo.isNonNull()) {
                leftTypeInfo = leftTypeInfo.unwrapOne();
                rightTypeInfo = rightTypeInfo.unwrapOne();
            } else if (leftTypeInfo.isNonNull() && !rightTypeInfo.isNonNull()) {
                leftTypeInfo = leftTypeInfo.unwrapOne();
            } else if (!leftTypeInfo.isNonNull() && rightTypeInfo.isNonNull()) {
                return false;
            }
            // lists
            if (leftTypeInfo.isList() && !rightTypeInfo.isList()) {
                return false;
            }
            // plain
            if (leftTypeInfo.isPlain()) {
                if (!rightTypeInfo.isPlain()) {
                    return false;
                }
                break;
            }
            leftTypeInfo = leftTypeInfo.unwrapOne();
            rightTypeInfo = rightTypeInfo.unwrapOne();
        }
        return true;

    }


    private TypeOfTypes getTypeOfType(TypeDefinition def) {
        return TypeOfTypes.getTypeOfType(def);
    }

    private static String getTypeName(Type type) {
        if (type == null) {
            return null;
        }
        return TypeInfo.typeInfo(type).getName();
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

    private <T> Map<String, T> sortedMap(List<T> listOfNamedThings, Function<T, String> nameFunc) {
        Map<String, T> map = listOfNamedThings.stream().collect(Collectors.toMap(nameFunc, Function.identity(), (x, y) -> y));
        return new TreeMap<>(map);
    }

    static String capitalize(String name) {
        if (name != null && name.length() != 0) {
            char[] chars = name.toCharArray();
            chars[0] = Character.toUpperCase(chars[0]);
            return new String(chars);
        } else {
            return name;
        }
    }


}