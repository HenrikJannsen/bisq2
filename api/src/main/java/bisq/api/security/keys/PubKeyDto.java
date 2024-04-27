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

package bisq.api.security.keys;

import bisq.common.encoding.Hex;
import bisq.security.keys.PubKey;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "PubKey")
public final class PubKeyDto {
    public static PubKeyDto from(PubKey pubKey) {
        return new PubKeyDto(Hex.encode(pubKey.getPublicKey().getEncoded()), pubKey.getKeyId());
    }

    private final String publicKey;
    private final String keyId;

    public PubKeyDto(String publicKey, String keyId) {
        this.publicKey = publicKey;
        this.keyId = keyId;
    }
}