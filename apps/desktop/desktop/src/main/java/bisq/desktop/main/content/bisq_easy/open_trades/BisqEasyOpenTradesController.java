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

package bisq.desktop.main.content.bisq_easy.open_trades;

import bisq.chat.ChatChannel;
import bisq.chat.ChatChannelDomain;
import bisq.chat.ChatMessage;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannelService;
import bisq.chat.notifications.ChatNotificationService;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.utils.AsyncTradeAction;
import bisq.desktop.common.threading.UIThread;
import bisq.desktop.common.view.Navigation;
import bisq.desktop.components.overlay.Popup;
import bisq.desktop.main.content.bisq_easy.open_trades.trade_state.TradeDataHeader;
import bisq.desktop.main.content.bisq_easy.open_trades.trade_state.TradeStateController;
import bisq.desktop.main.content.chat.ChatController;
import bisq.desktop.navigation.NavigationTarget;
import bisq.i18n.Res;
import bisq.settings.SettingsService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.user.profile.UserProfile;
import bisq.user.reputation.ReputationService;
import javafx.collections.transformation.SortedList;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class BisqEasyOpenTradesController extends ChatController<BisqEasyOpenTradesView, BisqEasyOpenTradesModel> {
    private final BisqEasyOpenTradeChannelService channelService;
    private final BisqEasyTradeService bisqEasyTradeService;
    private final SettingsService settingsService;
    private final ReputationService reputationService;
    private final ChatNotificationService chatNotificationService;
    private TradeStateController tradeStateController;
    private AsyncTradeAction archiveAction;
    private Pin channelsPin, tradesPin, tradeRulesConfirmedPin;
    private boolean active;
    private long activation;
    private OpenTradesWelcome openTradesWelcome;
    private TradeDataHeader tradeDataHeader;
    private final Map<String, Pin> isInMediationPinMap = new HashMap<>();

    public BisqEasyOpenTradesController(ServiceProvider serviceProvider) {
        super(serviceProvider, ChatChannelDomain.BISQ_EASY_OPEN_TRADES, NavigationTarget.BISQ_EASY_OPEN_TRADES);

        channelService = chatService.getBisqEasyOpenTradeChannelService();
        bisqEasyTradeService = serviceProvider.getTradeService().getBisqEasyTradeService();
        settingsService = serviceProvider.getSettingsService();
        reputationService = serviceProvider.getUserService().getReputationService();
        chatNotificationService = serviceProvider.getChatService().getChatNotificationService();
    }

    @Override
    public void createDependencies(ChatChannelDomain chatChannelDomain) {
        archiveAction = new AsyncTradeAction();
        tradeStateController = new TradeStateController(serviceProvider, this::archiveTrade, archiveAction.pendingProperty());
        openTradesWelcome = new OpenTradesWelcome();
        tradeDataHeader = new TradeDataHeader(serviceProvider, Res.get("bisqEasy.openTrades.chat.peer.description").toUpperCase());
    }

    @Override
    public BisqEasyOpenTradesModel createAndGetModel(ChatChannelDomain chatChannelDomain) {
        return new BisqEasyOpenTradesModel(chatChannelDomain);
    }

    @Override
    public BisqEasyOpenTradesView createAndGetView() {
        return new BisqEasyOpenTradesView(model,
                this,
                tradeDataHeader.getRoot(),
                chatMessageContainerController.getView().getRoot(),
                channelSidebar.getRoot(),
                tradeStateController.getView().getRoot(),
                openTradesWelcome.getView().getRoot());
    }

    @Override
    public void onActivate() {
        active = true;
        long currentActivation = ++activation;
        archiveAction.activate();
        model.getFilteredList().setPredicate(e -> false);

        tradesPin = bisqEasyTradeService.getTrades().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyTradeReadModel trade) {
                runInActivation(currentActivation, () -> handleTradeAdded(trade));
            }

            @Override
            public void onRemoved(Object element) {
                if (element instanceof BisqEasyTradeReadModel trade) {
                    runInActivation(currentActivation, () -> handleTradeRemoved(trade));
                }
            }

            @Override
            public void onCleared() {
                runInActivation(currentActivation, BisqEasyOpenTradesController.this::handleTradesCleared);
            }
        });

        channelsPin = channelService.getChannels().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyOpenTradeChannel channel) {
                runInActivation(currentActivation, () -> handleChannelAdded(channel));
            }

            @Override
            public void onRemoved(Object element) {
                if (element instanceof BisqEasyOpenTradeChannel channel) {
                    runInActivation(currentActivation, () -> handleChannelRemoved(channel));
                }
            }

            @Override
            public void onCleared() {
                runInActivation(currentActivation, BisqEasyOpenTradesController.this::handleChannelsCleared);
            }
        });

        tradeRulesConfirmedPin = settingsService.getBisqEasyTradeRulesConfirmed().addObserver(isConfirmed ->
                runInActivation(currentActivation, () -> {
                    if (isConfirmed) {
                        model.getTradeRulesAccepted().set(true);
                        maybeSelectFirst();
                        updateVisibility();
                    }
                }));

        selectedChannelPin = selectionService.getSelectedChannel().addObserver(channel ->
                runInActivation(currentActivation, () -> selectedChannelChanged(channel)));

        maybeSelectFirst();
        updateVisibility();
    }

    @Override
    public void onDeactivate() {
        active = false;
        activation++;
        archiveAction.deactivate();
        tradeRulesConfirmedPin.unbind();
        channelsPin.unbind();
        tradesPin.unbind();
        selectedChannelPin.unbind();
        isInMediationPinMap.values().forEach(Pin::unbind);
        isInMediationPinMap.clear();
        doCloseChatWindow();
        model.reset();
        resetSelectedChildTarget();
        selectionService.selectChannel(null);
    }

    @Override
    protected void selectedChannelChanged(ChatChannel<? extends ChatMessage> chatChannel) {
        super.selectedChannelChanged(chatChannel);

        UIThread.run(() -> {
            if (!hasTradeForChannel(chatChannel)) {
                model.getSelectedItem().set(null);
                tradeStateController.setSelectedChannel(null);
                tradeDataHeader.setSelectedChannel(null);
                maybeSelectFirst();
            } else if (chatChannel instanceof BisqEasyOpenTradeChannel tradeChannel) {
                UserProfile peerUserProfile = tradeChannel.getPeer();
                String peerUserName = peerUserProfile.getUserName();

                String shortTradeId = tradeChannel.getTradeId().substring(0, 8);
                model.getChatWindowTitle().set(Res.get("bisqEasy.openTrades.chat.window.title",
                        serviceProvider.getConfig().getAppName(), peerUserName, shortTradeId));

                model.getListItems().stream()
                        .filter(item -> item.getChannel().getId().equals(tradeChannel.getId()))
                        .findAny()
                        .ifPresent(item -> model.getSelectedItem().set(item));

                tradeStateController.setSelectedChannel(tradeChannel);
                tradeDataHeader.setSelectedChannel(tradeChannel);
            }
        });
    }

    private void runInActivation(long expectedActivation, Runnable action) {
        UIThread.run(() -> {
            if (active && activation == expectedActivation) {
                action.run();
            }
        });
    }

    void archiveTrade(BisqEasyTradeReadModel trade, BisqEasyOpenTradeChannel channel) {
        archiveAction.run(() -> serviceProvider.getBisqEasyService().getBisqEasyTradeActionService()
                        .closeTrade(bisqEasyTradeService, chatService.getLeavePrivateChatManager(), trade, channel),
                () -> Navigation.navigateTo(NavigationTarget.BISQ_EASY_HISTORY));
    }

    void onTradeSelectionInteraction() {
        // Automatic selection after archive must not cancel the user's navigation to history.
        archiveAction.invalidate();
    }

    void onSelectItem(OpenTradeListItem item) {
        if (item == null || !hasTradeForChannel(item.getChannel())) {
            selectionService.selectChannel(null);
        } else {
            onShowTradeRulesAcceptedWarning();
            BisqEasyOpenTradeChannel channel = item.getChannel();
            selectionService.selectChannel(channel);
            updateVisibility();
        }
    }

    void onShowTradeRulesAcceptedWarning() {
        if (!model.getFilteredList().isEmpty() && !settingsService.getBisqEasyTradeRulesConfirmed().get()) {
            new Popup().information(Res.get("bisqEasy.tradeGuide.notConfirmed.warn"))
                    .actionButtonText(Res.get("bisqEasy.tradeGuide.open"))
                    .onAction(() -> Navigation.navigateTo(NavigationTarget.BISQ_EASY_GUIDE))
                    .show();
        }
    }

    void onToggleChatWindow() {
        if (model.getChatWindow().get() == null) {
            model.getChatWindow().set(new Stage());
        } else {
            doCloseChatWindow();
        }
    }

    void onCloseChatWindow() {
        doCloseChatWindow();
    }

    private void doCloseChatWindow() {
        if (model.getChatWindow().get() != null) {
            model.getChatWindow().get().hide();
        }
        model.getChatWindow().set(null);
    }

    // Trade
    private void handleTradeAdded(BisqEasyTradeReadModel trade) {
        channelService.findChannelByTradeId(trade.getId())
                .ifPresentOrElse(channel -> handleTradeAndChannelAdded(trade, channel),
                        () -> log.warn("Trade with id {} was added but associated channel is not found.", trade.getId()));
    }

    private void handleTradeRemoved(BisqEasyTradeReadModel trade) {
        String tradeId = trade.getId();
        channelService.findChannelByTradeId(tradeId)
                .ifPresentOrElse(channel -> handleTradeAndChannelRemoved(trade),
                        () -> {
                            if (findListItem(trade).isEmpty()) {
                                log.warn("Trade with id {} was removed but associated channel and listItem is not found. " +
                                        "We ignore that call.", tradeId);
                            } else {
                                log.warn("Trade with id {} was removed but associated channel is not found but a listItem with that trade is still present." +
                                        "We call handleTradeAndChannelRemoved.", tradeId);
                                handleTradeAndChannelRemoved(trade);
                            }
                        });
    }

    private void handleTradesCleared() {
        handleClearTradesAndChannels();
    }

    // Channel
    private void handleChannelAdded(BisqEasyOpenTradeChannel channel) {
        bisqEasyTradeService.findTrade(channel.getTradeId())
                .ifPresentOrElse(trade -> handleTradeAndChannelAdded(trade, channel),
                        () -> log.warn("Channel with tradeId {} was added but associated trade is not found.", channel.getTradeId()));
    }

    private void handleChannelRemoved(BisqEasyOpenTradeChannel channel) {
        String tradeId = channel.getTradeId();
        bisqEasyTradeService.findTrade(tradeId)
                .ifPresentOrElse(this::handleTradeAndChannelRemoved,
                        () -> {
                            Optional<OpenTradeListItem> listItem = findListItem(tradeId);
                            if (listItem.isEmpty()) {
                                log.debug("Channel with tradeId {} was removed but associated trade and the listItem is not found. " +
                                        "This is expected as we first remove the trade and then the channel.", tradeId);
                            } else {
                                log.warn("Channel with tradeId {} was removed but associated trade is not found but we still have the listItem with that trade. " +
                                        "We call handleTradeAndChannelRemoved.", tradeId);
                                handleTradeAndChannelRemoved(listItem.get().getTrade());
                            }
                        });
    }

    private void handleChannelsCleared() {
        handleClearTradesAndChannels();
    }

    // TradeAndChannel
    private void handleTradeAndChannelAdded(BisqEasyTradeReadModel trade, BisqEasyOpenTradeChannel channel) {
        UIThread.run(() -> {
            if (findListItem(trade).isPresent()) {
                log.debug("We got called handleTradeAndChannelAdded but we have that trade list item already. " +
                        "This is expected as we get called both when a trade is added and the associated channel.");
                return;
            }

            model.getListItems().add(new OpenTradeListItem(channel,
                    trade,
                    reputationService,
                    chatNotificationService,
                    userProfileService));

            String tradeId = trade.getId();
            if (isInMediationPinMap.containsKey(tradeId)) {
                isInMediationPinMap.get(tradeId).unbind();
            }
            long currentActivation = activation;
            Pin pin = channel.isInMediationObservable().addObserver(isInMediation ->
                    runInActivation(currentActivation, () -> {
                        if (isInMediation != null) {
                            updateIsAnyTradeInMediation();
                        }
                    }));
            isInMediationPinMap.put(tradeId, pin);

            updatePredicate();
            maybeSelectFirst();
            updateVisibility();
        });
    }

    private void handleTradeAndChannelRemoved(BisqEasyTradeReadModel trade) {
        UIThread.run(() -> {
            String tradeId = trade.getId();
            if (findListItem(trade).isEmpty()) {
                log.warn("We got called handleTradeAndChannelRemoved but we have not found any trade list item with tradeId {}", tradeId);
                return;
            }

            OpenTradeListItem item = findListItem(trade).get();
            item.dispose();
            model.getListItems().remove(item);

            if (isInMediationPinMap.containsKey(tradeId)) {
                isInMediationPinMap.get(tradeId).unbind();
                isInMediationPinMap.remove(trade.getId());
            }
            updateIsAnyTradeInMediation();

            updatePredicate();
            maybeSelectFirst();
            updateVisibility();
        });
    }

    private void handleClearTradesAndChannels() {
        UIThread.run(() -> {
            model.getListItems().forEach(OpenTradeListItem::dispose);
            model.getListItems().clear();

            isInMediationPinMap.values().forEach(Pin::unbind);
            isInMediationPinMap.clear();
            updateIsAnyTradeInMediation();

            updatePredicate();
            maybeSelectFirst();
            updateVisibility();
        });
    }

    // Misc
    private void updatePredicate() {
        model.getFilteredList().setPredicate(e -> bisqEasyTradeService.findTrade(e.getTradeId()).isPresent());
    }

    private void maybeSelectFirst() {
        long currentActivation = activation;
        UIThread.runOnNextRenderFrame(() -> {
            if (!active || activation != currentActivation) {
                return;
            }
            SortedList<OpenTradeListItem> sortedList = model.getSortedList();
            if (!hasTradeForChannel(selectionService.getSelectedChannel().get()) &&
                    !sortedList.isEmpty()) {
                selectionService.selectChannel(sortedList.getFirst().getChannel());
            }
        });
    }

    private void updateVisibility() {
        boolean openTradesAvailable = !model.getFilteredList().isEmpty();
        model.getNoOpenTrades().set(!openTradesAvailable);

        boolean tradeRuleConfirmed = settingsService.getBisqEasyTradeRulesConfirmed().get();
        model.getTradeWelcomeVisible().set(openTradesAvailable && !tradeRuleConfirmed);
        model.getTradeStateVisible().set(openTradesAvailable && tradeRuleConfirmed);
        model.getChatVisible().set(openTradesAvailable && tradeRuleConfirmed);
    }

    private void updateIsAnyTradeInMediation() {
        long currentActivation = activation;
        UIThread.runOnNextRenderFrame(() -> {
            if (!active || activation != currentActivation) {
                return;
            }
            boolean value = channelService.getChannels().stream()
                    .anyMatch(BisqEasyOpenTradeChannel::isInMediation);
            model.getIsAnyTradeInMediation().set(value);
        });
    }

    private Optional<OpenTradeListItem> findListItem(BisqEasyTradeReadModel trade) {
        return findListItem(trade.getId());
    }

    private Optional<OpenTradeListItem> findListItem(String tradeId) {
        return model.getListItems().stream()
                .filter(item -> item.getTrade().getId().equals(tradeId))
                .findAny();
    }

    private boolean hasTradeForChannel(ChatChannel<? extends ChatMessage> chatChannel) {
        return chatChannel instanceof BisqEasyOpenTradeChannel channel &&
                bisqEasyTradeService.findTrade(channel.getTradeId()).isPresent();
    }
}
