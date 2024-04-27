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

package bisq.api.user.identity;


import bisq.api.Api;
import bisq.security.DigestUtil;
import bisq.security.keys.KeyBundleService;
import bisq.security.pow.ProofOfWork;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;

@Slf4j
@Path("/user-identity")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Version API")
public class UserIdentityApi extends Api {
    private final KeyBundleService keyBundleService;
    private final UserIdentityService userIdentityService;

    public UserIdentityApi(@Context Application application) {
        keyBundleService = getCoreServiceProvider().getSecurityService().getKeyBundleService();
        userIdentityService = getCoreServiceProvider().getUserService().getUserIdentityService();
    }

    /**
     * @param nickName The nickname to be used for the new user identity
     * @return The key bundle.
     */
    @Operation(description = "Creates a new user identity")
    @ApiResponse(responseCode = "200", description = "the new user identity with the give nick name",
            content = {
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = bisq.api.user.identity.UserIdentityDto.class)
                    )}
    )
    @GET
    @Path("create/{nick-name}")
    public UserIdentityDto createAndPublishNewUserProfile(@Parameter(description = "nick name") @PathParam("nick-name") String nickName) {
        KeyPair keyPair = keyBundleService.generateKeyPair();
        byte[] pubKeyHash = DigestUtil.hash(keyPair.getPublic().getEncoded());
        ProofOfWork proofOfWork = userIdentityService.mintNymProofOfWork(pubKeyHash);
        //String nym = NymIdGenerator.generate(pubKeyHash, proofOfWork.getSolution());
        UserIdentity userIdentity = userIdentityService.createAndPublishNewUserProfile(
                        nickName,
                        keyPair,
                        pubKeyHash,
                        proofOfWork,
                        0,
                        "",
                        "")
                .join();
        log.info("UserIdentityDto {}", UserIdentityDto.from(userIdentity));
        return UserIdentityDto.from(userIdentity);
    }
}
