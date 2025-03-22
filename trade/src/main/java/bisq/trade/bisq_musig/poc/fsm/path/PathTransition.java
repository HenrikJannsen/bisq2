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

package bisq.trade.bisq_musig.poc.fsm.path;

import bisq.trade.bisq_musig.poc.fsm.path.phase.PhaseTransition;
import lombok.Getter;

import java.util.Collection;
import java.util.Optional;

@Getter
public class PathTransition {
    private final Path from;
    private final Class<? extends PathEvent> on;
    private final Optional<Collection<PhaseTransition>> phases;
    private final Path to;
    private final Optional<Class<? extends PathEventHandler>> handler;

    public PathTransition(Path from,
                          Class<? extends PathEvent> on,
                          Collection<PhaseTransition> phases,
                          Path to) {
        this.from = from;
        this.on = on;
        this.phases = Optional.of(phases);
        this.to = to;

        this.handler = Optional.empty();
    }

    public PathTransition(Path from,
                          Class<? extends PathEvent> on,
                          Class<? extends PathEventHandler> handler,
                          Path to) {
        this.from = from;
        this.on = on;
        this.handler = Optional.of(handler);
        this.to = to;
        phases = Optional.empty();
    }
}

