package org.globsframework.mcp.sample;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.annotations.EnumAnnotation;
import org.globsframework.core.metamodel.annotations.Required;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.apache.hc.core5.http.impl.bootstrap.AsyncServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.server.apache.GlobHttpApacheBuilder;
import org.globsframework.http.server.apache.Server;
import org.globsframework.http.HttpTreatment;
import org.globsframework.mcp.McpServerRegister;
import org.globsframework.mcp.http.HttpToMcp;
import org.globsframework.mcp.transport.McpHttpTransport;
import org.globsframework.mcp.transport.McpStdioServer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A runnable MCP server over stdio, exposing a toy catalog.
 * <p>
 * It shows the two ways in: {@code /product/{ref}} and {@code /product} are declared as an ordinary
 * globs-http REST API and bridged with {@link HttpToMcp}, while {@code convert_price} is declared
 * directly on the {@link McpServerRegister}. Neither path involves a generated class or an annotation
 * processor — the GlobTypes are the tool definitions.
 * <p>
 * Runs on stdio by default, or over Streamable HTTP with {@code --http [port]}.
 * <p>
 * Point an MCP client at it with, from this directory:
 * <pre>
 * mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
 * java -cp target/classes:$(cat target/cp.txt) org.globsframework.mcp.sample.DemoMcpServer
 * </pre>
 * or drive it by hand:
 * <pre>
 * echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -cp ... DemoMcpServer
 * </pre>
 */
public class DemoMcpServer {

    private static final List<Glob> CATALOG = List.of(
            product("A-12", "Espresso machine", 249.0, "kitchen"),
            product("B-07", "Coffee grinder", 89.5, "kitchen"),
            product("C-31", "Desk lamp", 39.0, "office"));

    public static void main(String[] args) throws Exception {
        McpServerRegister mcp = buildServer();
        if (args.length > 0 && args[0].equals("--http")) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8123;
            serveOverHttp(mcp, port);
        } else {
            new McpStdioServer(mcp.complete()).run();
        }
    }

    /** The same declaration behind either transport — they differ only in how bytes get in and out. */
    private static void serveOverHttp(McpServerRegister mcp, int port) throws Exception {
        HttpServerRegister http = new HttpServerRegister("demo-catalog-http");
        McpHttpTransport.register(http, mcp.complete());
        Server server = new GlobHttpApacheBuilder(http)
                .startAndWaitForStartup(AsyncServerBootstrap.bootstrap()
                        .setIOReactorConfig(IOReactorConfig.custom()
                                .setSoReuseAddress(true)
                                .build()), port);
        // stderr, never stdout: the same binary also speaks the stdio transport.
        System.err.println("MCP endpoint on http://localhost:" + server.getPort()
                + McpHttpTransport.DEFAULT_PATH);
        server.getServer().awaitShutdown(TimeValue.ofDays(365));
    }

    private static McpServerRegister buildServer() {
        McpServerRegister mcp = HttpToMcp.toMcp(catalogApi(), "0.1")
                .withTitle("Demo catalog")
                .withInstructions("A toy product catalog. References look like A-12.");

        mcp.registerTool("convert_price", ConversionRequest.TYPE, input -> {
            double rate = switch (input.get(ConversionRequest.currency)) {
                case "USD" -> 1.09;
                case "GBP" -> 0.85;
                default -> throw new IllegalArgumentException(
                        "Unsupported currency: " + input.get(ConversionRequest.currency));
            };
            return CompletableFuture.completedFuture(ConversionResult.TYPE.instantiate()
                    .set(ConversionResult.amount, input.get(ConversionRequest.amount) * rate)
                    .set(ConversionResult.currency, input.get(ConversionRequest.currency)));
        }).comment("Converts an amount in euros to another currency.")
                .declareReturnType(ConversionResult.TYPE);

        return mcp;
    }

    /** An ordinary globs-http API: this is the part that would already exist in a real application. */
    private static HttpServerRegister catalogApi() {
        HttpServerRegister register = new HttpServerRegister("demo-catalog");

        register.register("/product/{ref}", ProductPath.TYPE)
                .get(null, (HttpTreatment) (body, path, query) -> {
                    String wanted = path.get(ProductPath.ref);
                    return CompletableFuture.completedFuture(CATALOG.stream()
                            .filter(glob -> wanted.equals(glob.get(Product.ref)))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No product " + wanted)));
                })
                .comment("Reads one product by its reference.")
                .declareReturnType(Product.TYPE);

        register.register("/product", null)
                .get(ProductSearch.TYPE, (HttpTreatment) (body, path, query) -> {
                    String category = query.get(ProductSearch.category);
                    Double maxPrice = query.get(ProductSearch.maxPrice);
                    Glob[] found = CATALOG.stream()
                            .filter(glob -> category == null || category.equals(glob.get(Product.category)))
                            .filter(glob -> maxPrice == null || glob.get(Product.price) <= maxPrice)
                            .toArray(Glob[]::new);
                    return CompletableFuture.completedFuture(
                            SearchResult.TYPE.instantiate().set(SearchResult.products, found));
                })
                .comment("Searches the catalog.")
                .declareReturnType(SearchResult.TYPE);

        return register;
    }

    private static Glob product(String ref, String label, double price, String category) {
        return Product.TYPE.instantiate()
                .set(Product.ref, ref)
                .set(Product.label, label)
                .set(Product.price, price)
                .set(Product.category, category);
    }

    public static class Product {
        public static final GlobType TYPE;
        public static final StringField ref;
        public static final StringField label;
        public static final DoubleField price;
        public static final StringField category;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Product");
            ref = typeBuilder.declareStringField("ref",
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Catalog reference, e.g. A-12"));
            label = typeBuilder.declareStringField("label");
            price = typeBuilder.declareDoubleField("price",
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Price in euros"));
            category = typeBuilder.declareStringField("category",
                    EnumAnnotation.create(new String[]{"kitchen", "office"}));
            TYPE = typeBuilder.build();
        }
    }

    public static class ProductPath {
        public static final GlobType TYPE;
        public static final StringField ref;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ProductPath");
            ref = typeBuilder.declareStringField("ref", Required.UNIQUE_GLOB,
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Catalog reference, e.g. A-12"));
            TYPE = typeBuilder.build();
        }
    }

    public static class ProductSearch {
        public static final GlobType TYPE;
        public static final StringField category;
        public static final DoubleField maxPrice;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ProductSearch");
            category = typeBuilder.declareStringField("category",
                    EnumAnnotation.create(new String[]{"kitchen", "office"}));
            maxPrice = typeBuilder.declareDoubleField("maxPrice",
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Keep only products at or below this price"));
            TYPE = typeBuilder.build();
        }
    }

    public static class SearchResult {
        public static final GlobType TYPE;
        public static final GlobArrayField<Product> products;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("SearchResult");
            products = typeBuilder.declareGlobArrayField("products", () -> Product.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class ConversionRequest {
        public static final GlobType TYPE;
        public static final DoubleField amount;
        public static final StringField currency;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ConversionRequest");
            amount = typeBuilder.declareDoubleField("amount", Required.UNIQUE_GLOB,
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Amount in euros"));
            currency = typeBuilder.declareStringField("currency", Required.UNIQUE_GLOB,
                    EnumAnnotation.create(new String[]{"USD", "GBP"}));
            TYPE = typeBuilder.build();
        }
    }

    public static class ConversionResult {
        public static final GlobType TYPE;
        public static final DoubleField amount;
        public static final StringField currency;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ConversionResult");
            amount = typeBuilder.declareDoubleField("amount");
            currency = typeBuilder.declareStringField("currency");
            TYPE = typeBuilder.build();
        }
    }
}
