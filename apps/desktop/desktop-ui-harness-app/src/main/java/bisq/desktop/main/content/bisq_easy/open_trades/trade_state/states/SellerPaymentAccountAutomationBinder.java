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

package bisq.desktop.main.content.bisq_easy.open_trades.trade_state.states;

import bisq.desktop_ui_harness_app.AbstractDesktopAutomationViewBinder;

public final class SellerPaymentAccountAutomationBinder extends AbstractDesktopAutomationViewBinder<SellerState1.View> {
    @Override
    public Class<SellerState1.View> viewType() {
        return SellerState1.View.class;
    }

    @Override
    public void bind(SellerState1.View view) {
        scope(view.getRoot(), "bisq-easy-seller-payment-account");
        id(view.paymentAccountInput(), "account-data");
        id(view.sendPaymentAccountAction(), "send");
    }
}
