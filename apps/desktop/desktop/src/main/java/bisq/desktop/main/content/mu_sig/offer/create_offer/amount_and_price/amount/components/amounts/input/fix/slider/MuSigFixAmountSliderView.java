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

package bisq.desktop.main.content.mu_sig.offer.create_offer.amount_and_price.amount.components.amounts.input.fix.slider;

import bisq.desktop.common.view.View;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
public class MuSigFixAmountSliderView extends View<VBox, MuSigFixAmountSliderModel, MuSigFixAmountSliderController> {
    private final Slider fixedAmountSlider;
    private final ChangeListener<Number> maxOrFixedAmountSliderValueListener, minAmountSliderValueListener;
    private Subscription  sliderTrackStylePin;

    public MuSigFixAmountSliderView(MuSigFixAmountSliderModel model,
                                    MuSigFixAmountSliderController controller) {
        super(new VBox(10), model, controller);

        fixedAmountSlider = new Slider();
        fixedAmountSlider.setMin(model.getSliderMin());
        fixedAmountSlider.setMax(model.getSliderMax());
        fixedAmountSlider.getStyleClass().add("fixed-amount-slider");


        VBox sliderBox = new VBox( fixedAmountSlider);
        sliderBox.setMaxWidth(model.getAmountBoxWidth() + 40);

        VBox.setMargin(sliderBox, new Insets(30, 0, 0, 0));
        root.getChildren().addAll(sliderBox);
        root.setAlignment(Pos.TOP_CENTER);

        maxOrFixedAmountSliderValueListener = (observable, oldValue, newValue) -> {
            double maxAllowedSliderValue = controller.onGetMaxAllowedSliderValue();
            fixedAmountSlider.setValue(Math.min(newValue.doubleValue(), maxAllowedSliderValue));
        };
        minAmountSliderValueListener = (observable, oldValue, newValue) -> {
            double maxAllowedSliderValue = controller.onGetMaxAllowedSliderValue();
        };
    }

    @Override
    protected void onViewAttached() {
        sliderTrackStylePin = EasyBind.subscribe(model.getSliderTrackStyle(), trackStyle -> {
            fixedAmountSlider.setStyle(trackStyle);
        });

        fixedAmountSlider.valueProperty().bindBidirectional(model.getMaxOrFixedAmountSliderValue());
        fixedAmountSlider.valueProperty().addListener(maxOrFixedAmountSliderValueListener);
        model.getMaxOrFixedAmountSliderFocus().bind(fixedAmountSlider.focusedProperty());
    }

    @Override
    protected void onViewDetached() {
        sliderTrackStylePin.unsubscribe();

        fixedAmountSlider.valueProperty().unbindBidirectional(model.getMaxOrFixedAmountSliderValue());
        fixedAmountSlider.valueProperty().removeListener(maxOrFixedAmountSliderValueListener);
        model.getMaxOrFixedAmountSliderFocus().unbind();
    }

}
