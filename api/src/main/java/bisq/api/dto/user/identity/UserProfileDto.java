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

package bisq.api.dto.user.identity;

import bisq.api.dto.network.identity.NetworkIdDto;
import bisq.api.dto.security.pow.ProofOfWorkDto;
import bisq.user.profile.UserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "UserProfile")
public final class UserProfileDto {
    public static UserProfileDto from(UserProfile userProfile) {
        return new UserProfileDto(userProfile.getNickName(),
                ProofOfWorkDto.from(userProfile.getProofOfWork()),
                userProfile.getAvatarVersion(),
                NetworkIdDto.from(userProfile.getNetworkId()),
                userProfile.getTerms(),
                userProfile.getStatement());
    }

    private final String nickName;
    private final ProofOfWorkDto proofOfWorkDto;
    private final int avatarVersion;
    private final NetworkIdDto networkIdDto;
    private final String terms;
    private final String statement;

    public UserProfileDto(String nickName, ProofOfWorkDto proofOfWorkDto, int avatarVersion, NetworkIdDto networkIdDto, String terms, String statement) {
        this.nickName = nickName;
        this.proofOfWorkDto = proofOfWorkDto;
        this.avatarVersion = avatarVersion;
        this.networkIdDto = networkIdDto;
        this.terms = terms;
        this.statement = statement;
    }
}
