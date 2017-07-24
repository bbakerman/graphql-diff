package com.graphql.diff

import graphql.language.Directive
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import spock.lang.Specification

class SchemaDiffTest extends Specification {
    private CapturingReporter reporter

    void setup() {
        reporter = new CapturingReporter()
    }

    def "same schema diff"() {
        Map<String, Object> schemaOld = TestKit.introspect("schemaBaseLine.graphqls")
        Map<String, Object> schemaNew = TestKit.introspect("schemaBaseLine.graphqls")

        def diffSet = new DiffSet(schemaOld, schemaNew)

        def diff = new SchemaDiff(reporter)
        diff.diffSchema(diffSet)

        expect:
        reporter.errorCount == 0
    }

    def "change_in_null_ness"() {

        given:
        Type baseLine = new NonNullType(new ListType(new TypeName("foo")))
        Type same = new NonNullType(new ListType(new TypeName("foo")))

        Type lessStrict = new ListType(new TypeName("foo"))


        def diff = new SchemaDiff(reporter)

        def sameTypeOK = diff.checkNonNullAndList(baseLine, same)

        def lessStrictOk = diff.checkNonNullAndList(baseLine, lessStrict)

        // not allowed as old clients wont work
        def moreStrictOk = diff.checkNonNullAndList(lessStrict, baseLine)


        expect:
        sameTypeOK
        lessStrictOk
        !moreStrictOk
    }

    def "change_in_list_ness"() {

        given:
        Type baseLine = new NonNullType(new ListType(new TypeName("foo")))
        Type notList = new NonNullType(new TypeName("foo"))

        def diff = new SchemaDiff(reporter)

        def noLongerList = diff.checkNonNullAndList(baseLine, notList)

        expect:
        !noLongerList
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
        missingDirective != null
    }
}
