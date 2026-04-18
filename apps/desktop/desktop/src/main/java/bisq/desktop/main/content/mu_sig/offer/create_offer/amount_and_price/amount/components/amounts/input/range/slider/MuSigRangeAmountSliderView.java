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
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
public class MuSigRangeAmountSliderView extends View<VBox, MuSigRangeAmountSliderModel, MuSigRangeAmountSliderController> {
    private final ChangeListener<Number> maxOrFixedAmountSliderValueListener, minAmountSliderValueListener;
    private final RangeSlider rangeAmountSlider;
    private Subscription  sliderTrackStylePin;

    public MuSigRangeAmountSliderView(MuSigRangeAmountSliderModel model,
                                      MuSigRangeAmountSliderController controller) {
        super(new VBox(10), model, controller);

        rangeAmountSlider = new RangeSlider();
        rangeAmountSlider.setMin(model.getSliderMin());
        rangeAmountSlider.setMax(model.getSliderMax());
        rangeAmountSlider.getStyleClass().add("amount-range-slider");


        VBox sliderBox = new VBox( rangeAmountSlider);
        sliderBox.setMaxWidth(model.getAmountBoxWidth() + 40);

        VBox.setMargin(sliderBox, new Insets(30, 0, 0, 0));
        root.getChildren().addAll(sliderBox);
        root.setAlignment(Pos.TOP_CENTER);

        maxOrFixedAmountSliderValueListener = (observable, oldValue, newValue) -> {
            double maxAllowedSliderValue = controller.onGetMaxAllowedSliderValue();
            rangeAmountSlider.getHighValue().set(Math.min(newValue.doubleValue(), maxAllowedSliderValue));
        };
        minAmountSliderValueListener = (observable, oldValue, newValue) -> {
            double maxAllowedSliderValue = controller.onGetMaxAllowedSliderValue();
            rangeAmountSlider.getLowValue().set(Math.min(newValue.doubleValue(), maxAllowedSliderValue));
        };
    }

    @Override
    protected void onViewAttached() {
        sliderTrackStylePin = EasyBind.subscribe(model.getSliderTrackStyle(), trackStyle -> {
            rangeAmountSlider.setStyle(trackStyle);
        });

        rangeAmountSlider.getLowValue().bindBidirectional(model.getMinAmountSliderValue());
        rangeAmountSlider.getHighValue().bindBidirectional(model.getMaxOrFixedAmountSliderValue());
        rangeAmountSlider.getLowValue().addListener(minAmountSliderValueListener);
        rangeAmountSlider.getHighValue().addListener(maxOrFixedAmountSliderValueListener);
        model.getRangeSliderLowThumbFocus().bind(rangeAmountSlider.getLowThumbFocused());
        model.getRangeSliderHighThumbFocus().bind(rangeAmountSlider.getHighThumbFocused());

        // Needed to trigger focusOut event on amount components
        // We handle all parents mouse events.
      /*  Parent node = root;
        while (node.getParent() != null) {
            node.setOnMousePressed(e -> root.requestFocus());
            node = node.getParent();
        }*/
    }

    @Override
    protected void onViewDetached() {
        sliderTrackStylePin.unsubscribe();

        rangeAmountSlider.getHighValue().unbindBidirectional(model.getMaxOrFixedAmountSliderValue());
        rangeAmountSlider.getHighValue().removeListener(maxOrFixedAmountSliderValueListener);
        rangeAmountSlider.getLowValue().unbindBidirectional(model.getMinAmountSliderValue());
        rangeAmountSlider.getLowValue().removeListener(minAmountSliderValueListener);
        model.getRangeSliderLowThumbFocus().unbind();
        model.getRangeSliderHighThumbFocus().unbind();
        model.getMaxOrFixedAmountSliderFocus().unbind();
    }
}
