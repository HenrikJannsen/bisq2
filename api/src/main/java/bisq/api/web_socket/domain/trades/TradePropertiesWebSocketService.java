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

package bisq.api.web_socket.domain.trades;

import bisq.api.dto.DtoMappings;
import bisq.api.web_socket.domain.BaseWebSocketService;
import bisq.api.web_socket.subscription.ModificationType;
import bisq.api.web_socket.subscription.Subscriber;
import bisq.api.web_socket.subscription.SubscriberRepository;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.trade.TradeService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static bisq.api.web_socket.subscription.Topic.TRADE_PROPERTIES;

@Slf4j
public class TradePropertiesWebSocketService extends BaseWebSocketService {
    private final BisqEasyTradeService bisqEasyTradeService;
    private final Object lifecycleLock = new Object();
    @Nullable
    private Session session;

    public TradePropertiesWebSocketService(SubscriberRepository subscriberRepository,
                                           TradeService tradeService) {
        super(subscriberRepository, TRADE_PROPERTIES);
        bisqEasyTradeService = tradeService.getBisqEasyTradeService();
    }


    @Override
    public CompletableFuture<Boolean> initialize() {
        Session initialized;
        synchronized (lifecycleLock) {
            if (session != null) {
                return CompletableFuture.completedFuture(true);
            }
            initialized = new Session();
            session = initialized;
        }
        Pin pin = bisqEasyTradeService.getTrades().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyTradeReadModel trade) {
                subscribeToTrade(initialized, trade);
            }

            @Override
            public void onRemoved(Object element) {
                if (element instanceof BisqEasyTradeReadModel trade) {
                    removeTrade(initialized, trade);
                }
            }

            @Override
            public void onCleared() {
                clearTrades(initialized);
            }
        });
        boolean retained;
        synchronized (lifecycleLock) {
            retained = session == initialized && initialized.active;
            if (retained) {
                initialized.tradesPin = pin;
            }
        }
        if (!retained) {
            pin.unbind();
        }
        return CompletableFuture.completedFuture(true);
    }

    private void subscribeToTrade(Session initialized, BisqEasyTradeReadModel trade) {
        Registration registration;
        List<Pin> replaced;
        synchronized (lifecycleLock) {
            if (session != initialized || !initialized.active) {
                return;
            }
            registration = new Registration(initialized, trade);
            Registration previous = initialized.registrations.put(trade.getId(), registration);
            replaced = previous == null ? List.of() : closeRegistration(previous);
        }
        replaced.forEach(Pin::unbind);
        registerPin(registration, () -> observeTradeState(trade, registration));
        registerPin(registration, () -> observeInterruptTradeInitiator(trade, registration));
        registerPin(registration, () -> observePaymentAccountData(trade, registration));
        registerPin(registration, () -> observeBitcoinPaymentData(trade, registration));
        registerPin(registration, () -> observePaymentProof(trade, registration));
        registerPin(registration, () -> observeErrorMessage(trade, registration));
        registerPin(registration, () -> observeErrorStackTrace(trade, registration));
        registerPin(registration, () -> observeTradeProtocolFailure(trade, registration));
        registerPin(registration, () -> observePeersErrorMessage(trade, registration));
        registerPin(registration, () -> observePeersErrorStackTrace(trade, registration));
        registerPin(registration, () -> observePeersTradeProtocolFailure(trade, registration));
        registerPin(registration, () -> observeTradeCompletedDate(trade, registration));
    }

    private void registerPin(Registration registration, Supplier<Pin> subscribe) {
        if (!registration.isActive()) {
            return;
        }
        Pin pin = subscribe.get();
        boolean retained;
        synchronized (lifecycleLock) {
            retained = registration.isActive();
            if (retained) {
                registration.pins.add(pin);
            }
        }
        if (!retained) {
            pin.unbind();
        }
    }

    private void removeTrade(Session initialized, BisqEasyTradeReadModel trade) {
        List<Pin> removed = List.of();
        synchronized (lifecycleLock) {
            Registration registration = initialized.registrations.get(trade.getId());
            if (registration != null && registration.trade == trade) {
                initialized.registrations.remove(trade.getId());
                removed = closeRegistration(registration);
            }
        }
        removed.forEach(Pin::unbind);
    }

    private void clearTrades(Session initialized) {
        List<Pin> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            initialized.registrations.values().forEach(registration -> removed.addAll(closeRegistration(registration)));
            initialized.registrations.clear();
        }
        removed.forEach(Pin::unbind);
    }

    // Registry operations hold lifecycleLock; invoking a pin or observer must happen after releasing it.
    private List<Pin> closeRegistration(Registration registration) {
        registration.active = false;
        List<Pin> removed = List.copyOf(registration.pins);
        registration.pins.clear();
        return removed;
    }


    private Pin observeTradeState(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.tradeStateObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.tradeState = Optional.of(DtoMappings.BisqEasyTradeStateMapping.fromBisq2Model(value));
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeInterruptTradeInitiator(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.getInterruptTradeInitiator().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.interruptTradeInitiator = Optional.of(DtoMappings.RoleMapping.fromBisq2Model(value));
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observePaymentAccountData(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.paymentAccountDataObservable().addObserver(value -> {
            if (value.isPresent()) {
                var data = new TradePropertiesDto();
                data.paymentAccountData = value;
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeBitcoinPaymentData(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.bitcoinPaymentDataObservable().addObserver(value -> {
            if (value.isPresent()) {
                var data = new TradePropertiesDto();
                data.bitcoinPaymentData = value;
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observePaymentProof(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.paymentProofObservable().addObserver(value -> {
            if (value.isPresent()) {
                var data = new TradePropertiesDto();
                data.paymentProof = value;
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeErrorMessage(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.errorMessageObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.errorMessage = Optional.of(value);
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeErrorStackTrace(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.errorStackTraceObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.errorStackTrace = Optional.of(value);
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeTradeProtocolFailure(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.tradeProtocolFailureObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.tradeProtocolFailure = Optional.of(DtoMappings.TradeProtocolFailureMapping.fromBisq2Model(value));
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observePeersErrorMessage(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.peersErrorMessageObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.peersErrorMessage = Optional.of(value);
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observePeersErrorStackTrace(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.peersErrorStackTraceObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.peersErrorStackTrace = Optional.of(value);
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observePeersTradeProtocolFailure(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.peersTradeProtocolFailureObservable().addObserver(value -> {
            if (value != null) {
                var data = new TradePropertiesDto();
                data.peersTradeProtocolFailure = Optional.of(DtoMappings.TradeProtocolFailureMapping.fromBisq2Model(value));
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    private Pin observeTradeCompletedDate(BisqEasyTradeReadModel bisqEasyTrade, Registration registration) {
        return bisqEasyTrade.tradeCompletedDateObservable().addObserver(value -> {
            if (value.isPresent()) {
                var data = new TradePropertiesDto();
                data.tradeCompletedDate = value;
                send(registration, Map.of(registration.trade.getId(), data));
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        List<Pin> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (session == null) {
                return CompletableFuture.completedFuture(true);
            }
            Session closing = session;
            session = null;
            closing.active = false;
            if (closing.tradesPin != null) {
                removed.add(closing.tradesPin);
                closing.tradesPin = null;
            }
            closing.registrations.values().forEach(registration -> removed.addAll(closeRegistration(registration)));
            closing.registrations.clear();
        }
        removed.forEach(Pin::unbind);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public Optional<String> getJsonPayload() {
        List<Map<String, TradePropertiesDto>> maps = bisqEasyTradeService.getTrades().stream()
                .map(bisqEasyTrade -> {
                    var snapshot = bisqEasyTrade.getSnapshot();
                    var data = new TradePropertiesDto();
                    data.tradeState = Optional.ofNullable(DtoMappings.BisqEasyTradeStateMapping.fromBisq2Model(snapshot.tradeState()));
                    data.interruptTradeInitiator = Optional.ofNullable(DtoMappings.RoleMapping.fromBisq2Model(snapshot.interruptTradeInitiator()));
                    data.paymentAccountData = snapshot.paymentAccountData();
                    data.bitcoinPaymentData = snapshot.bitcoinPaymentData();
                    data.paymentProof = snapshot.paymentProof();
                    data.errorMessage = Optional.ofNullable(snapshot.errorMessage());
                    data.errorStackTrace = Optional.ofNullable(snapshot.errorStackTrace());
                    data.tradeProtocolFailure = Optional.ofNullable(DtoMappings.TradeProtocolFailureMapping.fromBisq2Model(snapshot.tradeProtocolFailure()));
                    data.peersErrorMessage = Optional.ofNullable(snapshot.peersErrorMessage());
                    data.peersErrorStackTrace = Optional.ofNullable(snapshot.peersErrorStackTrace());
                    data.peersTradeProtocolFailure = Optional.ofNullable(DtoMappings.TradeProtocolFailureMapping.fromBisq2Model(snapshot.peersTradeProtocolFailure()));
                    data.tradeCompletedDate = snapshot.tradeCompletedDate();
                    return Map.of(bisqEasyTrade.getId(), data);
                })
                .collect(Collectors.toList());
        return toJson(maps);
    }

    private void send(Registration registration, Map<String, TradePropertiesDto> map) {
        if (!registration.isActive()) {
            return;
        }
        List<Map<String, TradePropertiesDto>> maps = Collections.singletonList(map);
        // The payload is defined as a list to support batch data delivery at subscribe.
        List<Subscriber> subscribers = subscriberRepository.findSubscribers(topic).values().stream()
                .flatMap(Collection::stream)
                .toList();
        if (subscribers.isEmpty()) {
            return;
        }
        toJson(maps).ifPresent(json -> subscribers.forEach(subscriber -> {
            if (registration.isActive()) {
                send(json, subscriber, ModificationType.REPLACE);
            }
        }));
    }

    private static final class Session {
        private final Map<String, Registration> registrations = new HashMap<>();
        private volatile boolean active = true;
        @Nullable
        private Pin tradesPin;
    }

    private static final class Registration {
        private final Session session;
        private final BisqEasyTradeReadModel trade;
        private final List<Pin> pins = new ArrayList<>();
        private volatile boolean active = true;

        private Registration(Session session, BisqEasyTradeReadModel trade) {
            this.session = session;
            this.trade = trade;
        }

        private boolean isActive() {
            return active && session.active;
        }
    }
}
