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

package bisq.core;

import bisq.application.ApplicationService;

public interface CoreServiceProvider {
    ApplicationService.Config getApplicationConfig();

    bisq.security.SecurityService getSecurityService();

    java.util.Optional<bisq.wallets.core.WalletService> getWalletService();

    bisq.network.NetworkService getNetworkService();

    bisq.identity.IdentityService getIdentityService();

    bisq.bonded_roles.BondedRolesService getBondedRolesService();

    bisq.account.AccountService getAccountService();

    bisq.offer.OfferService getOfferService();

    bisq.contract.ContractService getContractService();

    bisq.user.UserService getUserService();

    bisq.chat.ChatService getChatService();

    bisq.settings.SettingsService getSettingsService();

    bisq.support.SupportService getSupportService();

    bisq.presentation.notifications.SendNotificationService getSendNotificationService();

    bisq.trade.TradeService getTradeService();

    bisq.updater.UpdaterService getUpdaterService();

    bisq.bisq_easy.BisqEasyService getBisqEasyService();

    bisq.bonded_roles.security_manager.alert.AlertNotificationsService getAlertNotificationsService();
}
