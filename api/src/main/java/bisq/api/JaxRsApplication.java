package bisq.api;

import bisq.api.api.application.VersionApi;
import bisq.api.api.chat.ChatApi;
import bisq.api.api.security.keys.KeyBundleApi;
import bisq.api.error.CustomExceptionMapper;
import bisq.api.error.StatusException;
import bisq.api.util.StaticFileHandler;
import bisq.common.application.Service;
import bisq.core.CoreServiceProvider;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.net.URI;
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
}
