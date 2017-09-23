package com.graphql.diff

import com.graphql.diff.reporting.TypeKind
import graphql.language.Argument
import graphql.language.Directive
import graphql.language.IntValue
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import spock.lang.Specification

import static com.graphql.diff.reporting.DifferenceEvent.Category.INVALID
import static com.graphql.diff.reporting.DifferenceEvent.Category.MISSING
import static com.graphql.diff.reporting.DifferenceEvent.Category.STRICTER
import static com.graphql.diff.reporting.TypeKind.Interface
import static com.graphql.diff.reporting.TypeKind.Union

class SchemaDiffTest extends Specification {
    private CapturingReporter reporter

    void setup() {
        reporter = new CapturingReporter()
    }

    DiffSet diffSet(String newFile) {
        Map<String, Object> schemaOld = TestKit.introspect("schema_ABaseLine.graphqls")
        Map<String, Object> schemaNew = TestKit.introspect(newFile)

        def diffSet = new DiffSet(schemaOld, schemaNew)
        diffSet
    }


    def "change_in_null_ness"() {

        given:
        Type baseLine = new NonNullType(new ListType(new TypeName("foo")))
        Type same = new NonNullType(new ListType(new TypeName("foo")))

        Type less = new ListType(new TypeName("foo"))


        def diff = new SchemaDiff(reporter)

        def sameType = diff.checkTypeWithNonNullAndList(baseLine, same)

        def lessStrict = diff.checkTypeWithNonNullAndList(baseLine, less)

        // not allowed as old clients wont work
        def moreStrict = diff.checkTypeWithNonNullAndList(less, baseLine)


        expect:
        sameType == null
        lessStrict == null
        moreStrict == STRICTER
    }

    def "change_in_list_ness"() {

        given:
        Type baseLine = new NonNullType(new ListType(new TypeName("foo")))
        Type notList = new NonNullType(new TypeName("foo"))

        def diff = new SchemaDiff(reporter)

        def noLongerList = diff.checkTypeWithNonNullAndList(baseLine, notList)

        expect:
        noLongerList == INVALID
    }

    def "directives_controlled_via_options"() {

        given:
        TypeDefinition left = new ObjectTypeDefinition("fooType")


        def oneDirective = [new Directive("bar")]
        def twoDirectives = [new Directive("foo"), new Directive("bar")]

        def diff = new SchemaDiff(reporter)
        diff.checkDirectives(left, twoDirectives, oneDirective)
        def notChecked = reporter.lastError()

        diff = new SchemaDiff(reporter, SchemaDiff.Options.defaultOptions().enforceDirectives())
        diff.checkDirectives(left, twoDirectives, oneDirective)
        def missingDirective = reporter.lastError()

        expect:
        notChecked == null
        missingDirective.category == MISSING
    }

    def "directives enforced to be the same"() {

        given:
        TypeDefinition left = new ObjectTypeDefinition("fooType")


        def oneDirective = [new Directive("bar")]
        def twoDirectives = [new Directive("foo"), new Directive("bar")]

        def diff = new SchemaDiff(reporter, SchemaDiff.Options.defaultOptions().enforceDirectives())

        diff.checkDirectives(left, twoDirectives, oneDirective)
        def missingDirective = reporter.lastError()

        def oldDirective = new Directive("foo", [
                new Argument("arg1", new StringValue("p1")),
                new Argument("arg2", new StringValue("p1")),
        ])

        def newDirective = new Directive("foo", [
                new Argument("arg1", new StringValue("p1")),
                new Argument("arg3", new StringValue("p1")),
        ])

        diff.checkDirectives(left, [oldDirective], [newDirective])
        def missingArgs = reporter.lastError()


        def newDirectiveDiffDefaultType = new Directive("foo", [
                new Argument("arg1", new StringValue("p1")),
                new Argument("arg2", new IntValue(new BigInteger("123"))),
        ])

        diff.checkDirectives(left, [oldDirective], [newDirectiveDiffDefaultType])
        def changedType = reporter.lastError()

        expect:
        missingDirective.category == MISSING
        missingArgs.category == MISSING
        changedType.category == INVALID
        reporter.getErrorCount() == 3
    }

    def "same schema diff"() {
        DiffSet diffSet = diffSet("schema_ABaseLine.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 0
    }

    def "missing fields on interface"() {
        DiffSet diffSet = diffSet("schema_interface_fields_missing.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 2 // 2 fields removed
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == Interface

        reporter.errors[1].category == MISSING
        reporter.errors[1].typeOfType == Interface
    }

    def "missing members on union"() {
        DiffSet diffSet = diffSet("schema_missing_union_members.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 1 // 1 member removed
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == Union

    }

    def "missing fields on object"() {
        DiffSet diffSet = diffSet("schema_missing_object_fields.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 2 // 2 fields removed
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == TypeKind.Object
        reporter.errors[0].fieldName == 'colour'

        reporter.errors[1].category == MISSING
        reporter.errors[1].typeOfType == TypeKind.Object
        reporter.errors[1].fieldName == 'temperament'

    }

    def "missing operation"() {
        DiffSet diffSet = diffSet("schema_missing_operation.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 1
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == TypeKind.Operation
        reporter.errors[0].fieldName == 'mutation'

    }

    def "missing input object fields"() {
        DiffSet diffSet = diffSet("schema_missing_input_object_fields.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 1
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == TypeKind.InputObject
        reporter.errors[0].fieldName == 'queryTarget'

    }

    def "changed input object field types"() {
        DiffSet diffSet = diffSet("schema_changed_input_object_fields.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 2
        reporter.errors[0].category == INVALID
        reporter.errors[0].typeName == 'Questor'
        reporter.errors[0].typeOfType == TypeKind.InputObject
        reporter.errors[0].fieldName == 'queryTarget'

        reporter.errors[1].category == STRICTER
        reporter.errors[1].typeName == 'Questor'
        reporter.errors[1].typeOfType == TypeKind.InputObject
        reporter.errors[1].fieldName == 'newMandatoryField'

    }

    def "missing object field args"() {
        DiffSet diffSet = diffSet("schema_missing_field_arguments.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 1
        reporter.errors[0].category == MISSING
        reporter.errors[0].typeOfType == TypeKind.Object
        reporter.errors[0].fieldName == 'sword'

    }

    def "changed object field args"() {
        DiffSet diffSet = diffSet("schema_changed_field_arguments.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 2
        reporter.errors[0].category == INVALID
        reporter.errors[0].typeOfType == TypeKind.Object
        reporter.errors[0].fieldName == 'sword'

        reporter.errors[1].category == INVALID
        reporter.errors[1].typeOfType == TypeKind.Object
        reporter.errors[1].fieldName == 'sword'

    }

    def "changed type on object"() {
        DiffSet diffSet = diffSet("schema_changed_object_fields.graphqls")

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 4
        reporter.errors[0].category == STRICTER
        reporter.errors[0].typeName == 'Query'
        reporter.errors[0].typeOfType == TypeKind.Object
        reporter.errors[0].fieldName == 'being'

        reporter.errors[1].category == INVALID
        reporter.errors[1].typeName == 'Query'
        reporter.errors[1].typeOfType == TypeKind.Object
        reporter.errors[1].fieldName == 'beings'

        reporter.errors[2].category == STRICTER
        reporter.errors[2].typeName == 'Query'
        reporter.errors[2].typeOfType == TypeKind.Object
        reporter.errors[2].fieldName == 'customScalar'

        reporter.errors[3].category == STRICTER
        reporter.errors[3].typeName == 'Query'
        reporter.errors[3].typeOfType == TypeKind.Object
        reporter.errors[3].fieldName == 'wizards'

    }

}
