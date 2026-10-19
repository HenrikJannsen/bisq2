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

package bisq.desktop.main.content.bisq_easy.trade_wizard.review;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
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

import java.util.List;
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
class TradeWizardReviewReactivationTest {
    private final CompletableFuture<BisqEasyTradeReadModel> created = new CompletableFuture<>();
    private final CompletableFuture<BisqEasyTradeReadModel> completed = new CompletableFuture<>();
    private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class);
    private final BisqEasyOffer offer = mock(BisqEasyOffer.class, RETURNS_DEEP_STUBS);
    private final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
    private BisqEasyTradeActionService actions;
    private TradeWizardReviewController controller;
    private TradeWizardReviewModel model;

    @BeforeEach
    void setUp() {
        actions = services.getBisqEasyService().getBisqEasyTradeActionService();
        when(actions.takeOffer(any(), any(), any())).thenReturn(new BisqEasyTradeActionService.TakeOfferAttempt(created, completed));
        when(services.getSupportService().getBisqEasyMediationRequestService().selectMediator(any(), any(), any()))
                .thenReturn(Optional.of(mock(UserProfile.class)));
        when(offer.getDirection()).thenReturn(Direction.SELL);
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getSnapshot()).thenReturn(mock(BisqEasyTradeSnapshot.class));
        when(trade.errorMessageObservable()).thenReturn(new Observable<>());
        when(trade.peersErrorMessageObservable()).thenReturn(new Observable<>());
        runOnFxThread(() -> {
            try (var price = mockConstruction(PriceInput.class);
                 var display = mockConstruction(ReviewDataDisplay.class);
                 var view = mockConstruction(TradeWizardReviewView.class, (mock, context) ->
                         model = (TradeWizardReviewModel) context.arguments().getFirst())) {
                controller = new TradeWizardReviewController(services, visible -> {}, target -> {});
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
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TradeWizardReviewModel.TakeOfferStatus.SENT);
            controller.onDeactivate();
            controller.onActivate();
            controller.takeOffer();
            created.complete(trade);
            completed.complete(trade);
            assertThat(model.getBisqEasyTrade()).isSameAs(trade);
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TradeWizardReviewModel.TakeOfferStatus.SUCCESS);
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
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TradeWizardReviewModel.TakeOfferStatus.SENT);
            controller.onActivate();
            assertThat(model.getBisqEasyTrade()).isSameAs(trade);
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TradeWizardReviewModel.TakeOfferStatus.SUCCESS);
            verify(actions, times(1)).takeOffer(any(), any(), any());
        });
    }

    @Test
    void resetDoesNotAttachThePreviousAttemptToANewReview() {
        runOnFxThread(() -> {
            controller.takeOffer();
            controller.onDeactivate();
            controller.reset();
            prepareOffer();
            controller.onActivate();
            created.complete(trade);
            completed.complete(trade);
            assertThat(model.getBisqEasyTrade()).isNull();
            assertThat(model.getTakeOfferStatus().get()).isEqualTo(TradeWizardReviewModel.TakeOfferStatus.NOT_STARTED);
            assertThat(created).isNotCancelled();
            assertThat(completed).isNotCancelled();
        });
    }

    private void prepareOffer() {
        model.setBisqEasyOffer(offer);
        var bitcoinMethod = BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN);
        model.setBitcoinPaymentMethods(List.of(bitcoinMethod));
        model.setTakersSelectedBitcoinPaymentMethod(bitcoinMethod);
        model.setTakersSelectedFiatPaymentMethod(mock(FiatPaymentMethod.class));
    }
}
