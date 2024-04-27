package b2.controllers.offer;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record Offer(@NotBlank String details) {

}
