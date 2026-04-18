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

import bisq.common.observable.Pin;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.threading.UIThread;
import bisq.desktop.common.view.Controller;
import bisq.offer.mu_sig.draft.CreateOfferDraftWorkflow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class MuSigFixAmountSliderController implements Controller {
    static final String SLIDER_TRACK_DEFAULT_COLOR = "-bisq-dark-grey-50";
    static final String SLIDER_TRACK_MARKER_COLOR = "-bisq2-green";

    private final MuSigFixAmountSliderModel model;
    @Getter
    private final MuSigFixAmountSliderView view;
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final Set<Pin> pins = new HashSet<>();
    private final CreateOfferDraftWorkflow createOfferDraftWorkflow;

    public MuSigFixAmountSliderController(ServiceProvider serviceProvider,
                                          CreateOfferDraftWorkflow createOfferDraftWorkflow) {
        this.createOfferDraftWorkflow = createOfferDraftWorkflow;
        model = new MuSigFixAmountSliderModel();
        view = new MuSigFixAmountSliderView(model, this);
    }


    /* --------------------------------------------------------------------- */
    // Lifecycle
    /* --------------------------------------------------------------------- */

    @Override
    public void onActivate() {
        subscriptions.add(EasyBind.subscribe(model.getSliderValue(),
                value -> {
                    if (value != null) {
                        createOfferDraftWorkflow.setFixTradeAmountFromSliderValue(value.doubleValue());
                    }
                }));
        pins.add(createOfferDraftWorkflow.fixAmountSliderValueObservable().addObserver(sliderValue -> {
            UIThread.run(() -> {
                if (sliderValue != null) {
                    model.getSliderValue().set(sliderValue);
                }
            });
        }));

        pins.add(createOfferDraftWorkflow.userSpecificTradeAmountLimitAsSliderValueObservable().addObserver(value -> {
            UIThread.run(() -> {
                applySliderTrackStyle(value);
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
    // Private
    /* --------------------------------------------------------------------- */


    /**
     * Defines a horizontal linear gradient used to simulate a "filled" portion
     * of a JavaFX Slider track.
     *
     * The gradient uses duplicated color stops at the same percentage positions
     * to create hard transitions (no blending) between colors:
     *
     * - From 0% to X%: filled color (-bisq2-green)
     * - From X% to 100%: unfilled color (-bisq-dark-grey-50)
     *
     * The value X (e.g. 1.2%) represents the current slider position as a percentage
     * and is typically updated dynamically to reflect the slider's value.
     *
     * This approach is used because JavaFX Slider does not provide a built-in
     * progress/fill track visualization.
     */
    private void applySliderTrackStyle(Optional<Double> value) {
        double rightPercentage = 0;
        if (value.isPresent()) {
            rightPercentage = value.get() * 100;
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
        model.getSliderTrackStyle().set(style);
    }
}
