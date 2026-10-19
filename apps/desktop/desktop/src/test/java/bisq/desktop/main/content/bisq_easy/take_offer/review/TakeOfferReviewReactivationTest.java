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

package bisq.desktop.main.content.bisq_easy.take_offer.review;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.common.monetary.Coin;
import bisq.common.monetary.Fiat;
import bisq.bisq_easy.BisqEasyTradeActionService;
import bisq.common.observable.Observable;
import bisq.desktop.ServiceProvider;
import bisq.desktop.main.content.bisq_easy.components.offer.PriceInput;
import bisq.desktop.main.content.bisq_easy.components.offer.ReviewDataDisplay;
import bisq.desktop.testutil.JavaFxToolkitExtension;
import bisq.offer.Direction;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeSnapshot;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static bisq.desktop.testutil.JavaFxToolkitExtension.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(JavaFxToolkitExtension.class)
class TakeOfferReviewReactivationTest {
    private final CompletableFuture<BisqEasyTradeReadModel> created = new CompletableFuture<>();
    private final CompletableFuture<BisqEasyTradeReadModel> completed = new CompletableFuture<>();
    private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class);
    private final BisqEasyOffer offer = mock(BisqEasyOffer.class, RETURNS_DEEP_STUBS);
    private final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
    private BisqEasyTradeActionService actions;
    private TakeOfferReviewController controller;
    private TakeOfferReviewModel model;

    @BeforeEach
    void setUp() {
        actions = services.getBisqEasyService().getBisqEasyTradeActionService();
        when(actions.takeOffer(any(), any(), any())).thenReturn(new BisqEasyTradeActionService.TakeOfferAttempt(created, completed));
        when(services.getSupportService().getBisqEasyMediationRequestService().selectMediator(any(), any(), any()))
                .thenReturn(Optional.of(mock(UserProfile.class)));
        when(offer.getTakersDirection()).thenReturn(Direction.SELL);
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getSnapshot()).thenReturn(mock(BisqEasyTradeSnapshot.class));
        when(trade.errorMessageObservable()).thenReturn(new Observable<>());
        when(trade.peersErrorMessageObservable()).thenReturn(new Observable<>());
        runOnFxThread(() -> {
            try (var price = mockConstruction(PriceInput.class);
                 var display = mockConstruction(ReviewDataDisplay.class);
                 var view = mockConstruction(TakeOfferReviewView.class, (mock, context) ->
                         model = (TakeOfferReviewModel) context.arguments().getFirst())) {
                controller = new TakeOfferReviewController(services, visible -> {}, target -> {});
            }
            prepareOffer();
            controller.onActivate();
        });
    }

    @AfterEach
    void tearDown() {
        runOnFxThread(controller::onDeactivate);
    }

    @Test
    void returningWhilePendingObservesTheExistingAttempt() {
        runOnFxThread(() -> {
            controller.takeOffer();
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TakeOfferReviewModel.TakeOfferStatus.SENT);
            controller.onDeactivate();
            controller.onActivate();
            controller.takeOffer();
            created.complete(trade);
            completed.complete(trade);
            assertThat(model.getBisqEasyTrade()).isSameAs(trade);
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TakeOfferReviewModel.TakeOfferStatus.SUCCESS);
            verify(actions, times(1)).takeOffer(any(), any(), any());
        });
    }

    @Test
    void returningAfterCompletionShowsTheCompletedAttempt() {
        runOnFxThread(() -> {
            controller.takeOffer();
            controller.onDeactivate();
            created.complete(trade);
            completed.complete(trade);
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TakeOfferReviewModel.TakeOfferStatus.SENT);
            controller.onActivate();
            assertThat(model.getBisqEasyTrade()).isSameAs(trade);
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TakeOfferReviewModel.TakeOfferStatus.SUCCESS);
            verify(actions, times(1)).takeOffer(any(), any(), any());
        });
    }

    private void prepareOffer() {
        model.setBisqEasyOffer(offer);
        var bitcoinMethod = BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN);
        model.setBitcoinPaymentMethodSpec(new BitcoinPaymentMethodSpec(bitcoinMethod));
        model.setFiatPaymentMethodSpec(new FiatPaymentMethodSpec(mock(FiatPaymentMethod.class)));
        model.setTakersBaseSideAmount(Coin.asBtcFromValue(10_000));
        model.setTakersQuoteSideAmount(Fiat.fromValue(10_000, "USD"));
    }
}
