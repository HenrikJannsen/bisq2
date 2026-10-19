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

import bisq.bonded_roles.release.AppType;
import bisq.trade.ServiceProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

final class BisqEasyTradeServiceTestSupport {
    private BisqEasyTradeServiceTestSupport() {
    }

    static BisqEasyTradeService service(ServiceProvider serviceProvider) {
        BisqEasyTradeService service = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        when(service.getPersistence().persistSnapshotAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        return service;
    }

    static <T extends Throwable> T assertFutureFailure(Class<T> type, Runnable action) {
        return assertInstanceOf(type, assertThrows(CompletionException.class, action::run).getCause());
    }
}
