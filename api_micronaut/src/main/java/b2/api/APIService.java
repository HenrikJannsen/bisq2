package b2.api;

import b2.controllers.network.VersionController;
import b2.controllers.offer.OfferController;
import io.micronaut.context.ApplicationContext;
import io.micronaut.openapi.annotation.OpenAPIInclude;
import io.micronaut.runtime.Micronaut;
import io.micronaut.runtime.server.EmbeddedServer;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;

import static io.micronaut.core.util.CollectionUtils.mapOf;

@OpenAPIDefinition(
        info = @Info(
                title = "bisq-api",
                version = "2.0.3"
        ), servers = @Server(url = "/")
)
@OpenAPIInclude(classes = OfferController.class, tags = @Tag(name = "Trade"))
@OpenAPIInclude(classes = VersionController.class, tags = @Tag(name = "P2P"))
public class APIService {
    public void start() {
        String[] args = new String[]{};
        ApplicationContext ctx = Micronaut.build(args)
                .banner(false)
                .properties(mapOf(
                        "micronaut.server.port", 2141
                ))
                .start();
        var server = ctx.getBean(EmbeddedServer.class);
        System.out.printf("api service listening at http://%s:%d\n", server.getHost(), server.getPort());
    }
}
