package b2.controllers.network;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/version")
public class VersionController {

    @Get
    public Version version() {
        return new Version("v0.1.0");
    }
}

