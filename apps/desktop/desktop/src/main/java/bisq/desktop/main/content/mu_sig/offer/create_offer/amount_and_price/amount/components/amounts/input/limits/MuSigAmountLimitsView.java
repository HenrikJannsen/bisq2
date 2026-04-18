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

import bisq.desktop.common.view.View;
import bisq.desktop.components.containers.Spacer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MuSigAmountLimitsView extends View<VBox, MuSigAmountLimitsModel, MuSigAmountLimitsController> {
    private final Label minTradeAmountLimitValue, maxTradeAmountLimitValue, minTradeAmountLimitCode, maxTradeAmountLimitCode,
            minTradeAmountLimitInUsdValue, maxTradeAmountLimitInUsdValue;
    private final HBox  tradeAmountLimitsBox, tradeAmountLimitsInUsdBox;

    public MuSigAmountLimitsView(MuSigAmountLimitsModel model,
                                 MuSigAmountLimitsController controller) {
        super(new VBox(10), model, controller);

        minTradeAmountLimitValue = new Label();
        minTradeAmountLimitValue.getStyleClass().add("range-value");
        minTradeAmountLimitCode = new Label();
        minTradeAmountLimitCode.getStyleClass().add("range-code");
        HBox minTradeAmountLimitBox = new HBox(2, minTradeAmountLimitValue, minTradeAmountLimitCode);
        minTradeAmountLimitBox.setAlignment(Pos.BASELINE_LEFT);

        maxTradeAmountLimitValue = new Label();
        maxTradeAmountLimitValue.getStyleClass().add("range-value");
        maxTradeAmountLimitCode = new Label();
        maxTradeAmountLimitCode.getStyleClass().add("range-code");
        HBox maxTradeAmountLimitBox = new HBox(2, maxTradeAmountLimitValue, maxTradeAmountLimitCode);
        maxTradeAmountLimitBox.setAlignment(Pos.BASELINE_RIGHT);

        // limits in USD
        minTradeAmountLimitInUsdValue = new Label();
        minTradeAmountLimitInUsdValue.getStyleClass().add("range-value-secondary");
        Label minTradeAmountLimitInUsdCode = new Label("USD");
        minTradeAmountLimitInUsdCode.getStyleClass().add("range-code-secondary");
        HBox minTradeAmountLimitInUsdBox = new HBox(2, minTradeAmountLimitInUsdValue, minTradeAmountLimitInUsdCode);
        minTradeAmountLimitInUsdBox.setAlignment(Pos.BASELINE_LEFT);

        maxTradeAmountLimitInUsdValue = new Label();
        maxTradeAmountLimitInUsdValue.getStyleClass().add("range-value-secondary");
        Label maxTradeAmountLimitInUsdCode = new Label("USD");
        maxTradeAmountLimitInUsdCode.getStyleClass().add("range-code-secondary");
        HBox maxTradeAmountLimitInUsdBox = new HBox(2, maxTradeAmountLimitInUsdValue, maxTradeAmountLimitInUsdCode);
        maxTradeAmountLimitInUsdBox.setAlignment(Pos.BASELINE_RIGHT);

        tradeAmountLimitsBox = new HBox(minTradeAmountLimitBox, Spacer.fillHBox(), maxTradeAmountLimitBox);
        tradeAmountLimitsInUsdBox = new HBox(minTradeAmountLimitInUsdBox, Spacer.fillHBox(), maxTradeAmountLimitInUsdBox);
        tradeAmountLimitsInUsdBox.setOpacity(0.5);

        VBox vBox = new VBox( tradeAmountLimitsBox, tradeAmountLimitsInUsdBox);
        vBox.setMaxWidth(model.getAmountBoxWidth() + 40);

        VBox.setMargin(vBox, new Insets(30, 0, 0, 0));
        root.getChildren().addAll(vBox);
        root.setAlignment(Pos.TOP_CENTER);
    }

    @Override
    protected void onViewAttached() {
        minTradeAmountLimitValue.textProperty().bind(model.getFormattedMinTradeAmountLimit());
        minTradeAmountLimitCode.textProperty().bind(model.getTradeAmountLimitCode());

        maxTradeAmountLimitValue.textProperty().bind(model.getFormattedMaxTradeAmountLimit());
        maxTradeAmountLimitCode.textProperty().bind(model.getTradeAmountLimitCode());

        minTradeAmountLimitInUsdValue.textProperty().bind(model.getFormattedMinTradeAmountLimitInUsd());
        maxTradeAmountLimitInUsdValue.textProperty().bind(model.getFormattedMaxTradeAmountLimitInUsd());

        tradeAmountLimitsInUsdBox.visibleProperty().bind(model.getShowTradeAmountLimitInUsd());
        tradeAmountLimitsInUsdBox.managedProperty().bind(model.getShowTradeAmountLimitInUsd());
    }

    @Override
    protected void onViewDetached() {
        minTradeAmountLimitValue.textProperty().unbind();
        minTradeAmountLimitCode.textProperty().unbind();

        maxTradeAmountLimitValue.textProperty().unbind();
        maxTradeAmountLimitCode.textProperty().unbind();

        minTradeAmountLimitInUsdValue.textProperty().unbind();
        maxTradeAmountLimitInUsdValue.textProperty().unbind();

        tradeAmountLimitsInUsdBox.visibleProperty().unbind();
        tradeAmountLimitsInUsdBox.managedProperty().unbind();
    }

}
