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

import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionChangeMapper;
import bisq.common.observable.collection.CollectionObserver;
import bisq.common.observable.collection.ReadOnlyObservableSet;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** A set whose owner publishes immutable membership snapshots; callers can only observe it. */
@Slf4j
final class BisqEasyPublishedSet<T> extends AbstractSet<T> implements ReadOnlyObservableSet<T> {
    private volatile Set<T> snapshot = Set.of();
    private final List<CollectionObserver<T>> observers = new ArrayList<>();

    synchronized void publish(Collection<? extends T> values) {
        Set<T> next = Set.copyOf(values);
        Set<T> previous = snapshot;
        snapshot = next;
        List<T> removed = previous.stream().filter(value -> !next.contains(value)).toList();
        List<T> added = next.stream().filter(value -> !previous.contains(value)).toList();
        if (removed.isEmpty() && added.isEmpty()) {
            return;
        }
        List.copyOf(observers).forEach(observer -> notifyObserver(() -> {
            if (!removed.isEmpty()) {
                observer.onAllRemoved(removed);
            }
            if (!added.isEmpty()) {
                observer.onAllAdded(added);
            }
        }));
    }

    @Override
    public synchronized Pin addObserver(CollectionObserver<T> observer) {
        observers.add(observer);
        notifyObserver(() -> observer.onAllAdded(snapshot));
        return () -> removeObserver(observer);
    }

    @Override
    public synchronized Pin addObserver(Runnable observer) {
        CollectionObserver<T> adapter = new CollectionObserver<>() {
            @Override
            public void onAdded(T element) {
                observer.run();
            }

            @Override
            public void onRemoved(Object element) {
                observer.run();
            }

            @Override
            public void onCleared() {
                observer.run();
            }
        };
        observers.add(adapter);
        notifyObserver(observer);
        return () -> removeObserver(adapter);
    }

    @Override
    public synchronized <R> Pin addCollectionChangeMapper(Collection<R> collection,
                                                          Function<T, Boolean> filterFunction,
                                                          Function<T, R> mapFunction,
                                                          Consumer<Runnable> executor) {
        CollectionChangeMapper<T, R> mapper = new CollectionChangeMapper<>(collection, filterFunction, mapFunction, executor);
        observers.add(mapper);
        notifyObserver(() -> mapper.onAllSet(snapshot));
        return () -> removeObserver(mapper);
    }

    private synchronized void removeObserver(CollectionObserver<T> observer) {
        observers.remove(observer);
    }

    private void notifyObserver(Runnable notification) {
        try {
            notification.run();
        } catch (Exception exception) {
            log.error("Trade collection observer failed", exception);
        }
    }

    @Override
    public Set<T> getUnmodifiableSet() {
        return snapshot;
    }

    @Override
    public Iterator<T> iterator() {
        return snapshot.iterator();
    }

    @Override
    public int size() {
        return snapshot.size();
    }

    @Override
    public boolean contains(Object value) {
        return snapshot.contains(value);
    }

    @Override
    public boolean add(T value) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public boolean remove(Object value) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public boolean addAll(Collection<? extends T> values) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public boolean removeAll(Collection<?> values) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public boolean retainAll(Collection<?> values) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public boolean removeIf(Predicate<? super T> filter) {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Trade membership is owned by the trade service");
    }
}
