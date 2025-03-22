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

package bisq.trade.bisq_musig.poc;

import bisq.trade.bisq_musig.poc.fsm.path.PathTransition;
import bisq.trade.bisq_musig.poc.fsm.path.phase.PhaseTransition;
import bisq.trade.bisq_musig.poc.fsm.path.phase.state.StateTransition;
import bisq.trade.bisq_musig.poc.paths.MuSigPaths;
import bisq.trade.bisq_musig.poc.paths.events.EnterConflictResolutionEvent;
import bisq.trade.bisq_musig.poc.paths.events.RunConflictResolutionEvent;
import bisq.trade.bisq_musig.poc.paths.events.RunMuSigProtocolEvent;
import bisq.trade.bisq_musig.poc.paths.events.StartMuSigProtocolEvent;
import bisq.trade.bisq_musig.poc.paths.handlers.StartMuSigProtocolEventHandler;
import bisq.trade.bisq_musig.poc.paths.standard.StandardPhases;
import bisq.trade.bisq_musig.poc.paths.standard.events.BisqMuSigTakeOfferEvent;
import bisq.trade.bisq_musig.poc.paths.standard.events.StartFiatConfirmationPhaseEvent;
import bisq.trade.bisq_musig.poc.paths.standard.states.DepositState;
import bisq.trade.bisq_musig.poc.paths.standard.states.events.BisqMuSigConstructPreSignedTxEvent;
import bisq.trade.bisq_musig.poc.paths.standard.states.events.BisqMuSigDepositTxBroadcastEvent;
import bisq.trade.bisq_musig.poc.paths.standard.states.events.BisqMuSigKeyAggregationEvent;
import bisq.trade.bisq_musig.poc.paths.standard.states.handlers.BisqMuSigConstructPreSignedTxEventHandler;
import bisq.trade.bisq_musig.poc.paths.standard.states.handlers.BisqMuSigDepositTxBroadcastEventHandler;
import bisq.trade.bisq_musig.poc.paths.standard.states.handlers.BisqMuSigKeyAggregationEventHandler;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

@Getter
@Slf4j
public class MuSigConfig {
    public static void main(String[] args) {
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
                .registerTypeAdapter(Class.class, new ClassSerializer())
                .create();

        MuSigConfig muSigConfig = new MuSigConfig();
        log.info(gson.toJson(muSigConfig));
    }

    private final List<PathTransition> paths;

    public MuSigConfig() {
        paths = anyOf(new PathTransition(MuSigPaths.INIT,
                        StartMuSigProtocolEvent.class,
                        StartMuSigProtocolEventHandler.class,
                        MuSigPaths.STANDARD),
                new PathTransition(MuSigPaths.STANDARD,
                        RunMuSigProtocolEvent.class,
                        allOf(new PhaseTransition(StandardPhases.INIT,
                                        BisqMuSigTakeOfferEvent.class,
                                        allOf(new StateTransition(DepositState.INIT,
                                                        BisqMuSigConstructPreSignedTxEvent.class,
                                                        BisqMuSigConstructPreSignedTxEventHandler.class,
                                                        DepositState.PRE_SIGNED),
                                                new StateTransition(DepositState.PRE_SIGNED,
                                                        BisqMuSigKeyAggregationEvent.class,
                                                        BisqMuSigKeyAggregationEventHandler.class,
                                                        DepositState.KEY_AGGREGATED),
                                                new StateTransition(DepositState.PRE_SIGNED,
                                                        BisqMuSigDepositTxBroadcastEvent.class,
                                                        BisqMuSigDepositTxBroadcastEventHandler.class,
                                                        DepositState.DEPOSIT_COMPLETED)
                                        ),
                                        StandardPhases.DEPOSIT_PHASE),
                                new PhaseTransition(StandardPhases.DEPOSIT_PHASE,
                                        StartFiatConfirmationPhaseEvent.class,
                                        List.of(), // todo add more...
                                        StandardPhases.FIAT_CONFIRMATION_PHASE)
                                // todo add more...
                        ),
                        MuSigPaths.COMPLETED),
                new PathTransition(MuSigPaths.STANDARD,
                        EnterConflictResolutionEvent.class, // this event is triggered by any condition in the standard path to start conflict path
                        List.of(), // we could use an event handler here to check for conditions and fire the RunConflictResolutionEvent
                        MuSigPaths.CONFLICT_RESOLUTION),
                new PathTransition(MuSigPaths.CONFLICT_RESOLUTION,
                        RunConflictResolutionEvent.class,
                        List.of(), // todo define PhaseTransitions
                        MuSigPaths.COMPLETED)
        );

    }


    private static <E> List<E> allOf(E e1, E e2) {
        return List.of(e1, e2);
    }

    private static <E> List<E> allOf(E e1, E e2, E e3) {
        return List.of(e1, e2, e3);
    }

    private static <E> List<E> anyOf(E e1, E e2, E e3, E e4) {
        return List.of(e1, e2, e3, e4);
    }

    private static class ClassSerializer implements JsonSerializer<Class<?>> {
        @Override
        public JsonElement serialize(Class<?> src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getSimpleName());
        }
    }

    private static class OptionalTypeAdapterFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
            if (!Optional.class.isAssignableFrom(typeToken.getRawType())) {
                return null;
            }

            Type actualType = ((ParameterizedType) typeToken.getType()).getActualTypeArguments()[0];
            TypeAdapter<?> actualAdapter = gson.getAdapter(TypeToken.get(actualType));

            return (TypeAdapter<T>) new OptionalAdapter<>(actualAdapter);
        }

        private static class OptionalAdapter<T> extends TypeAdapter<Optional<T>> {
            private final TypeAdapter<T> valueAdapter;

            OptionalAdapter(TypeAdapter<T> valueAdapter) {
                this.valueAdapter = valueAdapter;
            }

            @Override
            public void write(JsonWriter out, Optional<T> value) throws IOException {
                if (value == null || value.isEmpty()) {
                    out.nullValue();
                } else {
                    valueAdapter.write(out, value.get());
                }
            }

            @Override
            public Optional<T> read(JsonReader in) throws IOException {
                T val = valueAdapter.read(in);
                return Optional.ofNullable(val);
            }
        }
    }

}
