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

import bisq.desktop.common.view.Model;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;

@Getter
public class MuSigAmountLimitsModel implements Model {
    private final StringProperty formattedMinTradeAmountLimit = new SimpleStringProperty();
    private final StringProperty formattedMaxTradeAmountLimit = new SimpleStringProperty();
    private final StringProperty formattedMinTradeAmountLimitInUsd = new SimpleStringProperty();
    private final StringProperty formattedMaxTradeAmountLimitInUsd = new SimpleStringProperty();
    private final StringProperty tradeAmountLimitCode = new SimpleStringProperty();
    private final BooleanProperty showTradeAmountLimitInUsd = new SimpleBooleanProperty();
}