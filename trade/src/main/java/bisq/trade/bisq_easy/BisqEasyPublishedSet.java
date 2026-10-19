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
    private final List<Registration> observers = new ArrayList<>();
    private final BisqEasyNotificationQueue notifications = new BisqEasyNotificationQueue();

    void publish(Collection<? extends T> values) {
        synchronized (notifications) {
            Set<T> next = Set.copyOf(values);
            Set<T> previous = snapshot;
            snapshot = next;
            List<T> removed = previous.stream().filter(value -> !next.contains(value)).toList();
            List<T> added = next.stream().filter(value -> !previous.contains(value)).toList();
            if (removed.isEmpty() && added.isEmpty()) {
                return;
            }
            List<Registration> recipients = List.copyOf(observers);
            notifications.enqueue(() -> recipients.forEach(registration -> registration.notifyObserver(observer -> {
                if (!removed.isEmpty()) {
                    observer.onAllRemoved(removed);
                }
                if (!added.isEmpty()) {
                    observer.onAllAdded(added);
                }
            })));
        }
        notifications.drain();
    }

    @Override
    public Pin addObserver(CollectionObserver<T> observer) {
        Registration registration = new Registration(observer);
        synchronized (notifications) {
            observers.add(registration);
            Set<T> initial = snapshot;
            notifications.enqueue(() -> registration.notifyObserver(current -> current.onAllAdded(initial)));
        }
        notifications.drain();
        return () -> removeObserver(registration);
    }

    @Override
    public Pin addObserver(Runnable observer) {
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
        Registration registration = new Registration(adapter);
        synchronized (notifications) {
            observers.add(registration);
            notifications.enqueue(() -> registration.notifyObserver(unused -> observer.run()));
        }
        notifications.drain();
        return () -> removeObserver(registration);
    }

    @Override
    public <R> Pin addCollectionChangeMapper(Collection<R> collection,
                                                          Function<T, Boolean> filterFunction,
                                                          Function<T, R> mapFunction,
                                                          Consumer<Runnable> executor) {
        CollectionChangeMapper<T, R> mapper = new CollectionChangeMapper<>(collection, filterFunction, mapFunction, executor);
        Registration registration = new Registration(mapper);
        synchronized (notifications) {
            observers.add(registration);
            Set<T> initial = snapshot;
            notifications.enqueue(() -> registration.notifyObserver(current -> current.onAllSet(initial)));
        }
        notifications.drain();
        return () -> removeObserver(registration);
    }

    private void removeObserver(Registration registration) {
        synchronized (notifications) {
            registration.active = false;
            observers.remove(registration);
        }
    }

    private final class Registration {
        private final CollectionObserver<T> observer;
        private volatile boolean active = true;

        private Registration(CollectionObserver<T> observer) {
            this.observer = observer;
        }

        private void notifyObserver(Consumer<CollectionObserver<T>> notification) {
            if (active) {
                try {
                    notification.accept(observer);
                } catch (Exception exception) {
                    log.error("Trade collection observer failed: {}", exception.getClass().getSimpleName());
                }
            }
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
