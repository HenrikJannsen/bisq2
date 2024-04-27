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

package bisq.api.network.identity;

import bisq.api.security.keys.PubKeyDto;
import bisq.common.data.Pair;
import bisq.network.identity.NetworkId;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Map;
import java.util.stream.Collectors;

@Getter
@EqualsAndHashCode
@Schema(name = "NetworkId")
public final class NetworkIdDto {
    public static NetworkIdDto from(NetworkId networkId) {
        Map<String, String> addressByTransportTypeMap = networkId.getAddressByTransportTypeMap().getMap().entrySet().stream()
                .map(e -> new Pair<>(e.getKey().name(), e.getValue().getFullAddress()))
                .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
        return new NetworkIdDto(addressByTransportTypeMap,
                PubKeyDto.from(networkId.getPubKey()));
    }

    private final PubKeyDto pubKey;
    private final Map<String, String> addressByTransportTypeMap;

    public NetworkIdDto(Map<String, String> addressByTransportTypeMap, PubKeyDto pubKey) {
        this.addressByTransportTypeMap = addressByTransportTypeMap;
        this.pubKey = pubKey;
    }
}

