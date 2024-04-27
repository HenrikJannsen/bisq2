package bisq.api;

import bisq.api.application.VersionApi;
import bisq.api.chat.ChatApi;
import bisq.api.error.CustomExceptionMapper;
import bisq.api.error.StatusException;
import bisq.api.security.keys.KeyBundleApi;
import bisq.api.user.identity.UserIdentityApi;
import bisq.common.application.Service;
import bisq.core.CoreServiceProvider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * JAX-RS application for the Bisq REST API
 */
@Slf4j
public class JaxRsApplication extends ResourceConfig implements Service {
    private final ApiService.Config config;
    @Getter
    private final CoreServiceProvider coreServiceProvider;
    public final String baseUrl;
    private HttpServer httpServer;

    public JaxRsApplication(ApiService.Config config, CoreServiceProvider coreServiceProvider) {
        this.config = config;
        this.coreServiceProvider = coreServiceProvider;

        baseUrl = config.getHost() + ":" + config.getPort() + "/api/" + config.getVersion();
        SwaggerResolution.setBaseUrl(baseUrl);
        register(CustomExceptionMapper.class)
                .register(StatusException.StatusExceptionMapper.class)
                .register(VersionApi.class)
                .register(KeyBundleApi.class)
                .register(UserIdentityApi.class)
                .register(ChatApi.class)
                .register(SwaggerResolution.class);
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        httpServer = JdkHttpServerFactory.createHttpServer(URI.create(baseUrl), this);
        httpServer.createContext("/doc", new StaticFileHandler("/doc/" + config.getVersion() + "/"));
        log.info("Server started at {}.", baseUrl);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        if (httpServer != null) {
            httpServer.stop(2);
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * JDK Server needs handler for serving files, will change in JDK 18
     * Currently this is only to serve the swagger-ui content to the client.
     * So any call to this handler must begin with api/v1. We keep v1 in case
     * we will have incompatible changes in the future.
     * This handler is limited to html, css, json and javascript files.
     */
    @Slf4j
    public static class StaticFileHandler implements HttpHandler {
        private static final String NOT_FOUND = "404 (Not Found)\n";
        public static final String[] VALID_SUFFIX = {".html", ".json", ".css", ".js"};

        @Getter
        protected final String rootContext;

        public StaticFileHandler(String rootContext) {
            this.rootContext = rootContext;
        }

        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();

            log.debug("requesting: " + uri.getPath());
            String filename = uri.getPath();
            if (filename == null || !filename.startsWith(rootContext) ||
                    Arrays.stream(VALID_SUFFIX).noneMatch(filename::endsWith)) {
                respond404(exchange);
                return;
            }
            // resource loading without leading slash
            String resourceName = filename.replace("..", "");
            if (filename.charAt(0) == '/') {
                resourceName = filename.substring(1);
            }

            // we are using getResourceAsStream to ultimately prevent load from parent directories
            try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
                if (resource == null) {
                    respond404(exchange);
                    return;
                }
                log.debug("sending: " + resourceName);
                // Object exists and is a file: accept with response code 200.
                String mime = "text/html";
                if (resourceName.endsWith(".js")) mime = "application/javascript";
                if (resourceName.endsWith(".json")) mime = "application/json";
                if (resourceName.endsWith(".css")) mime = "text/css";
                if (resourceName.endsWith(".png")) mime = "image/png";

                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", mime);
                headers.add("Cache-Control", "max-age=3600"); // cache static content on browser for 3600 seconds
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream outputStream = exchange.getResponseBody()) {
                    byte[] buffer = new byte[0x10000];
                    int count;
                    while ((count = resource.read(buffer)) >= 0) {
                        outputStream.write(buffer, 0, count);
                    }
                }
            }
        }

        private void respond404(HttpExchange exchange) throws IOException {
            // Object does not exist or is not a file: reject with 404 error.
            exchange.sendResponseHeaders(404, NOT_FOUND.length());
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(NOT_FOUND.getBytes());
            }
        }
    }
}
