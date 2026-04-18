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
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MuSigFixAmountSliderView extends View<VBox, MuSigFixAmountSliderModel, MuSigFixAmountSliderController> {
    private final Slider slider;

    public MuSigFixAmountSliderView(MuSigFixAmountSliderModel model,
                                    MuSigFixAmountSliderController controller) {
        super(new VBox(10), model, controller);

        slider = new Slider(0,1,0);
        slider.getStyleClass().add("fixed-amount-slider");

        root.getChildren().add(slider);
    }

    @Override
    protected void onViewAttached() {
        slider.valueProperty().bindBidirectional(model.getSliderValue());
        slider.styleProperty().bind(model.getSliderTrackStyle());
    }

    @Override
    protected void onViewDetached() {
        slider.valueProperty().unbindBidirectional(model.getSliderValue());
        slider.styleProperty().unbind();
    }

}
