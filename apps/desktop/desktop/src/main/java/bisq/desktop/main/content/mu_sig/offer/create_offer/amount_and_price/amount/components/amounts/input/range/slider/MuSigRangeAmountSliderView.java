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

package bisq.desktop.main.content.mu_sig.offer.create_offer.amount_and_price.amount.components.amounts.input.range.slider;

import bisq.desktop.common.view.View;
import bisq.desktop.components.controls.RangeSlider;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.HashSet;
import java.util.Set;

import static bisq.desktop.main.content.mu_sig.offer.create_offer.amount_and_price.amount.components.amounts.input.SliderTrackStyleHelper.getSliderTrackStyle;

@Slf4j
public class MuSigRangeAmountSliderView extends View<VBox, MuSigRangeAmountSliderModel, MuSigRangeAmountSliderController> {
    private final RangeSlider rangeAmountSlider;
    private final Set<Subscription> subscriptions = new HashSet<>();

    public MuSigRangeAmountSliderView(MuSigRangeAmountSliderModel model,
                                      MuSigRangeAmountSliderController controller) {
        super(new VBox(10), model, controller);

        rangeAmountSlider = new RangeSlider();
        rangeAmountSlider.setMin(0);
        rangeAmountSlider.setMax(1);
        rangeAmountSlider.setMaxWidth(300);

        root.getChildren().add(rangeAmountSlider);
    }

    @Override
    protected void onViewAttached() {
        rangeAmountSlider.getLowValue().bindBidirectional(model.getLowValue());
        rangeAmountSlider.getHighValue().bindBidirectional(model.getHighValue());

        subscriptions.add(EasyBind.subscribe(rangeAmountSlider.getHighValue(), value -> {
            double maxAllowedValue = model.getMaxAllowedValue().get();
            if (value.doubleValue() > maxAllowedValue) {
                rangeAmountSlider.setHighValue(maxAllowedValue);
            }

            String style = getSliderTrackStyle(maxAllowedValue);
            rangeAmountSlider.setStyle(style);
        }));
        subscriptions.add(EasyBind.subscribe(rangeAmountSlider.getLowValue(), value -> {
            double maxAllowedValue = model.getMaxAllowedValue().get();
            if (value.doubleValue() > maxAllowedValue) {
                rangeAmountSlider.setLowValue(maxAllowedValue);
            }
        }));
    }

    @Override
    protected void onViewDetached() {
        subscriptions.forEach(Subscription::unsubscribe);
        subscriptions.clear();
        rangeAmountSlider.getLowValue().unbindBidirectional(model.getLowValue());
        rangeAmountSlider.getHighValue().unbindBidirectional(model.getHighValue());
    }
}
