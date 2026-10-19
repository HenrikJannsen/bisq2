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

package bisq.desktop.main.content.bisq_easy.history;

import bisq.chat.ChatChannelDomain;
import bisq.chat.ChatService;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.threading.UIThread;
import bisq.desktop.common.utils.AsyncTradeAction;
import bisq.desktop.common.view.Controller;
import bisq.desktop.common.view.Navigation;
import bisq.desktop.components.overlay.Popup;
import bisq.desktop.main.content.bisq_easy.open_trades.trade_details.TradeDetailsController;
import bisq.desktop.main.content.bisq_easy.TradesUtils;
import bisq.desktop.navigation.NavigationTarget;
import bisq.i18n.Res;
import bisq.settings.DontShowAgainService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.bisq_easy.BisqEasyClosedTradeReadModel;
import bisq.user.profile.UserProfile;
import bisq.user.reputation.ReputationService;
import lombok.Getter;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class BisqEasyHistoryController implements Controller {
    @Getter
    private final BisqEasyHistoryView view;
    private final BisqEasyHistoryModel model;
    private final BisqEasyTradeService bisqEasyTradeService;
    private final ReputationService reputationService;
    private final DontShowAgainService dontShowAgainService;
    private final ChatService chatService;
    private Pin closedTradesPin;
    private final AsyncTradeAction deleteAction = new AsyncTradeAction();
    private String searchText = "";
    private final Set<String> knownTradeIds = new HashSet<>();
    private long activationGeneration;

    public BisqEasyHistoryController(ServiceProvider serviceProvider) {
        model = new BisqEasyHistoryModel();
        view = new BisqEasyHistoryView(model, this);
        bisqEasyTradeService = serviceProvider.getTradeService().getBisqEasyTradeService();
        reputationService = serviceProvider.getUserService().getReputationService();
        dontShowAgainService = serviceProvider.getDontShowAgainService();
        chatService = serviceProvider.getChatService();
    }

    @Override
    public void onActivate() {
        long generation = ++activationGeneration;
        deleteAction.activate();
        model.getBisqEasyTradeHistoryListItems().clear();
        knownTradeIds.clear();
        closedTradesPin = bisqEasyTradeService.getClosedTrades().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyClosedTradeReadModel closedTrade) {
                runIfCurrentActivation(generation, () -> {
                    if (knownTradeIds.add(closedTrade.trade().getId())) {
                        model.getBisqEasyTradeHistoryListItems().add(
                                new BisqEasyTradeHistoryListItem(closedTrade, reputationService));
                        updatePlaceholderText();
                    }
                });
            }

            @Override
            public void onRemoved(Object element) {
                if (element instanceof BisqEasyClosedTradeReadModel closedTrade) {
                    runIfCurrentActivation(generation, () -> {
                        knownTradeIds.remove(closedTrade.trade().getId());
                        findListItem(closedTrade.trade().getId())
                                .ifPresent(item -> model.getBisqEasyTradeHistoryListItems().remove(item));
                        updatePlaceholderText();
                    });
                }
            }

            @Override
            public void onCleared() {
                runIfCurrentActivation(generation, () -> {
                    model.getBisqEasyTradeHistoryListItems().clear();
                    knownTradeIds.clear();
                    updatePlaceholderText();
                });
            }
        });
        updatePlaceholderText();
    }

    @Override
    public void onDeactivate() {
        activationGeneration++;
        deleteAction.deactivate();
        closedTradesPin.unbind();
    }

    private void runIfCurrentActivation(long generation, Runnable update) {
        UIThread.run(() -> {
            if (activationGeneration == generation) {
                update.run();
            }
        });
    }

    void applySearchPredicate(String searchText) {
        this.searchText = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        model.setSearchStringPredicate(createSearchPredicate());
        applyPredicates();
        updatePlaceholderText();
    }

    void onShowTradeDetails(BisqEasyTradeHistoryListItem item) {
        Navigation.navigateTo(NavigationTarget.BISQ_EASY_TRADE_DETAILS,
                new TradeDetailsController.InitData(item.getTrade(), item.getMyUserProfile(), item.getPeersUserProfile(),
                        item.getTrade().getContract().getMediator()));
    }

    void onContactPeer(UserProfile peer) {
        chatService.createAndSelectTwoPartyPrivateChatChannel(ChatChannelDomain.DISCUSSION, peer)
                .ifPresent(channel -> Navigation.navigateTo(NavigationTarget.CHAT_PRIVATE));
    }

    void onExportTradeData(BisqEasyTradeReadModel trade) {
        TradesUtils.exportTrade(trade, getView().getRoot().getScene());
    }

    void onDeleteTrade(BisqEasyTradeReadModel trade) {
        String key = "deleteArchivedTrade";
        if (dontShowAgainService.showAgain(key)) {
            new Popup().warning(Res.get("bisqEasy.history.table.actionButtons.deleteArchivedTrade.popup.info"))
                    .actionButtonText(Res.get("bisqEasy.history.table.actionButtons.deleteArchivedTrade.popup.actionButton"))
                    .onAction(() -> doDeleteTrade(trade))
                    .closeButtonText(Res.get("action.cancel"))
                    .dontShowAgainId(key)
                    .show();
        } else {
            doDeleteTrade(trade);
        }
    }

    private void doDeleteTrade(BisqEasyTradeReadModel trade) {
        deleteAction.run(() -> bisqEasyTradeService.deleteTrade(trade));
    }

    private void applyPredicates() {
        model.getFilteredBisqEasyTradeHistoryListItems().setPredicate(null);
        model.getFilteredBisqEasyTradeHistoryListItems().setPredicate(model.getBisqEasyTradeHistoryListItemsPredicate());
    }

    private Predicate<BisqEasyTradeHistoryListItem> createSearchPredicate() {
        return item -> searchText.isEmpty()
                || item.getMyUserProfile().getUserName().toLowerCase().contains(searchText)
                || item.getPeersUserProfile().getUserName().toLowerCase().contains(searchText)
                || item.getTradeId().toLowerCase().contains(searchText)
                || item.getDateString().toLowerCase().contains(searchText)
                || item.getMarket().getMarketDisplayName().toLowerCase().contains(searchText)
                || item.getMarket().getBaseCurrencyCode().toLowerCase().contains(searchText)
                || item.getMarket().getQuoteCurrencyCode().toLowerCase().contains(searchText)
                || item.getBaseAmountString().toLowerCase().contains(searchText)
                || item.getQuoteAmountWithCodeString().toLowerCase().contains(searchText)
                || item.getPriceWithCodeString().toLowerCase().contains(searchText)
                || item.getPaymentMethodAsString().toLowerCase().contains(searchText)
                || item.getMyRole().toLowerCase().contains(searchText);
    }

    private void updatePlaceholderText() {
        if (model.getBisqEasyTradeHistoryListItems().isEmpty()) {
            model.getPlaceholderText().set(Res.get("bisqEasy.history.noTrades"));
        } else if (model.getFilteredBisqEasyTradeHistoryListItems().isEmpty()) {
            model.getPlaceholderText().set(Res.get("bisqEasy.history.noMatchingTrades"));
        }
    }

    private Optional<BisqEasyTradeHistoryListItem> findListItem(String tradeId) {
        return model.getBisqEasyTradeHistoryListItems().stream()
                .filter(item -> item.getTrade().getId().equals(tradeId))
                .findAny();
    }
}
