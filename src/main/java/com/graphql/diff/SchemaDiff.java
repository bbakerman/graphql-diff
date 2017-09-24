package com.graphql.diff;

import com.graphql.diff.reporting.DifferenceEvent;
import com.graphql.diff.reporting.DifferenceReporter;
import com.graphql.diff.reporting.TypeKind;
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
import static com.graphql.diff.reporting.TypeKind.getTypeKind;
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
        final Document oldDoc;
        final Document newDoc;

        private CallContext(Document oldDoc, Document newDoc) {
            this.oldDoc = oldDoc;
            this.newDoc = newDoc;
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

        <T extends TypeDefinition> Optional<T> getOldTypeDef(Type type, Class<T> typeDefClass) {
            return getType(getTypeName(type), typeDefClass, oldDoc);
        }

        <T extends TypeDefinition> Optional<T> getNewTypeDef(Type type, Class<T> typeDefClass) {
            return getType(getTypeName(type), typeDefClass, newDoc);
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

        Map<String, Object> oldAPI = diffSet.getOld();
        Map<String, Object> newAPI = diffSet.getNew();

        diffSchemaImpl(oldAPI, newAPI);
    }

    private void diffSchemaImpl(Map<String, Object> oldApi, Map<String, Object> newApi) {
        Document oldDoc = new IntrospectionResultToSchema().createSchemaDefinition(oldApi);
        Document newDoc = new IntrospectionResultToSchema().createSchemaDefinition(newApi);

        CallContext callContext = new CallContext(oldDoc, newDoc);


        Optional<SchemaDefinition> oldSchemaDef = getSchemaDef(oldDoc);
        Optional<SchemaDefinition> newSchemaDef = getSchemaDef(newDoc);


        // check query operation
        checkOperation("query", callContext, oldSchemaDef, newSchemaDef);
        checkOperation("mutation", callContext, oldSchemaDef, newSchemaDef);
        checkOperation("subscription", callContext, oldSchemaDef, newSchemaDef);

        differenceReporter.onEnd();
    }

    private void checkOperation(String opName, CallContext callContext, Optional<SchemaDefinition> oldSchemaDef, Optional<SchemaDefinition> newSchemaDef) {
        // if schema declaration is missing then it is assumed to contain Query / Mutation / Subscription
        Optional<OperationTypeDefinition> oldOpTypeDef;
        oldOpTypeDef = oldSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> callContext.getOldTypeDef(type, ObjectTypeDefinition.class), opName));

        Optional<OperationTypeDefinition> newOpTypeDef;
        newOpTypeDef = newSchemaDef
                .map(schemaDefinition -> getOpDef(opName, schemaDefinition))
                .orElseGet(() -> synthOperationTypeDefinition(type -> callContext.getNewTypeDef(type, ObjectTypeDefinition.class), opName));

        // must be new
        if (!oldOpTypeDef.isPresent()) {
            return;
        }

        if (oldOpTypeDef.isPresent() && !newOpTypeDef.isPresent()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(capitalize(opName))
                    .fieldName(opName)
                    .typeKind(TypeKind.Operation)
                    .components(opName)
                    .reasonMsg("The new API no longer has the operation '%s'", opName)
                    .build());
            return;
        }

        OperationTypeDefinition oldOpTypeDefinition = oldOpTypeDef.get();
        OperationTypeDefinition newOpTypeDefinition = newOpTypeDef.get();

        Type oldType = oldOpTypeDefinition.getType();
        //
        // if we have no old op, then it must have been added (which is ok)
        Optional<TypeDefinition> oldTD = callContext.getOldTypeDef(oldType, TypeDefinition.class);
        if (!oldTD.isPresent()) {
            return;
        }
        checkType(callContext, oldType, newOpTypeDefinition.getType());
    }

    private void checkType(CallContext callContext, Type oldType, Type newType) {
        String typeName = getTypeName(oldType);

        if (callContext.examiningType(typeName)) {
            return;
        }
        if (isSystemScalar(typeName)) {
            return;
        }
        if (isReservedType(typeName)) {
            return;
        }
        Optional<TypeDefinition> oldTD = callContext.getOldTypeDef(oldType, TypeDefinition.class);
        Optional<TypeDefinition> newTD = callContext.getNewTypeDef(newType, TypeDefinition.class);

        if (!oldTD.isPresent()) {
            differenceReporter.report(newInfo()
                    .typeName(typeName)
                    .reasonMsg("Type '%s' is missing", typeName)
                    .build());
            return;

        }
        TypeDefinition oldDef = oldTD.get();

        differenceReporter.report(newInfo()
                .typeName(typeName)
                .typeKind(getTypeKind(oldDef))
                .reasonMsg("Examining type '%s' ...", typeName)
                .build());

        if (!newTD.isPresent()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(typeName)
                    .typeKind(getTypeKind(oldDef))
                    .reasonMsg("The new API does not have a type called '%s'", typeName)
                    .build());
            callContext.exitType();
            return;
        }
        TypeDefinition newDef = newTD.get();
        if (!oldDef.getClass().equals(newDef.getClass())) {
            differenceReporter.report(apiBreakage()
                    .category(INVALID)
                    .typeName(typeName)
                    .typeKind(getTypeKind(oldDef))
                    .components(getTypeKind(oldDef), getTypeKind(newDef))
                    .reasonMsg("The new API has changed '%s' from a '%s' to a '%s'", typeName, getTypeKind(oldDef), getTypeKind(newDef))
                    .build());
            callContext.exitType();
            return;
        }
        if (oldDef instanceof ObjectTypeDefinition) {
            checkObjectType(callContext, (ObjectTypeDefinition) oldDef, (ObjectTypeDefinition) newDef);
        }
        if (oldDef instanceof InterfaceTypeDefinition) {
            checkInterfaceType(callContext, (InterfaceTypeDefinition) oldDef, (InterfaceTypeDefinition) newDef);
        }
        if (oldDef instanceof UnionTypeDefinition) {
            checkUnionType((UnionTypeDefinition) oldDef, (UnionTypeDefinition) newDef);
        }
        if (oldDef instanceof InputObjectTypeDefinition) {
            checkInputObjectType((InputObjectTypeDefinition) oldDef, (InputObjectTypeDefinition) newDef);
        }
        if (oldDef instanceof EnumTypeDefinition) {
            checkEnumType((EnumTypeDefinition) oldDef, (EnumTypeDefinition) newDef);
        }
        if (oldDef instanceof ScalarTypeDefinition) {
            checkScalarType((ScalarTypeDefinition) oldDef, (ScalarTypeDefinition) newDef);
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

    private void checkObjectType(CallContext callContext, ObjectTypeDefinition oldDef, ObjectTypeDefinition newDef) {
        Map<String, FieldDefinition> oldFields = sortedMap(oldDef.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> newFields = sortedMap(newDef.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(callContext, oldDef, oldFields, newFields);

        checkImplements(callContext, oldDef, oldDef.getImplements(), newDef.getImplements());

        checkDirectives(oldDef, newDef);
    }

    private void checkInterfaceType(CallContext callContext, InterfaceTypeDefinition oldDef, InterfaceTypeDefinition newDef) {
        Map<String, FieldDefinition> oldFields = sortedMap(oldDef.getFieldDefinitions(), FieldDefinition::getName);
        Map<String, FieldDefinition> newFields = sortedMap(newDef.getFieldDefinitions(), FieldDefinition::getName);

        checkFields(callContext, oldDef, oldFields, newFields);

        checkDirectives(oldDef, newDef);
    }

    private void checkUnionType(UnionTypeDefinition oldDef, UnionTypeDefinition newDef) {
        Map<String, Type> oldMemberTypes = sortedMap(oldDef.getMemberTypes(), SchemaDiff::getTypeName);
        Map<String, Type> newMemberTypes = sortedMap(newDef.getMemberTypes(), SchemaDiff::getTypeName);

        for (Map.Entry<String, Type> entry : oldMemberTypes.entrySet()) {
            String oldMemberTypeName = entry.getKey();
            if (!newMemberTypes.containsKey(oldMemberTypeName)) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(oldMemberTypeName)
                        .reasonMsg("The new API does not contain union member type '%s'", oldMemberTypeName)
                        .build());
            }
        }
        checkDirectives(oldDef, newDef);
    }


    private void checkInputObjectType(InputObjectTypeDefinition oldDef, InputObjectTypeDefinition newDef) {

        checkInputFields(oldDef, oldDef.getInputValueDefinitions(), newDef.getInputValueDefinitions());

        checkDirectives(oldDef, newDef);
    }

    private void checkInputFields(TypeDefinition old, List<InputValueDefinition> oldIVD, List<InputValueDefinition> newIVD) {
        Map<String, InputValueDefinition> oldDefinitionMap = sortedMap(oldIVD, InputValueDefinition::getName);
        Map<String, InputValueDefinition> newDefinitionMap = sortedMap(newIVD, InputValueDefinition::getName);

        for (String inputFieldName : oldDefinitionMap.keySet()) {
            InputValueDefinition oldField = oldDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> newField = Optional.ofNullable(newDefinitionMap.get(inputFieldName));

            if (!newField.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .fieldName(oldField.getName())
                        .reasonMsg("The new API is missing an input field '%s'", oldField.getName())
                        .build());
            } else {
                DifferenceEvent.Category category = checkTypeWithNonNullAndList(oldField.getType(), newField.get().getType());
                if (category != null) {
                    differenceReporter.report(apiBreakage()
                            .category(category)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .fieldName(oldField.getName())
                            .components(getAstDesc(oldField.getType()), getAstDesc(newField.get().getType()))
                            .reasonMsg("The new API has changed input field '%s' from type '%s' to '%s'",
                                    oldField.getName(), getAstDesc(oldField.getType()), getAstDesc(newField.get().getType()))
                            .build());
                }
            }
        }

        // check new fields are not mandatory
        for (String inputFieldName : newDefinitionMap.keySet()) {
            InputValueDefinition newField = newDefinitionMap.get(inputFieldName);
            Optional<InputValueDefinition> oldField = Optional.ofNullable(oldDefinitionMap.get(inputFieldName));

            if (!oldField.isPresent()) {
                // new fields MUST not be mandatory
                if (typeInfo(newField.getType()).isNonNull()) {
                    differenceReporter.report(apiBreakage()
                            .category(STRICTER)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .fieldName(newField.getName())
                            .reasonMsg("The new API has made the new input field '%s' non null and hence more strict for old consumers", newField.getName())
                            .build());
                }
            }
        }
    }

    private void checkEnumType(EnumTypeDefinition oldDef, EnumTypeDefinition newDef) {
        Map<String, EnumValueDefinition> oldDefinitionMap = sortedMap(oldDef.getEnumValueDefinitions(), EnumValueDefinition::getName);
        Map<String, EnumValueDefinition> newDefinitionMap = sortedMap(newDef.getEnumValueDefinitions(), EnumValueDefinition::getName);

        for (String enumName : oldDefinitionMap.keySet()) {
            EnumValueDefinition oldEnum = oldDefinitionMap.get(enumName);
            Optional<EnumValueDefinition> newEnum = Optional.ofNullable(newDefinitionMap.get(enumName));

            if (!newEnum.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .components(oldEnum.getName())
                        .reasonMsg("The new API is missing an enum value '%s'", oldEnum.getName())
                        .build());
            } else {
                checkDirectives(oldDef, oldEnum.getDirectives(), newEnum.get().getDirectives());
            }
        }
        checkDirectives(oldDef, newDef);
    }

    private void checkScalarType(ScalarTypeDefinition oldDef, ScalarTypeDefinition newDef) {
        checkDirectives(oldDef, newDef);
    }

    private void checkImplements(CallContext callContext, ObjectTypeDefinition old, List<Type> oldImplements, List<Type> newImplements) {
        Map<String, Type> oldImplementsMap = sortedMap(oldImplements, t -> ((TypeName) t).getName());
        Map<String, Type> newImplementsMap = sortedMap(newImplements, t -> ((TypeName) t).getName());

        for (Map.Entry<String, Type> entry : oldImplementsMap.entrySet()) {
            InterfaceTypeDefinition oldInterface = callContext.getOldTypeDef(entry.getValue(), InterfaceTypeDefinition.class).get();
            Optional<InterfaceTypeDefinition> newInterface = callContext.getNewTypeDef(newImplementsMap.get(entry.getKey()), InterfaceTypeDefinition.class);
            if (!newInterface.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(oldInterface.getName())
                        .reasonMsg("The new API is missing the interface named '%s'", oldInterface.getName())
                        .build());
            } else {
                checkInterfaceType(callContext, oldInterface, newInterface.get());
            }
        }
    }


    private void checkFields(CallContext callContext, TypeDefinition oldDef, Map<String, FieldDefinition> oldFields, Map<String, FieldDefinition> newFields) {
        for (Map.Entry<String, FieldDefinition> entry : oldFields.entrySet()) {

            String fieldName = entry.getKey();
            differenceReporter.report(newInfo()
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(fieldName)
                    .reasonMsg("\tfield '%s' ...", fieldName)
                    .build());


            FieldDefinition newField = newFields.get(fieldName);
            if (newField == null) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(fieldName)
                        .reasonMsg("The new API is missing the field '%s'", fieldName)
                        .build());
            } else {
                checkField(callContext, oldDef, entry.getValue(), newField);
            }
        }
    }


    private void checkField(CallContext callContext, TypeDefinition old, FieldDefinition oldField, FieldDefinition newField) {
        Type oldFieldType = oldField.getType();
        Type newFieldType = newField.getType();

        DifferenceEvent.Category category = checkTypeWithNonNullAndList(oldFieldType, newFieldType);
        if (category != null) {
            differenceReporter.report(apiBreakage()
                    .category(category)
                    .typeName(old.getName())
                    .typeKind(getTypeKind(old))
                    .fieldName(oldField.getName())
                    .components(getAstDesc(oldFieldType), getAstDesc(newFieldType))
                    .reasonMsg("The new API has changed field '%s' from type '%s' to '%s'", oldField.getName(), getAstDesc(oldFieldType), getAstDesc(newFieldType))
                    .build());
        }

        checkFieldArguments(callContext, old, oldField, oldField.getInputValueDefinitions(), newField.getInputValueDefinitions());

        checkDirectives(old, oldField.getDirectives(), newField.getDirectives());
        //
        // and down we go again recursively via fields
        //
        checkType(callContext, oldFieldType, newFieldType);
    }

    private void checkFieldArguments(CallContext callContext, TypeDefinition oldDef, FieldDefinition oldField, List<InputValueDefinition> oldInputValueDefinitions, List<InputValueDefinition> newInputValueDefinitions) {
        Map<String, InputValueDefinition> oldArgsMap = sortedMap(oldInputValueDefinitions, InputValueDefinition::getName);
        Map<String, InputValueDefinition> newArgMap = sortedMap(newInputValueDefinitions, InputValueDefinition::getName);

        if (oldArgsMap.size() > newArgMap.size()) {
            differenceReporter.report(apiBreakage()
                    .category(MISSING)
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .reasonMsg("The new API has less arguments on field '%s' of type '%s' than the old API", oldField.getName(), oldDef.getName())
                    .build());
            return;
        }

        for (Map.Entry<String, InputValueDefinition> entry : oldArgsMap.entrySet()) {

            String argName = entry.getKey();
            differenceReporter.report(newInfo()
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .reasonMsg("\tfield argument '%s' ...", argName)
                    .build());


            InputValueDefinition newArg = newArgMap.get(argName);
            if (newArg == null) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(oldField.getName())
                        .components(argName)
                        .reasonMsg("The new API is missing the field argument '%s'", argName)
                        .build());
            } else {
                checkFieldArg(callContext, oldDef, oldField, entry.getValue(), newArg);
            }
        }

        // check new fields are not mandatory
        for (Map.Entry<String, InputValueDefinition> entry : newArgMap.entrySet()) {
            InputValueDefinition newArg = entry.getValue();
            Optional<InputValueDefinition> oldArg = Optional.ofNullable(oldArgsMap.get(newArg.getName()));

            if (!oldArg.isPresent()) {
                // new args MUST not be mandatory
                if (typeInfo(newArg.getType()).isNonNull()) {
                    differenceReporter.report(apiBreakage()
                            .category(STRICTER)
                            .typeName(oldDef.getName())
                            .typeKind(getTypeKind(oldDef))
                            .fieldName(oldField.getName())
                            .components(newArg.getName())
                            .reasonMsg("The new API has made the new argument '%s' on field '%s' non null and hence more strict for old consumers", newArg.getName(), oldField.getName())
                            .build());
                }
            }
        }

    }

    private void checkFieldArg(CallContext callContext, TypeDefinition oldDef, FieldDefinition oldField, InputValueDefinition oldArg, InputValueDefinition newArg) {

        Type oldArgType = oldArg.getType();
        Type newArgType = newArg.getType();

        DifferenceEvent.Category category = checkTypeWithNonNullAndList(oldArgType, newArgType);
        if (category != null) {
            differenceReporter.report(apiBreakage()
                    .category(category)
                    .typeName(oldDef.getName())
                    .typeKind(getTypeKind(oldDef))
                    .fieldName(oldField.getName())
                    .components(getAstDesc(oldArgType), getAstDesc(newArgType))
                    .reasonMsg("The new API has changed field '%s' argument '%s' from type '%s' to '%s'", oldField.getName(), oldArg.getName(), getAstDesc(oldArgType), getAstDesc(newArgType))
                    .build());
        } else {
            //
            // and down we go again recursively via arg types
            //
            checkType(callContext, oldArgType, newArgType);
        }

        Value oldValue = oldArg.getDefaultValue();
        Value newValue = newArg.getDefaultValue();
        if (oldValue != null && newValue != null) {
            if (!oldValue.getClass().equals(newValue.getClass())) {
                differenceReporter.report(apiBreakage()
                        .category(INVALID)
                        .typeName(oldDef.getName())
                        .typeKind(getTypeKind(oldDef))
                        .fieldName(oldField.getName())
                        .components(oldArg.getName())
                        .reasonMsg("The new API has changed default value types on argument named '%s' on field '%s' of type '%s", oldArg.getName(), oldField.getName(), oldDef.getName())
                        .build());
            }
        }

        checkDirectives(oldDef, oldArg.getDirectives(), newArg.getDirectives());
    }

    private void checkDirectives(TypeDefinition oldDef, TypeDefinition newDef) {
        List<Directive> oldDirectives = oldDef.getDirectives();
        List<Directive> newDirectives = newDef.getDirectives();

        checkDirectives(oldDef, oldDirectives, newDirectives);
    }

    void checkDirectives(TypeDefinition old, List<Directive> oldDirectives, List<Directive> newDirectives) {
        if (!options.enforceDirectives) {
            return;
        }

        Map<String, Directive> oldDirectivesMap = sortedMap(oldDirectives, Directive::getName);
        Map<String, Directive> newDirectivesMap = sortedMap(newDirectives, Directive::getName);

        for (String directiveName : oldDirectivesMap.keySet()) {
            Directive oldDirective = oldDirectivesMap.get(directiveName);
            Optional<Directive> newDirective = Optional.ofNullable(newDirectivesMap.get(directiveName));
            if (!newDirective.isPresent()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(directiveName)
                        .reasonMsg("The new API does not have a directive named '%s", directiveName)
                        .build());
                continue;
            }


            Map<String, Argument> oldArgumentsByName = new TreeMap<>(oldDirective.getArgumentsByName());
            Map<String, Argument> newArgumentsByName = new TreeMap<>(newDirective.get().getArgumentsByName());

            if (oldArgumentsByName.size() > newArgumentsByName.size()) {
                differenceReporter.report(apiBreakage()
                        .category(MISSING)
                        .typeName(old.getName())
                        .typeKind(getTypeKind(old))
                        .components(directiveName)
                        .reasonMsg("The new API has less arguments on directive '%s' than the old API", directiveName)
                        .build());
                return;
            }

            for (String argName : oldArgumentsByName.keySet()) {
                Argument oldArgument = oldArgumentsByName.get(argName);
                Optional<Argument> newArgument = Optional.ofNullable(newArgumentsByName.get(argName));

                if (!newArgument.isPresent()) {
                    differenceReporter.report(apiBreakage()
                            .category(MISSING)
                            .typeName(old.getName())
                            .typeKind(getTypeKind(old))
                            .components(directiveName, argName)
                            .reasonMsg("The new API does not have an argument named '%s' on directive '%s", argName, directiveName)
                            .build());
                } else {
                    Value oldValue = oldArgument.getValue();
                    Value newValue = newArgument.get().getValue();
                    if (oldValue != null && newValue != null) {
                        if (!oldValue.getClass().equals(newValue.getClass())) {
                            differenceReporter.report(apiBreakage()
                                    .category(INVALID)
                                    .typeName(old.getName())
                                    .typeKind(getTypeKind(old))
                                    .components(directiveName, argName)
                                    .reasonMsg("The new API has changed value types on argument named '%s' on directive '%s", argName, directiveName)
                                    .build());
                        }
                    }
                }
            }
        }
    }

    DifferenceEvent.Category checkTypeWithNonNullAndList(Type oldType, Type newType) {
        TypeInfo oldTypeInfo = typeInfo(oldType);
        TypeInfo newTypeInfo = typeInfo(newType);

        if (!oldTypeInfo.getName().equals(newTypeInfo.getName())) {
            return INVALID;
        }

        while (true) {
            //
            // its allowed to get more less strict in the new but not more strict
            if (oldTypeInfo.isNonNull() && newTypeInfo.isNonNull()) {
                oldTypeInfo = oldTypeInfo.unwrapOne();
                newTypeInfo = newTypeInfo.unwrapOne();
            } else if (oldTypeInfo.isNonNull() && !newTypeInfo.isNonNull()) {
                oldTypeInfo = oldTypeInfo.unwrapOne();
            } else if (!oldTypeInfo.isNonNull() && newTypeInfo.isNonNull()) {
                return STRICTER;
            }
            // lists
            if (oldTypeInfo.isList() && !newTypeInfo.isList()) {
                return INVALID;
            }
            // plain
            if (oldTypeInfo.isPlain()) {
                if (!newTypeInfo.isPlain()) {
                    return INVALID;
                }
                break;
            }
            oldTypeInfo = oldTypeInfo.unwrapOne();
            newTypeInfo = newTypeInfo.unwrapOne();
        }
        return null;
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