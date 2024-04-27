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

import bisq.api.identity.IdentityDto;
import bisq.user.identity.UserIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "UserIdentity")
public final class UserIdentityDto {
    public static UserIdentityDto from(UserIdentity userIdentity) {
        return new UserIdentityDto(IdentityDto.from(userIdentity.getIdentity()), bisq.api.user.identity.UserProfileDto.from(userIdentity.getUserProfile()));
    }

    private final IdentityDto identityDto;
    private final UserProfileDto userProfileDto;

    public UserIdentityDto(IdentityDto identityDto, bisq.api.user.identity.UserProfileDto userProfileDto) {
        this.identityDto = identityDto;
        this.userProfileDto = userProfileDto;
    }
}


