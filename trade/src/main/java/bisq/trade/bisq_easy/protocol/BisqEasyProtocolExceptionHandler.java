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

package bisq.trade.bisq_easy.protocol;

import bisq.common.fsm.Event;
import bisq.common.util.ExceptionUtil;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeProtocolException;
import bisq.trade.bisq_easy.BisqEasyTrade;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage;
import bisq.trade.protocol.events.SendTradeMessageHandler;
import lombok.extern.slf4j.Slf4j;

import static bisq.common.util.StringUtils.createUid;
import static bisq.common.util.StringUtils.truncate;
import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE;
import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_STACKTRACE;

@Slf4j
public class BisqEasyProtocolExceptionHandler extends SendTradeMessageHandler<BisqEasyTrade> {
    protected BisqEasyProtocolExceptionHandler(ServiceProvider serviceProvider, BisqEasyTrade model) {
        super(serviceProvider, model);
    }

    @Override
    public void handle(Event event) {
        TradeProtocolException tradeProtocolException = (TradeProtocolException) event;
        commitToModel(ExceptionUtil.getRootCauseMessage(tradeProtocolException),
                ExceptionUtil.getStackTraceAsString(tradeProtocolException));

        // We do not send the error message to the peer as there is risk that private data could be leaked.
        // Instead, we send the stack of causes as error message.
        // We might send more meaningful error messages in the future.
        String errorMessage = truncate(ExceptionUtil.getCauseStackClassNames(tradeProtocolException), MAX_LENGTH_ERROR_MESSAGE);
        String stackTrace = truncate(ExceptionUtil.getSafeStackTraceAsString(tradeProtocolException), MAX_LENGTH_STACKTRACE);
        log.warn("We send the cause stack and stackTrace to our peer.\n" +
                "errorMessage={}\nstackTrace={}", errorMessage, stackTrace);
        sendMessage(new BisqEasyReportErrorMessage(createUid(),
                trade.getId(),
                trade.getMyIdentity().getNetworkId(),
                trade.getPeer().getNetworkId(),
                errorMessage,
                stackTrace));
    }

    private void commitToModel(String errorMessage, String errorStackTrace) {
        trade.setErrorMessage(errorMessage);
        trade.setErrorStackTrace(errorStackTrace);
    }
}