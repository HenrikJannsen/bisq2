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

import bisq.common.observable.collection.CollectionObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BisqEasyPublishedSetTest {
    @Test
    void readersCannotMutateThePublishedMembershipOrItsIterator() {
        BisqEasyPublishedSet<String> published = new BisqEasyPublishedSet<>();
        published.publish(List.of("first"));
        Set<String> previous = published.getUnmodifiableSet();
        var iterator = published.iterator();
        assertThat(iterator.next()).isEqualTo("first");

        assertThrows(UnsupportedOperationException.class, () -> published.add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> published.remove("first"));
        assertThrows(UnsupportedOperationException.class, () -> published.addAll(List.of("extra")));
        assertThrows(UnsupportedOperationException.class, () -> published.removeAll(List.of("first")));
        assertThrows(UnsupportedOperationException.class, () -> published.retainAll(Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> published.removeIf(value -> true));
        assertThrows(UnsupportedOperationException.class, published::clear);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> previous.remove("first"));

        published.publish(List.of("second"));
        assertThat(previous).containsExactly("first");
        assertThat(published).containsExactly("second");
    }

    @Test
    void membershipChangesAreVisibleTogetherBeforeNotifications() {
        BisqEasyPublishedSet<String> published = new BisqEasyPublishedSet<>();
        published.publish(List.of("first"));
        List<Set<String>> observed = new ArrayList<>();
        var pin = published.addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(String value) {
                observed.add(published.getUnmodifiableSet());
            }

            @Override
            public void onRemoved(Object value) {
                observed.add(published.getUnmodifiableSet());
            }

            @Override
            public void onCleared() {
                observed.add(published.getUnmodifiableSet());
            }
        });

        published.publish(List.of("second", "third"));
        pin.unbind();
        published.publish(List.of());

        assertThat(observed.getFirst()).containsExactly("first");
        assertThat(observed.subList(1, observed.size())).hasSize(3)
                .allSatisfy(snapshot -> assertThat(snapshot).containsExactlyInAnyOrder("second", "third"));
    }

    @Test
    void mappedCollectionsKeepExistingMappingAndRemovalSemantics() {
        BisqEasyPublishedSet<String> published = new BisqEasyPublishedSet<>();
        published.publish(List.of("one", "skip"));
        List<String> target = new ArrayList<>();
        var pin = published.addCollectionChangeMapper(target, value -> !value.equals("skip"),
                String::toUpperCase, Runnable::run);
        assertThat(target).containsExactly("ONE");

        published.publish(List.of("two"));
        assertThat(target).containsExactly("TWO");
        pin.unbind();
        published.publish(List.of("three"));
        assertThat(target).containsExactly("TWO");
    }
}
