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

import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.desktop.ServiceProvider;
import bisq.desktop_ui_harness_app.DesktopAutomationBinderTestSupport;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class SellerPaymentAccountAutomationBinderTest extends DesktopAutomationBinderTestSupport {
    @Test
    void exposesPaymentAccountSubmissionWithoutProductionSelectorMetadata() {
        var state = new SellerState1(mock(ServiceProvider.class, RETURNS_DEEP_STUBS),
                mock(BisqEasyTradeReadModel.class), mock(BisqEasyOpenTradeChannel.class));
        var view = state.getView();
        assertNoScope(view.getRoot());
        assertNoId(view.paymentAccountInput());
        assertNoId(view.sendPaymentAccountAction());

        new SellerPaymentAccountAutomationBinder().bind(view);

        assertScope(view.getRoot(), "bisq-easy-seller-payment-account");
        assertId(view.paymentAccountInput(), "account-data");
        assertId(view.sendPaymentAccountAction(), "send");
    }
}
