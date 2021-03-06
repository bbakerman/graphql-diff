package com.graphql.diff;

import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
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

public class TestKit {

    private static final TypeResolver NULL_TYPE_RESOLVER = env -> null;

    static GraphQLSchema loadSchemaFile(String name) {
        Reader streamReader = loadFile(name);
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(streamReader);
        RuntimeWiring wiring = wireWithNoFetching();
        return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
    }

    static GraphQLScalarType CUSTOM_SCALAR = new GraphQLScalarType("CustomScalar", "CustomScalar", new Coercing() {
        @Override
        public Object serialize(Object dataFetcherResult) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public Object parseValue(Object input) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public Object parseLiteral(Object input) {
            throw new UnsupportedOperationException("Not implemented");
        }
    });

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
                .scalar(CUSTOM_SCALAR)
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
