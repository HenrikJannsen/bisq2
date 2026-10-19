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

import bisq.common.observable.collection.ObservableSet;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.ProtobufUtils;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.util.ExceptionUtil;
import bisq.persistence.PersistableStore;
import bisq.trade.bisq_easy.protocol.BisqEasyClosedTrade;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
public final class BisqEasyTradeStore implements PersistableStore<BisqEasyTradeStore> {
    private final ObservableSet<BisqEasyTrade> trades = new ObservableSet<>();

    // We keep track of all trades by storing the trade IDs to avoid that the same trade can be taken again.
    private final ObservableSet<String> tradeIds = new ObservableSet<>();
    private final ObservableSet<BisqEasyClosedTrade> closedTrades = new ObservableSet<>();

    // Copies of the trades as the last task of each trade persisted them. A write reads only these copies: the tasks of
    // other trades change their live trades at the same time, and a write of a live trade could store the trade in the
    // middle of a transition (docs/specifications/trade/trade-task-execution.md).
    private final Map<String, BisqEasyTrade> copyOfTradeById = new ConcurrentHashMap<>();
    private final Map<String, BisqEasyClosedTrade> copyOfClosedTradeById = new ConcurrentHashMap<>();

    BisqEasyTradeStore(Set<BisqEasyTrade> trades, Set<String> tradeIds, Set<BisqEasyClosedTrade> closedTrades) {
        this.trades.setAll(trades);
        this.tradeIds.setAll(tradeIds);
        this.closedTrades.setAll(closedTrades);
    }

    @Override
    public bisq.trade.protobuf.BisqEasyTradeStore.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.BisqEasyTradeStore.newBuilder()
                .addAllTrades(trades.stream()
                        .map(trade -> {
                            try {
                                return trade.toProto(serializeForHash);
                            } catch (Exception e) {
                                log.error("Could not create proto from BisqEasyTrade {}", trade, e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()))
                .addAllTradeIds(tradeIds)
                .addAllBisqEasyClosedTrades(closedTrades.stream()
                        .map(closedTrade -> {
                            try {
                                return closedTrade.toProto(serializeForHash);
                            } catch (Exception e) {
                                log.error("Could not create proto from BisqEasyClosedTrade {}", closedTrade, e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())
                );
    }

    @Override
    public bisq.trade.protobuf.BisqEasyTradeStore toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static BisqEasyTradeStore fromProto(bisq.trade.protobuf.BisqEasyTradeStore proto)
            throws UnresolvableProtobufException {
        try {
            var trades = ProtobufUtils.fromProtoStreamSkippingInvalid(proto.getTradesList(), BisqEasyTrade::fromProto)
                    .collect(Collectors.toSet());
            var closedTrades = ProtobufUtils.fromProtoStreamSkippingInvalid(proto.getBisqEasyClosedTradesList(),
                            BisqEasyClosedTrade::fromProto)
                    .collect(Collectors.toSet());
            return new BisqEasyTradeStore(trades, new HashSet<>(proto.getTradeIdsList()), closedTrades);
        } catch (RuntimeException e) {
            throw new UnresolvableProtobufException(e);
        }
    }

    @Override
    public ProtoResolver<PersistableStore<?>> getResolver() {
        return any -> {
            try {
                return fromProto(any.unpack(bisq.trade.protobuf.BisqEasyTradeStore.class));
            } catch (InvalidProtocolBufferException e) {
                throw new UnresolvableProtobufMessageException(e);
            }
        };
    }

    // The store for a write. It contains the copies which addTrade, applyPersisted and updateCopyOfTrade took.
    @Override
    public BisqEasyTradeStore getClone() {
        return new BisqEasyTradeStore(Set.copyOf(copyOfTradeById.values()),
                Set.copyOf(tradeIds),
                Set.copyOf(copyOfClosedTradeById.values()));
    }

    @Override
    public void applyPersisted(BisqEasyTradeStore persisted) {
        trades.setAll(persisted.getTrades());
        tradeIds.setAll(persisted.getTradeIds());
        closedTrades.setAll(persisted.getClosedTrades());
        copyOfTradeById.clear();
        copyOfClosedTradeById.clear();
        trades.forEach(trade -> copyOfTradeById.put(trade.getId(), copyOf(trade)));
        closedTrades.forEach(closedTrade -> copyOfClosedTradeById.put(closedTrade.trade().getId(), copyOf(closedTrade)));
    }

    /**
     * Takes the copy of the trade for the next write, as the trade is in this store: open, closed, or removed. Must run
     * in a task of the trade, so that no other task changes the trade meanwhile.
     */
    void updateCopyOfTrade(String tradeId) {
        findTrade(tradeId).ifPresentOrElse(trade -> copyOfTradeById.put(tradeId, copyOf(trade)),
                () -> copyOfTradeById.remove(tradeId));
        closedTrades.stream()
                .filter(closedTrade -> closedTrade.trade().getId().equals(tradeId))
                .findAny()
                .ifPresentOrElse(closedTrade -> copyOfClosedTradeById.put(tradeId, copyOf(closedTrade)),
                        () -> copyOfClosedTradeById.remove(tradeId));
    }

    ObservableSet<BisqEasyTrade> getAllTrades() {
        ObservableSet<BisqEasyTrade> allTrades = new ObservableSet<>();
        allTrades.addAll(trades);
        allTrades.addAll(closedTrades.stream().map(BisqEasyClosedTrade::trade).collect(Collectors.toSet()));
        return allTrades;
    }

    // Runs in a task of the trade, or before the trade service starts
    void addTrade(BisqEasyTrade trade) {
        trades.add(trade);
        tradeIds.add(trade.getId());
        copyOfTradeById.put(trade.getId(), copyOf(trade));
    }

    Optional<BisqEasyTrade> findTrade(String tradeId) {
        return trades.stream().filter(trade -> trade.getId().equals(tradeId)).findAny();
    }

    boolean tradeExists(String tradeId) {
        return tradeIds.contains(tradeId);
    }

    // A copy through the protobuf form. If that fails, the write uses the live trade, as before the copies.
    private static BisqEasyTrade copyOf(BisqEasyTrade trade) {
        try {
            return BisqEasyTrade.fromProto(trade.toProto(false));
        } catch (Exception e) {
            // The message of the exception can contain data of the trade, so we log only its type and stack trace
            log.error("Could not copy the trade {} for writing. We write the trade itself. Cause: {}",
                    trade.getId(), ExceptionUtil.getSafeStackTraceAsString(e));
            return trade;
        }
    }

    private static BisqEasyClosedTrade copyOf(BisqEasyClosedTrade closedTrade) {
        try {
            return BisqEasyClosedTrade.fromProto(closedTrade.toProto(false));
        } catch (Exception e) {
            log.error("Could not copy the closed trade {} for writing. We write the closed trade itself. Cause: {}",
                    closedTrade.trade().getId(), ExceptionUtil.getSafeStackTraceAsString(e));
            return closedTrade;
        }
    }
}
