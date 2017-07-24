package com.graphql.diff;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.PropertyDataFetcher;
import graphql.schema.TypeResolver;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Reader;
import java.util.Map;

public class TestKit {


    private static final TypeResolver NULL_TYPE_RESOLVER = env -> null;

    static Map<String, Object> introspect(String name) {

        GraphQLSchema schema = loadSchemaFile(name);
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();

        ExecutionResult executionResult = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY);
        return executionResult.getData();

    }

    static GraphQLSchema loadSchemaFile(String name) {
        Reader streamReader = loadFile(name);
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(streamReader);
        RuntimeWiring wiring = wireWithNoFetching();
        return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
    }

    static RuntimeWiring wireWithNoFetching() {
        return RuntimeWiring.newRuntimeWiring()
                .wiringFactory(new WiringFactory() {

                    @Override
                    public boolean providesTypeResolver(UnionWiringEnvironment environment) {
                        return true;
                    }

                    @Override
                    public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
                        return true;
                    }

                    @Override
                    public TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
                        return NULL_TYPE_RESOLVER;
                    }

                    @Override
                    public TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
                        return NULL_TYPE_RESOLVER;
                    }

                    @Override
                    public DataFetcher getDefaultDataFetcher(FieldWiringEnvironment environment) {
                        return new PropertyDataFetcher(environment.getFieldDefinition().getName());
                    }
                })
                .build();
    }

    static Reader loadFile(String name) {
        File pwd = new File("./src/test/resources").getAbsoluteFile();
        try {
            File f = new File(pwd, name);
            return new FileReader(f);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
