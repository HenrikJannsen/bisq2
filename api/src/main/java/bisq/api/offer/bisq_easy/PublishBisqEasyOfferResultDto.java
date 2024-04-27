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
package bisq.api.offer.bisq_easy;

import bisq.network.p2p.services.data.BroadcastResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "PublishBisqEasyOfferResultDto")
public final class PublishBisqEasyOfferResultDto {
    public static PublishBisqEasyOfferResultDto from(BroadcastResult broadcastResult) {
        return new PublishBisqEasyOfferResultDto(String.valueOf(broadcastResult.size()));
    }

    private final String numBroadcastResults;

    public PublishBisqEasyOfferResultDto(String numBroadcastResults) {
        this.numBroadcastResults = numBroadcastResults;
    }
}


