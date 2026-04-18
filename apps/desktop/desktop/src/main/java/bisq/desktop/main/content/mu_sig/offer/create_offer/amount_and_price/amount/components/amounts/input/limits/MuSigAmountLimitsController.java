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

package bisq.desktop.main.content.mu_sig.offer.create_offer.amount_and_price.amount.components.amounts.input.limits;

import bisq.common.observable.Pin;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.threading.UIThread;
import bisq.desktop.common.view.Controller;
import bisq.offer.mu_sig.draft.CreateOfferDraftWorkflow;
import bisq.presentation.formatters.AmountFormatter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.Subscription;

import java.util.HashSet;
import java.util.Set;

@Slf4j
public class MuSigAmountLimitsController implements Controller {
    private final MuSigAmountLimitsModel model;
    @Getter
    private final MuSigAmountLimitsView view;
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final Set<Pin> pins = new HashSet<>();
    private final CreateOfferDraftWorkflow createOfferDraftWorkflow;

    public MuSigAmountLimitsController(ServiceProvider serviceProvider,
                                       CreateOfferDraftWorkflow createOfferDraftWorkflow) {
        this.createOfferDraftWorkflow = createOfferDraftWorkflow;
        model = new MuSigAmountLimitsModel();
        view = new MuSigAmountLimitsView(model, this);
    }


    /* --------------------------------------------------------------------- */
    // Lifecycle
    /* --------------------------------------------------------------------- */

    @Override
    public void onActivate() {
        // Domain specific
        pins.add(createOfferDraftWorkflow.inputAmountLimitsObservable().addObserver(inputAmountLimits -> {
            UIThread.run(() -> {
                model.getFormattedMinTradeAmountLimit().set(AmountFormatter.formatAmountByMonetaryType(inputAmountLimits.getMin()));
                model.getFormattedMaxTradeAmountLimit().set(AmountFormatter.formatAmountByMonetaryType(inputAmountLimits.getMax()));
            });
        }));
    }

    @Override
    public void onDeactivate() {
        subscriptions.forEach(Subscription::unsubscribe);
        subscriptions.clear();
        pins.forEach(Pin::unbind);
        pins.clear();
    }


    /* --------------------------------------------------------------------- */
    // Public API
    /* --------------------------------------------------------------------- */



    /* --------------------------------------------------------------------- */
    // UI handlers
    /* --------------------------------------------------------------------- */


    double onGetMaxAllowedSliderValue() {
        return 1;
      /*  MonetaryRange rangeQuoteSideAmount = model.getRangeQuoteSideAmount().get();
        if (rangeQuoteSideAmount == null) {
            return 0;
        }
        Monetary maxRangeQuoteSideAmount = rangeQuoteSideAmount.getMax();
        return getSliderValue(maxRangeQuoteSideAmount.getValue());*/
    }
}
