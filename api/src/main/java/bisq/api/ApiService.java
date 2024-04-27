package bisq.api;

import bisq.common.application.Service;
import bisq.core.CoreServiceProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ResourceConfig;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JAX-RS application for the Bisq REST API
 */
@Slf4j
public class ApiService extends ResourceConfig implements Service {
    @Getter
    private static CoreServiceProvider coreServiceProvider;

    @Getter
    public static final class Config {
        private final boolean enabled;
        private final String host;
        private final int port;
        private final String version;
        private final String user;
        private final String password;

        public Config(boolean enabled, String host, int port, String version, String user, String password) {
            this.enabled = enabled;
            this.host = host;
            this.port = port;
            this.version = version;
            this.user = user;
            this.password = password;
        }

        public static Config from(com.typesafe.config.Config config) {
            return new Config(config.getBoolean("enabled"),
                    config.getString("host"),
                    config.getInt("port"),
                    config.getString("version"),
                    config.getString("user"),
                    config.getString("password"));
        }
    }

    private final Optional<JaxRsApplication> jaxRsApplication;

    public ApiService(Config config, CoreServiceProvider coreServiceProvider) {
        this.coreServiceProvider = coreServiceProvider;

        if (config.isEnabled()) {
            jaxRsApplication = Optional.of(new JaxRsApplication(config, coreServiceProvider));
        } else {
            jaxRsApplication = Optional.empty();
        }
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        return jaxRsApplication.map(JaxRsApplication::initialize)
                .orElse(CompletableFuture.completedFuture(true));
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        return jaxRsApplication.map(JaxRsApplication::shutdown)
                .orElse(CompletableFuture.completedFuture(true));
    }
}
