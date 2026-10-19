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

import java.util.ArrayDeque;
import java.util.Queue;

/** Publication and enqueue share this monitor; external callbacks always run outside it. */
final class BisqEasyNotificationQueue {
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private boolean draining;

    synchronized void enqueue(Runnable notification) {
        pending.add(notification);
    }

    void drain() {
        synchronized (this) {
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            while (true) {
                Runnable notification;
                synchronized (this) {
                    notification = pending.poll();
                    if (notification == null) {
                        draining = false;
                        return;
                    }
                }
                notification.run();
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                draining = false;
            }
            throw failure;
        }
    }
}
