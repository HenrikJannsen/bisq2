package b2.controllers.network;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record Version(@NotBlank String version) {

}
