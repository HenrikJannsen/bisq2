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

package bisq.desktop.main.content.bisq_easy.history;

import bisq.common.observable.collection.ObservableSet;
import bisq.desktop.ServiceProvider;
import bisq.desktop.testutil.JavaFxToolkitExtension;
import bisq.trade.bisq_easy.BisqEasyClosedTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.user.profile.UserProfile;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;

import java.util.concurrent.CopyOnWriteArrayList;

import static bisq.desktop.testutil.JavaFxToolkitExtension.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(JavaFxToolkitExtension.class)
class BisqEasyHistoryLifecycleTest {
    private final ObservableSet<BisqEasyClosedTradeReadModel> closedTrades = new ObservableSet<>();
    private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class);
    private BisqEasyClosedTradeReadModel closedTrade;
    private BisqEasyHistoryController controller;
    private BisqEasyHistoryModel model;
    private MockedConstruction<BisqEasyTradeHistoryListItem> items;
    private MockedConstruction<BisqEasyHistoryView> views;

    @BeforeEach
    void setUp() {
        ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(trade.getId()).thenReturn("trade-id");
        closedTrade = new BisqEasyClosedTradeReadModel(trade, mock(UserProfile.class), mock(UserProfile.class));
        closedTrades.add(closedTrade);
        when(services.getTradeService().getBisqEasyTradeService().getClosedTrades()).thenReturn(closedTrades);
        runOnFxThread(() -> {
            views = mockConstruction(BisqEasyHistoryView.class, (mock, context) ->
                    model = (BisqEasyHistoryModel) context.arguments().getFirst());
            items = mockConstruction(BisqEasyTradeHistoryListItem.class, (mock, context) -> {
                when(mock.getTrade()).thenReturn(((BisqEasyClosedTradeReadModel) context.arguments().getFirst()).trade());
                when(mock.getTradeId()).thenReturn("trade-id");
            });
            controller = new BisqEasyHistoryController(services);
            controller.onActivate();
            assertThat(model.getBisqEasyTradeHistoryListItems()).hasSize(1);
        });
    }

    @AfterEach
    void tearDown() {
        runOnFxThread(() -> {
            controller.onDeactivate();
            items.close();
            views.close();
        });
    }

    @Test
    void backgroundRemovalUpdatesTheModelOnTheFxThread() {
        CopyOnWriteArrayList<Boolean> mutationThreads = new CopyOnWriteArrayList<>();
        runOnFxThread(() -> model.getBisqEasyTradeHistoryListItems().addListener(
                (ListChangeListener<BisqEasyTradeHistoryListItem>) change ->
                        mutationThreads.add(Platform.isFxApplicationThread())));

        closedTrades.remove(closedTrade);

        runOnFxThread(() -> {
            assertThat(model.getBisqEasyTradeHistoryListItems()).isEmpty();
            assertThat(mutationThreads).containsExactly(true);
        });
    }

    @Test
    void reactivationRemovesTradesDeletedWhileTheViewWasDetached() {
        runOnFxThread(controller::onDeactivate);
        closedTrades.remove(closedTrade);

        runOnFxThread(() -> {
            controller.onActivate();
            assertThat(model.getBisqEasyTradeHistoryListItems()).isEmpty();
        });
    }

    @Test
    void queuedClearFromPreviousActivationCannotClearTheReactivatedView() {
        runOnFxThread(() -> {
            Thread publisher = Thread.ofPlatform().start(closedTrades::clear);
            try {
                publisher.join(5_000);
                assertThat(publisher.isAlive()).isFalse();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            controller.onDeactivate();
            closedTrades.add(closedTrade);
            controller.onActivate();
        });

        runOnFxThread(() -> assertThat(model.getBisqEasyTradeHistoryListItems()).singleElement()
                .satisfies(item -> assertThat(item.getTrade()).isSameAs(trade)));
    }
}
