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
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.HashSet;
import java.util.Set;

@Slf4j
public class MuSigFixAmountSliderView extends View<VBox, MuSigFixAmountSliderModel, MuSigFixAmountSliderController> {
    private static final String SLIDER_TRACK_DEFAULT_COLOR = "-bisq-dark-grey-50";
    private static final String SLIDER_TRACK_MARKER_COLOR = "-bisq2-green";

    private final Slider slider;
    private final Set<Subscription> subscriptions = new HashSet<>();

    public MuSigFixAmountSliderView(MuSigFixAmountSliderModel model,
                                    MuSigFixAmountSliderController controller) {
        super(new VBox(10), model, controller);

        slider = new Slider(0, 1, 0);
        slider.getStyleClass().add("fixed-amount-slider");

        root.getChildren().add(slider);
    }

    @Override
    protected void onViewAttached() {
        slider.valueProperty().bindBidirectional(model.getGetSliderValue());
        subscriptions.add(EasyBind.subscribe(slider.valueProperty(), value -> {
            double maxAllowedValue = model.getMaxAllowedValue().get();
            if (value.doubleValue() > maxAllowedValue) {
                slider.setValue(maxAllowedValue);
            }

            applySliderTrackStyle(maxAllowedValue);
        }));
    }

    @Override
    protected void onViewDetached() {
        subscriptions.forEach(Subscription::unsubscribe);
        subscriptions.clear();
        slider.valueProperty().unbindBidirectional(model.getGetSliderValue());
    }

    /**
     * Defines a horizontal linear gradient used to simulate a "filled" portion
     * of a JavaFX Slider track.
     * <p>
     * The gradient uses duplicated color stops at the same percentage positions
     * to create hard transitions (no blending) between colors:
     * <p>
     * - From 0% to X%: filled color (-bisq2-green)
     * - From X% to 100%: unfilled color (-bisq-dark-grey-50)
     * <p>
     * The value X (e.g. 1.2%) represents the current slider position as a percentage
     * and is typically updated dynamically to reflect the slider's value.
     * <p>
     * This approach is used because JavaFX Slider does not provide a built-in
     * progress/fill track visualization.
     */
    private void applySliderTrackStyle(double value) {
        double rightPercentage = 0;
        if (value < 1) {
            rightPercentage = value * 100;

            // Adjust values to match slider knob better
            if (rightPercentage < 2) {
                rightPercentage += 1.2;
            } else if (rightPercentage < 8) {
                rightPercentage += 1;
            } else if (rightPercentage < 15) {
                rightPercentage += 0.9;
            } else if (rightPercentage < 24) {
                rightPercentage += 0.7;
            } else if (rightPercentage < 60) {
                rightPercentage += 0.5;
            }
        }

        // E.g.: -bisq-dark-grey-50 0%, -bisq-dark-grey-50 30.0%, -bisq2-green 30.0%, -bisq2-green 60.0%, -bisq-dark-grey-50 60.0%, -bisq-dark-grey-50 100%)
        String segments = String.format(
                SLIDER_TRACK_DEFAULT_COLOR + " 0%%, " +
                        SLIDER_TRACK_DEFAULT_COLOR + " %1$.1f%%, " +

                        SLIDER_TRACK_MARKER_COLOR + " %1$.1f%%, " +
                        SLIDER_TRACK_MARKER_COLOR + " %2$.1f%%, " +

                        SLIDER_TRACK_DEFAULT_COLOR + " %2$.1f%%, " +
                        SLIDER_TRACK_DEFAULT_COLOR + " 100%%)",
                0d, rightPercentage);
        String style = "-track-color: linear-gradient(to right, " + segments + ";";
        slider.setStyle(style);
    }
}
