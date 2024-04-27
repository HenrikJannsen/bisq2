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
import bisq.security.keys.KeyBundle;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "KeyBundle")
public final class KeyBundleDto {
    public static KeyBundleDto from(KeyBundle keyBundle) {
        return new KeyBundleDto(keyBundle.getKeyId(),
                Hex.encode(keyBundle.getKeyPair().getPrivate().getEncoded()),
                Hex.encode(keyBundle.getKeyPair().getPublic().getEncoded()),
                Hex.encode(keyBundle.getTorKeyPair().getPrivateKey()),
                keyBundle.getTorKeyPair().getOnionAddress()
        );
    }

    private final String keyId;
    private final String privateKey;
    private final String publicKey;
    private final String torPrivateKey;
    private final String onionAddress;

    public KeyBundleDto(String keyId, String privateKey, String publicKey, String torPrivateKey, String onionAddress) {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.torPrivateKey = torPrivateKey;
        this.onionAddress = onionAddress;
    }
}


