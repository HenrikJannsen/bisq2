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
package bisq.rest_api.dto;

import bisq.common.encoding.Hex;
import bisq.security.keys.KeyBundle;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "KeyBundle")
public final class KeyBundleDto {
    private String keyId;
    private String privateKey;
    private String publicKey;
    private String torPrivateKey;
    private String onionAddress;

    public static KeyBundleDto from(KeyBundle keyBundle) {
        KeyBundleDto dto = new KeyBundleDto();
        dto.keyId = keyBundle.getKeyId();
        dto.privateKey = Hex.encode(keyBundle.getKeyPair().getPrivate().getEncoded());
        dto.publicKey = Hex.encode(keyBundle.getKeyPair().getPublic().getEncoded());
        dto.torPrivateKey = Hex.encode(keyBundle.getTorKeyPair().getPrivateKey());
        dto.onionAddress = keyBundle.getTorKeyPair().getOnionAddress();
        return dto;
    }
}


