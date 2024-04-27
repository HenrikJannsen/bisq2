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
package bisq.api.identity;

import bisq.api.network.identity.NetworkIdDto;
import bisq.api.security.keys.KeyBundleDto;
import bisq.identity.Identity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "Identity")
public final class IdentityDto {
    public static IdentityDto from(Identity identity) {
        return new IdentityDto(identity.getTag(),
                NetworkIdDto.from(identity.getNetworkId()),
                KeyBundleDto.from(identity.getKeyBundle()));
    }

    private final String tag;
    private final NetworkIdDto networkIdDto;
    private final KeyBundleDto keyBundleDto;

    public IdentityDto(String tag, NetworkIdDto networkIdDto, KeyBundleDto keyBundleDto) {
        this.tag = tag;
        this.networkIdDto = networkIdDto;
        this.keyBundleDto = keyBundleDto;
    }
}


