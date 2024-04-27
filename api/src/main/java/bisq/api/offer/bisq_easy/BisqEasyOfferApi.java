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

package bisq.api.offer.bisq_easy;


import bisq.account.payment_method.FiatPaymentMethod;
import bisq.api.Api;
import bisq.bisq_easy.BisqEasyService;
import bisq.bisq_easy.BisqEasyServiceUtil;
import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.chat.ChatService;
import bisq.chat.bisqeasy.offerbook.BisqEasyOfferbookChannel;
import bisq.chat.bisqeasy.offerbook.BisqEasyOfferbookChannelService;
import bisq.chat.bisqeasy.offerbook.BisqEasyOfferbookMessage;
import bisq.common.currency.CryptoCurrency;
import bisq.common.currency.FiatCurrency;
import bisq.common.currency.Market;
import bisq.common.currency.TradeCurrency;
import bisq.common.monetary.Monetary;
import bisq.core.CoreServiceProvider;
import bisq.network.p2p.services.data.BroadcastResult;
import bisq.offer.Direction;
import bisq.offer.amount.spec.AmountSpec;
import bisq.offer.amount.spec.QuoteSideFixedAmountSpec;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.offer.price.spec.FloatPriceSpec;
import bisq.offer.price.spec.PriceSpec;
import bisq.presentation.parser.AmountParser;
import bisq.presentation.parser.PercentageParser;
import bisq.settings.SettingsService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Path("/offer/bisq-easy")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Version API")
public class BisqEasyOfferApi extends Api {
    private final UserIdentityService userIdentityService;
    private final MarketPriceService marketPriceService;
    private final BisqEasyOfferbookChannelService bisqEasyOfferbookChannelService;
    private final SettingsService settingsService;
    private final BisqEasyService bisqEasyService;

    public BisqEasyOfferApi(@Context Application application) {
        CoreServiceProvider serviceProvider = getCoreServiceProvider();
        userIdentityService = serviceProvider.getUserService().getUserIdentityService();
        ChatService chatService = serviceProvider.getChatService();
        bisqEasyOfferbookChannelService = chatService.getBisqEasyOfferbookChannelService();
        marketPriceService = serviceProvider.getBondedRolesService().getMarketPriceService();
        settingsService = serviceProvider.getSettingsService();
        bisqEasyService = serviceProvider.getBisqEasyService();
    }

    // Sell 200 EUR offer with 8% over market price
    // http://localhost:2141/api/v1/offer/bisq-easy/publish/SELL/EUR/SEPA/200/8
    @Operation(description = "Creates a new user identity")
    @ApiResponse(responseCode = "200", description = "the new user identity with the give nick name",
            content = {
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PublishBisqEasyOfferResultDto.class)
                    )}
    )
    @GET
    @Path("publish/{direction}/{currency}/{payment-method}/{amount}/{float-price-in-percent}")
    public PublishBisqEasyOfferResultDto publishChatMessage(@PathParam("direction") String directionString,
                                                            @PathParam("currency") String currencyCode,
                                                            @PathParam("payment-method") String paymentMethod,
                                                            @PathParam("amount") String amountString,
                                                            @PathParam("float-price-in-percent") String percentagePriceString) {
        UserIdentity userIdentity = userIdentityService.getSelectedUserIdentity();
        Direction direction = Direction.valueOf(directionString.toUpperCase());
        TradeCurrency quoteCurrency;
        if (FiatCurrency.isFiat(currencyCode)) {
            quoteCurrency = new FiatCurrency(currencyCode);
        } else {
            quoteCurrency = new CryptoCurrency(currencyCode);
        }
        CryptoCurrency baseCurrency = CryptoCurrency.BITCOIN;
        Market market = new Market(baseCurrency.getCode(), quoteCurrency.getCode(), baseCurrency.getName(), quoteCurrency.getName());

        // TODO only support a single method atm
        List<FiatPaymentMethod> fiatPaymentMethods = List.of(FiatPaymentMethod.fromCustomName(paymentMethod));
        Monetary amount = AmountParser.parse(amountString, quoteCurrency.getCode());
        AmountSpec amountSpec = new QuoteSideFixedAmountSpec(amount.getValue());

        // TODO only support percentage price atm
        double percentage = PercentageParser.parse(percentagePriceString);
        PriceSpec priceSpec = new FloatPriceSpec(percentage);

        String chatMessageText = BisqEasyServiceUtil.createOfferBookMessageFromPeerPerspective(userIdentity.getNickName(),
                marketPriceService,
                direction,
                market,
                fiatPaymentMethods,
                amountSpec,
                priceSpec);
        BisqEasyOffer bisqEasyOffer = new BisqEasyOffer(
                userIdentity.getUserProfile().getNetworkId(),
                direction,
                market,
                amountSpec,
                priceSpec,
                new ArrayList<>(fiatPaymentMethods),
                userIdentity.getUserProfile().getTerms(),
                bisqEasyService.getMinRequiredReputationScore().get(),
                new ArrayList<>(settingsService.getSupportedLanguageCodes()));

        BisqEasyOfferbookChannel channel = bisqEasyOfferbookChannelService.findChannel(market).orElseThrow();
        BisqEasyOfferbookMessage myOfferMessage = new BisqEasyOfferbookMessage(channel.getId(),
                userIdentity.getUserProfile().getId(),
                Optional.of(bisqEasyOffer),
                Optional.of(chatMessageText),
                Optional.empty(),
                new Date().getTime(),
                false);

        AtomicReference<BroadcastResult> result = new AtomicReference<>();
        bisqEasyOfferbookChannelService.publishChatMessage(myOfferMessage, userIdentity).whenComplete((r, t) -> {
            result.set(r);
        }).join();

        // TODO we should return the BisqEasyOffer but that requires more work to create all the DTO objects used in
        //  BisqEasyOffer
        return PublishBisqEasyOfferResultDto.from(result.get());
    }
}
