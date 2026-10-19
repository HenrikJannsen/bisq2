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

package bisq.desktop.main.content.bisq_easy.open_trades;

import bisq.chat.ChatChannel;
import bisq.chat.ChatChannelDomain;
import bisq.chat.ChatMessage;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.Transitions;
import bisq.desktop.common.view.Navigation;
import bisq.desktop.main.content.bisq_easy.open_trades.trade_state.TradeDataHeader;
import bisq.desktop.main.content.bisq_easy.open_trades.trade_state.TradeStateController;
import bisq.desktop.main.content.chat.message_container.ChatMessageContainerController;
import bisq.desktop.main.content.chat.message_container.sidebar.ChannelSidebar;
import bisq.desktop.navigation.NavigationTarget;
import bisq.desktop.testutil.JavaFxToolkitExtension;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.MockedConstruction;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.desktop.testutil.JavaFxToolkitExtension.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(JavaFxToolkitExtension.class)
@ResourceLock(value = "Transitions.settingsService", mode = ResourceAccessMode.READ_WRITE)
class TradeArchiveNavigationTest {
    private final CompletableFuture<Void> archived = new CompletableFuture<>();
    private final ObservableSet<BisqEasyTradeReadModel> trades = new ObservableSet<>();
    private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class);
    private final BisqEasyOpenTradeChannel channel = mock(BisqEasyOpenTradeChannel.class, RETURNS_DEEP_STUBS);
    private BisqEasyOpenTradesController controller;
    private boolean deactivated;

    @BeforeEach
    void setUp() {
        var services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(trade.getId()).thenReturn("trade-id");
        when(channel.getTradeId()).thenReturn("trade-id");
        when(channel.isInMediationObservable()).thenReturn(new Observable<>(false));
        trades.add(trade);
        var tradeService = services.getTradeService().getBisqEasyTradeService();
        when(tradeService.getTrades()).thenReturn(trades);
        when(tradeService.findTrade("trade-id")).thenAnswer(ignored -> trades.contains(trade) ? Optional.of(trade) : Optional.empty());
        var channels = services.getChatService().getBisqEasyOpenTradeChannelService();
        when(channels.getChannels()).thenReturn(new ObservableSet<>());
        when(channels.findChannelByTradeId("trade-id")).thenReturn(Optional.of(channel));
        when(services.getSettingsService().getBisqEasyTradeRulesConfirmed()).thenReturn(new Observable<>(true));
        when(services.getSettingsService().getUseAnimations()).thenReturn(new Observable<>(false));
        when(services.getChatService().getChatChannelSelectionService(ChatChannelDomain.BISQ_EASY_OPEN_TRADES)
                .getSelectedChannel()).thenReturn(new Observable<ChatChannel<? extends ChatMessage>>());
        when(services.getBisqEasyService().getBisqEasyTradeActionService().closeTrade(any(), any(), any(), any())).thenReturn(archived);

        runOnFxThread(() -> {
            Transitions.setSettingsService(services.getSettingsService());
            Navigation.getCurrentNavigationTarget().set(NavigationTarget.BISQ_EASY_OPEN_TRADES);
            // The production parent owns the archive intent. Unrelated visual components are mocked.
            try (var messageView = mockConstruction(ChatMessageContainerController.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var sidebar = mockConstruction(ChannelSidebar.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var tradeState = mockConstruction(TradeStateController.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var welcome = mockConstruction(OpenTradesWelcome.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var header = mockConstruction(TradeDataHeader.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var view = mockConstruction(BisqEasyOpenTradesView.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
                 var item = mockConstruction(OpenTradeListItem.class, (mock, context) -> {
                     when(mock.getTradeId()).thenReturn("trade-id");
                     when(mock.getTrade()).thenReturn(trade);
                     when(mock.getChannel()).thenReturn(channel);
                 })) {
                controller = new BisqEasyOpenTradesController(services);
                controller.onActivate();
            }
        });
    }

    @AfterEach
    void tearDown() {
        runOnFxThread(() -> {
            if (!deactivated) {
                controller.onDeactivate();
            }
            Navigation.getCurrentNavigationTarget().set(null);
            Transitions.setSettingsService(null);
        });
    }

    @Test
    void queuedOwnerUpdatesCannotRepopulateADetachedView() {
        AtomicReference<MockedConstruction<OpenTradeListItem>> items = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                items.set(mockConstruction(OpenTradeListItem.class, (mock, context) -> {
                    when(mock.getTradeId()).thenReturn("trade-id");
                    when(mock.getTrade()).thenReturn(trade);
                    when(mock.getChannel()).thenReturn(channel);
                }));
                // Keep FX busy until both owner notifications have queued and the view has detached.
                CompletableFuture.runAsync(() -> {
                    trades.remove(trade);
                    trades.add(trade);
                }).orTimeout(5, TimeUnit.SECONDS).join();
                controller.onDeactivate();
                deactivated = true;
            });
            runOnFxThread(() -> assertThat(controller.getModel().getListItems()).isEmpty());
        } finally {
            runOnFxThread(() -> {
                if (items.get() != null) {
                    items.get().close();
                }
            });
        }
    }

    @Test
    void automaticRemovalAndSelectionStillNavigateToHistory() {
        runOnFxThread(() -> {
            controller.archiveTrade(trade, channel);
            trades.remove(trade);
            controller.selectedChannelChanged(null);
            archived.complete(null);
            assertThat(Navigation.getCurrentNavigationTarget().get()).isEqualTo(NavigationTarget.BISQ_EASY_HISTORY);
        });
    }

    @Test
    void userSelectionCancelsOnlyThePendingNavigation() {
        runOnFxThread(() -> {
            controller.archiveTrade(trade, channel);
            controller.onTradeSelectionInteraction();
            controller.selectedChannelChanged(null);
            trades.remove(trade);
            assertThat(archived).isNotCancelled();
            archived.complete(null);
            assertThat(Navigation.getCurrentNavigationTarget().get()).isEqualTo(NavigationTarget.BISQ_EASY_OPEN_TRADES);
        });
    }

    @Test
    void leavingOpenTradesSuppressesLateArchiveNavigation() {
        runOnFxThread(() -> {
            controller.archiveTrade(trade, channel);
            controller.onDeactivate();
            deactivated = true;
            Navigation.getCurrentNavigationTarget().set(NavigationTarget.DASHBOARD);
            archived.complete(null);
            assertThat(Navigation.getCurrentNavigationTarget().get()).isEqualTo(NavigationTarget.DASHBOARD);
        });
    }
}
