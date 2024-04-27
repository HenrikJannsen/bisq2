/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.api.application;


import bisq.api.Api;
import bisq.application.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Path("/version")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Version API")
public class VersionApi extends Api {

    private final ApplicationService.Config applicationConfig;

    public VersionApi(@Context Application application) {
        applicationConfig = getCoreServiceProvider().getApplicationConfig();
    }

    /**
     * @return The version
     */
    @Operation(summary = "Get version")
    @ApiResponse(responseCode = "404", description = "version was not found")
    @ApiResponse(responseCode = "200", description = "version",
            content = {
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VersionDto.class)
                    )}
    )
    @GET
    @Path("get")
    public VersionDto getVersion() {
        return VersionDto.from(applicationConfig.getVersion());
    }
}
