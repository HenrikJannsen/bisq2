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

package bisq.persistence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceServiceBoundaryTest {
    @Test
    void publicQueriesCannotExposeAnotherOwnersMutableStoreOrWriter() {
        List<Method> unsafeQueries = Arrays.stream(PersistenceService.class.getMethods())
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> exposesPersistenceObject(method.getGenericReturnType()))
                .toList();

        assertEquals(List.of(), unsafeQueries,
                "Registered clients must not let a caller bypass a store's execution owner");
    }

    private static boolean exposesPersistenceObject(Type type) {
        if (type instanceof Class<?> valueClass) {
            return PersistenceClient.class.isAssignableFrom(valueClass) ||
                    PersistableStore.class.isAssignableFrom(valueClass) ||
                    Persistence.class.isAssignableFrom(valueClass);
        }
        if (type instanceof ParameterizedType parameterized) {
            return exposesPersistenceObject(parameterized.getRawType()) ||
                    Arrays.stream(parameterized.getActualTypeArguments())
                            .anyMatch(PersistenceServiceBoundaryTest::exposesPersistenceObject);
        }
        return false;
    }
}
