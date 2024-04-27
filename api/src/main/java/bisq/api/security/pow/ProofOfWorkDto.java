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

package bisq.api.security.pow;

import bisq.common.encoding.Hex;
import bisq.security.pow.ProofOfWork;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "ProofOfWork")
public class ProofOfWorkDto {
    public static ProofOfWorkDto from(ProofOfWork proofOfWork) {
        byte[] challenge = proofOfWork.getChallenge();
        return new ProofOfWorkDto(
                Hex.encode(proofOfWork.getPayload()),
                proofOfWork.getCounter(),
                challenge == null ? "" : Hex.encode(challenge),
                proofOfWork.getDifficulty(),
                Hex.encode(proofOfWork.getSolution()),
                proofOfWork.getDuration()
        );
    }

    private final String payload;
    private final long counter;
    private final String challenge;
    private final double difficulty;
    private final String solution;
    private final long duration;

    public ProofOfWorkDto(String payload,
                          long counter,
                          String challenge,
                          double difficulty,
                          String solution,
                          long duration) {
        this.payload = payload;
        this.counter = counter;
        this.challenge = challenge;
        this.difficulty = difficulty;
        this.solution = solution;
        this.duration = duration;
    }
}
