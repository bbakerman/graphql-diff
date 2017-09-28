package com.graphql.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphql.diff.reporting.PrintStreamReporter;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class Main {

    public static void main(String[] args) {

        Options options = new Options();
        options.addOption(Option
                .builder("newSchema")
                .required()
                .argName("fileOrUrl")
                .numberOfArgs(1)
                .build()
        );
        options.addOption(Option
                .builder("oldSchema")
                .required()
                .argName("fileOrUrl")
                .numberOfArgs(1)
                .build()
        );
        try {
            CommandLine commandLine = new DefaultParser().parse(options, args);
            runDiff(commandLine);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runDiff(CommandLine commandLine) {

        String oldSchemaLocation = commandLine.getOptionValue("oldSchema");
        String newSchemaLocation = commandLine.getOptionValue("newSchema");

        System.out.println("Reading old schema at : " + oldSchemaLocation);
        System.out.println("Reading new schema at : " + newSchemaLocation);

        Map<String, Object> oldSchema = loadSchema(oldSchemaLocation);
        Map<String, Object> newSchema = loadSchema(newSchemaLocation);

        DiffSet diffSet = DiffSet.diffSet(oldSchema, newSchema);
        new SchemaDiff().diffSchema(diffSet, new PrintStreamReporter());
    }

    private static Map<String, Object> loadSchema(String schemaLocation) {
        try {
            if (schemaLocation.contains("http")) {
                return loadSchemaViaHttp(schemaLocation);
            } else {
                return loadSchemaFile(schemaLocation);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to read schema from location : " + schemaLocation + " : " + e.getMessage());
        }
    }

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static Object fromJson(String jsonStr) throws IOException {
        return OBJECT_MAPPER.readValue(jsonStr, Object.class);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSchemaViaHttp(String schemaLocationUrl) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .build();

        HttpUrl httpUrl = HttpUrl.parse(schemaLocationUrl);
        if (httpUrl == null) {
            throw new RuntimeException("The schema location is not a valid http url : " + schemaLocationUrl);
        }

        Map<String, Object> graphqlQuery = new HashMap<>();
        graphqlQuery.put("query", IntrospectionQuery.INTROSPECTION_QUERY);

        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), toJsonStr(graphqlQuery));
        Request request = new Request.Builder()
                .method("POST", requestBody)
                .url(httpUrl)
                .build();
        Call call = client.newCall(request);
        Response response = call.execute();
        ResponseBody body = response.body();

        return (Map<String, Object>) jsonObjToMap(body).get("data");
    }

    private static Map<String, Object> jsonObjToMap(ResponseBody body) throws IOException {
        String jsonString;
        Object obj = null;
        if (body != null) {
            jsonString = body.string();
            obj = fromJson(jsonString);
        }
        //noinspection unchecked
        return (Map<String, Object>) obj;
    }

    private static String toJsonStr(Map<String, Object> graphqlQuery) throws IOException {
        StringWriter json = new StringWriter();
        OBJECT_MAPPER.writeValue(json, graphqlQuery);
        return json.toString();
    }

    private static Map<String, Object> loadSchemaFile(String schemaLocation) throws IOException {
        File f = new File(schemaLocation);
        if (!f.exists() || !f.canRead()) {
            throw new RuntimeException("The schema location is not a valid file : " + schemaLocation);
        }
        TypeDefinitionRegistry registry = new SchemaParser().parse(f);
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, fakeRuntime(registry));
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        ExecutionResult executionResult = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY);
        return executionResult.getData();
    }

    private static RuntimeWiring fakeRuntime(TypeDefinitionRegistry registry) {
        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();
        registry.scalars().forEach((name, scalarTypeDefinition) -> {
            GraphQLScalarType scalarType = fakeScalar(name);
            if (!ScalarInfo.isStandardScalar(scalarType)) {
                builder.scalar(scalarType);
            }
        });
        builder.wiringFactory(new WiringFactory() {
            @Override
            public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
                return true;
            }

            @Override
            public TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
                return env -> env.getSchema().getQueryType();
            }

            @Override
            public boolean providesTypeResolver(UnionWiringEnvironment environment) {
                return true;
            }

            @Override
            public TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
                return env -> env.getSchema().getQueryType();
            }

            @Override
            public DataFetcher getDefaultDataFetcher(FieldWiringEnvironment environment) {
                return env -> null;
            }
        });

        return builder.build();
    }

    private static GraphQLScalarType fakeScalar(String name) {
        return new GraphQLScalarType(name, name, new Coercing() {
            @Override
            public Object serialize(Object dataFetcherResult) {
                return null;
            }

            @Override
            public Object parseValue(Object input) {
                return null;
            }

            @Override
            public Object parseLiteral(Object input) {
                return null;
            }
        });
    }
}
