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

package bisq.desktop.main.content.bisq_easy.open_trades.trade_state;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.common.observable.Observable;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.Transitions;
import bisq.desktop.testutil.JavaFxToolkitExtension;
import bisq.settings.SettingsService;
import bisq.trade.bisq_easy.BisqEasyTrade;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.TAKER_SENT_TAKE_OFFER_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(JavaFxToolkitExtension.class)
@ResourceLock(value = "Transitions.settingsService", mode = ResourceAccessMode.READ_WRITE)
class TradeStateProgressTest {
    private final Observable<BisqEasyTradeState> state = new Observable<>(TAKER_SENT_TAKE_OFFER_REQUEST);
    private ServiceProvider serviceProvider;
    private TradeStateController controller;
    private TradeStateModel model;
    private TradePhaseBox phaseBox;
    private boolean deactivated;

    @BeforeEach
    void setUp() {
        serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getUseAnimations()).thenReturn(new Observable<>(false));
        Transitions.setSettingsService(settingsService);

        BisqEasyTrade trade = trade(state);
        BisqEasyOpenTradeChannel channel = channel("trade", trade);
        runOnFxThread(() -> {
            // The controller and state components are real. Capturing the model avoids attaching unrelated
            // header and mediation controls while testing the production subscriptions on the JavaFX thread.
            try (MockedConstruction<TradeStateView> ignored = mockConstruction(TradeStateView.class,
                    (view, context) -> model = (TradeStateModel) context.arguments().getFirst())) {
                controller = new TradeStateController(serviceProvider);
            }
            controller.onActivate();
            controller.setSelectedChannel(channel);
            phaseBox = new TradePhaseBox(serviceProvider);
            phaseBox.setBisqEasyTrade(trade);
            assertThat(model.getStateInfoVBox().get()).isNotNull();
        });
    }

    @AfterEach
    void tearDown() {
        runOnFxThread(() -> {
            if (controller != null && !deactivated) {
                controller.onDeactivate();
            }
            if (phaseBox != null) {
                phaseBox.setBisqEasyTrade(null);
            }
        });
        Transitions.setSettingsService(null);
    }

    @Test
    void delayedPeerStateDoesNotReplaceTheScreenAfterLocalProgress() {
        AtomicReference<VBox> screenAfterLocalAction = new AtomicReference<>();
        runOnFxThread(() -> {
            // A take-offer response arrives while the seller's UI action is already running. Its screen
            // update waits in the JavaFX queue; sending the account data advances the screen immediately.
            receiveStateInBackground();
            state.set(TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS);
            screenAfterLocalAction.set(model.getStateInfoVBox().get());
            assertThat(phaseBox.getPhaseIndex()).isEqualTo(1);
        });

        runOnFxThread(() -> {
            assertThat(model.getStateInfoVBox().get()).isSameAs(screenAfterLocalAction.get());
            assertThat(phaseBox.getPhaseIndex()).isEqualTo(1);
        });
    }

    @Test
    void delayedStateOfPreviousTradeDoesNotChangeTheSelectedTrade() {
        Observable<BisqEasyTradeState> otherState = new Observable<>(
                TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS);
        BisqEasyTrade otherTrade = trade(otherState);
        BisqEasyOpenTradeChannel otherChannel = channel("other-trade", otherTrade);
        AtomicReference<VBox> selectedScreen = new AtomicReference<>();

        runOnFxThread(() -> {
            receiveStateInBackground();
            controller.setSelectedChannel(otherChannel);
            phaseBox.setBisqEasyTrade(otherTrade);
            selectedScreen.set(model.getStateInfoVBox().get());
        });

        runOnFxThread(() -> {
            assertThat(model.getBisqEasyTrade().get()).isSameAs(otherTrade);
            assertThat(model.getStateInfoVBox().get()).isSameAs(selectedScreen.get());
            assertThat(phaseBox.getPhaseIndex()).isEqualTo(1);
        });
    }

    @Test
    void delayedStateDoesNotUpdateADeactivatedController() {
        runOnFxThread(() -> {
            receiveStateInBackground();
            controller.onDeactivate();
            deactivated = true;
            phaseBox.setBisqEasyTrade(null);
        });

        runOnFxThread(() -> assertThat(model.getStateInfoVBox().get()).isNull());
    }

    @Test
    void phaseStopsObservingWhileDetachedAndResumesOnReattach() {
        AtomicReference<Stage> stage = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                Stage window = new Stage();
                stage.set(window);
                window.setScene(new Scene(phaseBox.getView().getRoot()));
                window.getScene().setRoot(new Pane());
                state.set(TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS);
                assertThat(phaseBox.getPhaseIndex()).isZero();

                window.getScene().setRoot(phaseBox.getView().getRoot());
                assertThat(phaseBox.getPhaseIndex()).isEqualTo(1);
            });
        } finally {
            runOnFxThread(() -> {
                if (stage.get() != null) {
                    stage.get().getScene().setRoot(new Pane());
                    stage.get().close();
                }
            });
        }
    }

    private BisqEasyTrade trade(Observable<BisqEasyTradeState> tradeState) {
        BisqEasyTrade trade = mock(BisqEasyTrade.class, RETURNS_DEEP_STUBS);
        when(trade.tradeStateObservable()).thenReturn(tradeState);
        when(trade.getTradeState()).thenAnswer(invocation -> tradeState.get());
        when(trade.isSeller()).thenReturn(true);
        when(trade.getContract().getBaseSidePaymentMethodSpec())
                .thenReturn(new BitcoinPaymentMethodSpec(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)));
        return trade;
    }

    private BisqEasyOpenTradeChannel channel(String tradeId, BisqEasyTrade trade) {
        BisqEasyOpenTradeChannel channel = mock(BisqEasyOpenTradeChannel.class);
        when(channel.getTradeId()).thenReturn(tradeId);
        when(channel.isInMediationObservable()).thenReturn(new Observable<>(false));
        when(serviceProvider.getTradeService().getBisqEasyTradeService().findTrade(tradeId)).thenReturn(Optional.of(trade));
        return channel;
    }

    private void receiveStateInBackground() {
        try {
            CompletableFuture.runAsync(() -> state.set(
                            TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
