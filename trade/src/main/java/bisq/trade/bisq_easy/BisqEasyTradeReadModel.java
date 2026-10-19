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

package bisq.trade.bisq_easy;

import bisq.common.fsm.State;
import bisq.common.monetary.Monetary;
import bisq.common.monetary.PriceQuote;
import bisq.common.observable.Pin;
import bisq.common.observable.ReadOnlyObservable;
import bisq.contract.Role;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.trade.TradeLifecycleState;
import bisq.trade.TradeRole;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.exceptions.TradeProtocolFailure;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stable read handle for a trade. The execution owner publishes complete snapshots before notifying observers.
 * It never exposes the mutable protocol model, signing keys, or writable model observables.
 */
@Slf4j
@Getter
public final class BisqEasyTradeReadModel {
    private final String id;
    private final BisqEasyContract contract;
    private final TradeRole tradeRole;
    private final String myIdentityTag;
    private final NetworkId myNetworkId;
    private final Party taker;
    private final Party maker;
    private volatile BisqEasyTradeSnapshot snapshot;

    @Getter(lombok.AccessLevel.NONE)
    private final Object publicationLock = new Object();
    @Getter(lombok.AccessLevel.NONE)
    private final List<Property<?>> properties = new ArrayList<>();
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<BisqEasyTradeSnapshot> snapshots = property(Function.identity());
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<BisqEasyTradeState> tradeState = property(BisqEasyTradeSnapshot::tradeState);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<State> state = property(BisqEasyTradeSnapshot::tradeState);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<Role> interruptTradeInitiator = property(BisqEasyTradeSnapshot::interruptTradeInitiator);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<Optional<String>> paymentAccountData = property(BisqEasyTradeSnapshot::paymentAccountData);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<Optional<String>> bitcoinPaymentData = property(BisqEasyTradeSnapshot::bitcoinPaymentData);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<Optional<String>> paymentProof = property(BisqEasyTradeSnapshot::paymentProof);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<Optional<Long>> tradeCompletedDate = property(BisqEasyTradeSnapshot::tradeCompletedDate);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<String> errorMessage = property(BisqEasyTradeSnapshot::errorMessage);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<String> errorStackTrace = property(BisqEasyTradeSnapshot::errorStackTrace);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<TradeProtocolFailure> tradeProtocolFailure = property(BisqEasyTradeSnapshot::tradeProtocolFailure);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<String> peersErrorMessage = property(BisqEasyTradeSnapshot::peersErrorMessage);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<String> peersErrorStackTrace = property(BisqEasyTradeSnapshot::peersErrorStackTrace);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<TradeProtocolFailure> peersTradeProtocolFailure = property(BisqEasyTradeSnapshot::peersTradeProtocolFailure);
    @Getter(lombok.AccessLevel.NONE)
    private final ReadOnlyObservable<TradeLifecycleState> lifecycleState = property(BisqEasyTradeSnapshot::lifecycleState);

    BisqEasyTradeReadModel(BisqEasyTrade trade) {
        id = trade.getId();
        contract = trade.getContract();
        tradeRole = trade.getTradeRole();
        myIdentityTag = trade.getMyIdentity().getTag();
        myNetworkId = trade.getMyIdentity().getNetworkId();
        taker = new Party(trade.getTaker().getNetworkId());
        maker = new Party(trade.getMaker().getNetworkId());
        snapshot = BisqEasyTradeSnapshot.from(trade);
    }

    void publish(BisqEasyTradeSnapshot next) {
        Objects.requireNonNull(next);
        synchronized (publicationLock) {
            BisqEasyTradeSnapshot previous = snapshot;
            snapshot = next;
            properties.forEach(property -> property.publish(previous, next));
        }
    }

    public ReadOnlyObservable<BisqEasyTradeSnapshot> snapshotObservable() {
        return snapshots;
    }

    public BisqEasyTradeState getTradeState() {
        return snapshot.tradeState();
    }

    public ReadOnlyObservable<BisqEasyTradeState> tradeStateObservable() {
        return tradeState;
    }

    public State getState() {
        return snapshot.tradeState();
    }

    public ReadOnlyObservable<State> stateObservable() {
        return state;
    }

    public ReadOnlyObservable<Role> getInterruptTradeInitiator() {
        return interruptTradeInitiator;
    }

    public Optional<String> getPaymentAccountData() {
        return snapshot.paymentAccountData();
    }

    public ReadOnlyObservable<Optional<String>> paymentAccountDataObservable() {
        return paymentAccountData;
    }

    public Optional<String> getBitcoinPaymentData() {
        return snapshot.bitcoinPaymentData();
    }

    public ReadOnlyObservable<Optional<String>> bitcoinPaymentDataObservable() {
        return bitcoinPaymentData;
    }

    public Optional<String> getPaymentProof() {
        return snapshot.paymentProof();
    }

    public ReadOnlyObservable<Optional<String>> paymentProofObservable() {
        return paymentProof;
    }

    public Optional<Long> getTradeCompletedDate() {
        return snapshot.tradeCompletedDate();
    }

    public ReadOnlyObservable<Optional<Long>> tradeCompletedDateObservable() {
        return tradeCompletedDate;
    }

    @Nullable
    public String getErrorMessage() {
        return snapshot.errorMessage();
    }

    public ReadOnlyObservable<String> errorMessageObservable() {
        return errorMessage;
    }

    @Nullable
    public String getErrorStackTrace() {
        return snapshot.errorStackTrace();
    }

    public ReadOnlyObservable<String> errorStackTraceObservable() {
        return errorStackTrace;
    }

    @Nullable
    public TradeProtocolFailure getTradeProtocolFailure() {
        return snapshot.tradeProtocolFailure();
    }

    public ReadOnlyObservable<TradeProtocolFailure> tradeProtocolFailureObservable() {
        return tradeProtocolFailure;
    }

    @Nullable
    public String getPeersErrorMessage() {
        return snapshot.peersErrorMessage();
    }

    public ReadOnlyObservable<String> peersErrorMessageObservable() {
        return peersErrorMessage;
    }

    @Nullable
    public String getPeersErrorStackTrace() {
        return snapshot.peersErrorStackTrace();
    }

    public ReadOnlyObservable<String> peersErrorStackTraceObservable() {
        return peersErrorStackTrace;
    }

    @Nullable
    public TradeProtocolFailure getPeersTradeProtocolFailure() {
        return snapshot.peersTradeProtocolFailure();
    }

    public ReadOnlyObservable<TradeProtocolFailure> peersTradeProtocolFailureObservable() {
        return peersTradeProtocolFailure;
    }

    public TradeLifecycleState getLifecycleState() {
        return snapshot.lifecycleState();
    }

    public ReadOnlyObservable<TradeLifecycleState> lifecycleState() {
        return lifecycleState;
    }

    @Nullable
    public String getProtocolVersion() {
        return snapshot.protocolVersion();
    }

    public BisqEasyOffer getOffer() {
        return contract.getOffer();
    }

    public boolean isBuyer() {
        return tradeRole.isBuyer();
    }

    public boolean isSeller() {
        return tradeRole.isSeller();
    }

    public boolean isTaker() {
        return tradeRole.isTaker();
    }

    public boolean isMaker() {
        return tradeRole.isMaker();
    }

    public Party getPeer() {
        return isTaker() ? maker : taker;
    }

    public Party getMyself() {
        return isTaker() ? taker : maker;
    }

    public String getShortId() {
        return id.substring(0, 8);
    }

    public Direction getDirection() {
        return isBuyer() ? Direction.BUY : Direction.SELL;
    }

    public Monetary getBaseSideMonetary() {
        return contract.getBaseSideMonetary();
    }

    public Monetary getQuoteSideMonetary() {
        return contract.getQuoteSideMonetary();
    }

    public PriceQuote getPriceQuote() {
        return contract.getPriceQuote();
    }

    public Direction getDisplayOfferDirection() {
        return getOffer().getMarket().isBaseCurrencyBitcoin() ? getDirection() : getDirection().mirror();
    }

    public boolean isBuyerInDisplayContext() {
        return getOffer().getMarket().isBaseCurrencyBitcoin() ? isBuyer() : isSeller();
    }

    public boolean isSellerInDisplayContext() {
        return getOffer().getMarket().isBaseCurrencyBitcoin() ? isSeller() : isBuyer();
    }

    public record Party(NetworkId networkId) {
        public NetworkId getNetworkId() {
            return networkId;
        }
    }

    private <T> ReadOnlyObservable<T> property(Function<BisqEasyTradeSnapshot, T> select) {
        Property<T> property = new Property<>(select);
        properties.add(property);
        return property;
    }

    private final class Property<T> implements ReadOnlyObservable<T> {
        private final Function<BisqEasyTradeSnapshot, T> select;
        private final Set<Consumer<T>> observers = new LinkedHashSet<>();

        private Property(Function<BisqEasyTradeSnapshot, T> select) {
            this.select = select;
        }

        @Override
        public T get() {
            return select.apply(snapshot);
        }

        @Override
        public Pin addObserver(Consumer<T> observer) {
            synchronized (publicationLock) {
                observers.add(observer);
                notifyObserver(observer, get());
            }
            return () -> removeObserver(observer);
        }

        @Override
        public void removeObserver(Consumer<T> observer) {
            synchronized (publicationLock) {
                observers.remove(observer);
            }
        }

        private void publish(BisqEasyTradeSnapshot previous, BisqEasyTradeSnapshot next) {
            T value = select.apply(next);
            if (!Objects.equals(select.apply(previous), value)) {
                List.copyOf(observers).forEach(observer -> notifyObserver(observer, value));
            }
        }

        private void notifyObserver(Consumer<T> observer, T value) {
            try {
                observer.accept(value);
            } catch (Exception exception) {
                log.error("Trade observer failed for trade {}", id, exception);
            }
        }
    }
}
