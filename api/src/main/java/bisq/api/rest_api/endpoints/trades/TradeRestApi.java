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

package bisq.api.rest_api.endpoints.trades;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.api.dto.presentation.closed_trades.ClosedTradeIndexedItem;
import bisq.api.dto.trade.bisq_easy.protocol.BisqEasyTradeStateDto;
import bisq.api.rest_api.endpoints.RestApiBase;
import bisq.api.rest_api.endpoints.offers.CreateOfferRequest;
import bisq.api.rest_api.pagination.PaginatedResponse;
import bisq.api.rest_api.pagination.PaginationParams;
import bisq.api.rest_api.pagination.SortDirection;
import bisq.api.web_socket.domain.ClosedTradeItemsService;
import bisq.bisq_easy.BisqEasyTradeActionService;
import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.chat.ChatChannelDomain;
import bisq.chat.ChatChannelSelectionService;
import bisq.chat.ChatService;
import bisq.chat.bisq_easy.offerbook.BisqEasyOfferbookChannelService;
import bisq.chat.bisq_easy.offerbook.BisqEasyOfferbookMessage;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannelService;
import bisq.chat.priv.LeavePrivateChatManager;
import bisq.common.monetary.Fiat;
import bisq.common.monetary.Monetary;
import bisq.common.util.StringUtils;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.i18n.Res;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.presentation.formatters.AmountFormatter;
import bisq.support.SupportService;
import bisq.support.mediation.bisq_easy.BisqEasyMediationRequestService;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Path("/trades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Bisq Easy Trade API")
public class TradeRestApi extends RestApiBase {
    private final BisqEasyOfferbookChannelService bisqEasyOfferbookChannelService;
    private final MarketPriceService marketPriceService;
    private final UserIdentityService userIdentityService;
    private final BannedUserService bannedUserService;
    private final BisqEasyMediationRequestService bisqEasyMediationRequestService;
    private final BisqEasyTradeService bisqEasyTradeService;
    private final BisqEasyTradeActionService tradeActionService;
    private final BisqEasyOpenTradeChannelService bisqEasyOpenTradeChannelService;
    private final LeavePrivateChatManager leavePrivateChatManager;
    private final ChatChannelSelectionService offerbookChannelSelectionService;
    private final ClosedTradeItemsService closedTradeItemsService;

    public TradeRestApi(ChatService chatService,
                        MarketPriceService marketPriceService,
                        UserService userService,
                        SupportService supportedService,
                        TradeService tradeService,
                        BisqEasyTradeActionService tradeActionService,
                        ClosedTradeItemsService closedTradeItemsService) {
        this.bisqEasyOfferbookChannelService = chatService.getBisqEasyOfferbookChannelService();
        offerbookChannelSelectionService = chatService.getChatChannelSelectionService(ChatChannelDomain.BISQ_EASY_OFFERBOOK);
        bisqEasyOpenTradeChannelService = chatService.getBisqEasyOpenTradeChannelService();
        leavePrivateChatManager = chatService.getLeavePrivateChatManager();
        this.marketPriceService = marketPriceService;
        userIdentityService = userService.getUserIdentityService();
        bannedUserService = userService.getBannedUserService();
        bisqEasyMediationRequestService = supportedService.getBisqEasyMediationRequestService();
        bisqEasyTradeService = tradeService.getBisqEasyTradeService();
        this.closedTradeItemsService = closedTradeItemsService;
        this.tradeActionService = tradeActionService;
    }

    @GET
    @Path("/closed")
    @Operation(
            summary = "Get closed trades",
            description = "Returns closed trades (completed, cancelled, rejected, or failed) for the current user. " +
                    "Paginated. Query params: 'page' (1-indexed, default 1), 'pageSize' (default 20, max 100), " +
                    "'sortBy' (DATE|MARKET|QUOTE_AMOUNT|BASE_AMOUNT|ROLE, default DATE), " +
                    "'direction' (ASC|DESC, default DESC), " +
                    "'search' (case-insensitive substring matched against trade id, market, role, peer username/nym, " +
                    "payment methods, formatted price/amount strings), " +
                    "'role' (BUYER|SELLER, optional), " +
                    "'state' (BisqEasyTradeStateDto value; repeatable or comma-separated, optional).",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Closed trades retrieved successfully. PaginatedResponse whose " +
                                    "'items' are ClosedTradeListItemDto (generic binding not expressible in " +
                                    "the schema annotation since PaginatedResponse is a record).",
                            content = @Content(schema = @Schema(implementation = PaginatedResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid query parameters"),
                    @ApiResponse(responseCode = "500", description = "Internal server error")
            }
    )
    public Response getClosedTrades(@QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize,
                                    @QueryParam("sortBy") String sortBy,
                                    @QueryParam("direction") String direction,
                                    @QueryParam("search") String search,
                                    @QueryParam("role") String role,
                                    @QueryParam("state") List<String> states) {
        try {
            ClosedTradesQuery.SortField field = ClosedTradesQuery.SortField.parse(
                    Optional.ofNullable(sortBy), ClosedTradesQuery.SortField.DATE);
            SortDirection sortDirection = SortDirection.parse(
                    Optional.ofNullable(direction), SortDirection.DESC);
            Optional<ClosedTradesQuery.RoleFilter> roleFilter = ClosedTradesQuery.RoleFilter.parse(
                    Optional.ofNullable(role));
            Set<BisqEasyTradeStateDto> stateFilter = ClosedTradesQuery.parseStates(states);
            List<ClosedTradeIndexedItem> filtered = ClosedTradesQuery.apply(
                    // Snapshot, not the live synchronized list: iteration during a concurrent
                    // trade-close would throw ConcurrentModificationException.
                    closedTradeItemsService.getItemsSnapshot(),
                    Optional.ofNullable(search),
                    roleFilter,
                    stateFilter,
                    field,
                    sortDirection);
            return buildPaginatedResponse(filtered,
                    PaginationParams.of(Optional.ofNullable(page), Optional.ofNullable(pageSize)),
                    ClosedTradeIndexedItem::dto);
        } catch (IllegalArgumentException e) {
            return buildErrorResponse(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Error retrieving closed trades", e);
            return buildErrorResponse("An unexpected error occurred");
        }
    }

    @POST
    @Operation(
            summary = "Create a Trade by Taking a Bisq Easy Offer",
            description = "Create a Trade by Taking a Bisq Easy Offer.",
            requestBody = @RequestBody(
                    description = "",
                    content = @Content(schema = @Schema(implementation = CreateOfferRequest.class))
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "",
                            content = @Content(schema = @Schema(example = ""))),
                    @ApiResponse(responseCode = "400", description = "Invalid input, or trading restricted by a security manager alert " +
                            "(body carries errorCode and, for MIN_VERSION_REQUIRED and MAKER_VERSION_BELOW_MIN_REQUIRED, " +
                            "minRequiredVersion)"),
                    @ApiResponse(responseCode = "500", description = "Internal server error")
            }
    )
    public void takeOffer(TakeOfferRequest request, @Suspended AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(150, TimeUnit.SECONDS); // We have 120 seconds socket timeout, so we should never get triggered here, as the message will be sent as mailbox message
        asyncResponse.setTimeoutHandler(response -> {
            response.resume(buildResponse(Response.Status.SERVICE_UNAVAILABLE, "Request timed out"));
        });

        try {
            UserIdentity takerIdentity = userIdentityService.getSelectedUserIdentity();
            checkArgument(!bannedUserService.isUserProfileBanned(takerIdentity.getUserProfile()), "Taker profile is banned");
            //noinspection OptionalGetWithoutIsPresent
            BisqEasyOffer bisqEasyOffer = bisqEasyOfferbookChannelService.getChannels().stream().flatMap(c -> c.getChatMessages().stream())
                    .filter(BisqEasyOfferbookMessage::hasBisqEasyOffer)
                    .map(e -> e.getBisqEasyOffer().get())
                    .filter(e -> e.getId().equals(request.offerId()))
                    .findFirst()
                    .orElseThrow();
            checkArgument(!bannedUserService.isUserProfileBanned(bisqEasyOffer.getMakerNetworkId()), "Maker profile is banned");
            Monetary baseSideAmount = Monetary.from(request.baseSideAmount(), bisqEasyOffer.getMarket().getBaseCurrencyCode());
            Monetary quoteSideAmount = Monetary.from(request.quoteSideAmount(), bisqEasyOffer.getMarket().getQuoteCurrencyCode());
            BitcoinPaymentMethod bitcoinPaymentMethod = PaymentMethodSpecUtil.getBitcoinPaymentMethod(request.bitcoinPaymentMethod());
            BitcoinPaymentMethodSpec bitcoinPaymentMethodSpec = new BitcoinPaymentMethodSpec(bitcoinPaymentMethod);
            FiatPaymentMethod fiatPaymentMethod = PaymentMethodSpecUtil.getFiatPaymentMethod(request.fiatPaymentMethod());
            FiatPaymentMethodSpec fiatPaymentMethodSpec = new FiatPaymentMethodSpec(fiatPaymentMethod);
            Optional<UserProfile> mediator = bisqEasyMediationRequestService.selectMediator(bisqEasyOffer.getMakersUserProfileId(),
                    takerIdentity.getId(),
                    bisqEasyOffer.getId());
            long marketPrice = marketPriceService.findMarketPrice(bisqEasyOffer.getMarket())
                    .map(e -> e.getPriceQuote().getValue())
                    .orElseThrow();
            var attempt = tradeActionService.takeOffer(() -> bisqEasyTradeService.takerCreatesTrade(takerIdentity.getIdentity(),
                    bisqEasyOffer,
                    baseSideAmount,
                    quoteSideAmount,
                    bitcoinPaymentMethodSpec,
                    fiatPaymentMethodSpec,
                    mediator,
                    marketPrice), bisqEasyTradeService, takerIdentity);
            attempt.result().whenCompleteAsync((trade, error) -> {
                if (error != null) {
                    resumeTradeFailure(asyncResponse, error);
                    return;
                }
                try {
                    bisqEasyOfferbookChannelService.findChannel(trade.getOffer().getMarket())
                            .ifPresent(offerbookChannelSelectionService::selectChannel);
                    var snapshot = trade.getSnapshot();
                    String errorStackTrace = snapshot.errorStackTrace() == null ? "" : StringUtils.truncate(snapshot.errorStackTrace(), 500);
                    checkArgument(snapshot.errorMessage() == null, "An error occurred at taking the offer: " + snapshot.errorMessage() +
                            ". ErrorStackTrace: " + errorStackTrace);
                    String peersErrorStackTrace = snapshot.peersErrorStackTrace() == null ? "" : StringUtils.truncate(snapshot.peersErrorStackTrace(), 500);
                    checkArgument(snapshot.peersErrorMessage() == null, "An error occurred at the peers side at taking the offer: " + snapshot.peersErrorMessage() +
                            ". ErrorStackTrace: " + peersErrorStackTrace);
                    asyncResponse.resume(buildResponse(Response.Status.CREATED, new TakeOfferResponse(trade.getId())));
                } catch (Exception failure) {
                    resumeTradeFailure(asyncResponse, failure);
                }
            });
        } catch (Exception error) {
            resumeTradeFailure(asyncResponse, error);
        }
    }

    private void resumeTradeFailure(AsyncResponse response, Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof TradeRestrictedException restricted) {
            response.resume(buildResponse(Response.Status.BAD_REQUEST, toTradeRestrictedErrorEntity(restricted)));
        } else if (error instanceof IllegalArgumentException || error instanceof TradeProtocolException) {
            response.resume(buildResponse(Response.Status.BAD_REQUEST, "Invalid input: " + error.getMessage()));
        } else {
            response.resume(buildErrorResponse("An unexpected error occurred: " + error.getMessage()));
        }
    }

    /**
     * All values are strings so that clients decoding the error body as a flat string map keep
     * working; the "error" text preserves the pre-structured wire format ("Invalid input: ..."),
     * which released mobile clients match on.
     */
    static Map<String, String> toTradeRestrictedErrorEntity(TradeRestrictedException exception) {
        Map<String, String> entity = new LinkedHashMap<>();
        entity.put("error", "Invalid input: " + exception.getMessage());
        entity.put("errorCode", exception.getRestriction().name());
        exception.findMinRequiredVersion().ifPresent(minVersion -> entity.put("minRequiredVersion", minVersion));
        return entity;
    }

    @PATCH
    @Path("/{tradeId}/event")
    @Operation(
            summary = "Process a trade event for a specific trade ID",
            description = "This endpoint allows processing various trade events such as rejecting, canceling, " +
                    "closing a trade, or handling payment details from buyers and sellers. " +
                    "It interacts with the open trade channel and manages state transitions based on the event type.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Trade event processed successfully"),
                    @ApiResponse(responseCode = "404", description = "Trade or trade channel not found"),
                    @ApiResponse(responseCode = "400", description = "Invalid trade event data, or trading restricted by a security manager alert " +
                            "(body carries errorCode and, for MIN_VERSION_REQUIRED, minRequiredVersion)"),
                    @ApiResponse(responseCode = "500", description = "Internal server error")
            }
    )
    public void processTradeEvent(@PathParam("tradeId") String tradeId,
                                  TradeEventDto tradeEvent,
                                  @Suspended AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(10, TimeUnit.SECONDS);
        asyncResponse.setTimeoutHandler(response -> {
            response.resume(buildResponse(Response.Status.SERVICE_UNAVAILABLE, "Request timed out"));
        });

        try {
            Optional<BisqEasyOpenTradeChannel> optionalChannel = bisqEasyOpenTradeChannelService.findChannelByTradeId(tradeId);
            if (optionalChannel.isEmpty()) {
                asyncResponse.resume(buildResponse(Response.Status.NOT_FOUND, "Open trade channel not found for trade with ID " + tradeId));
                return;
            }

            Optional<BisqEasyTradeReadModel> optionalTrade = bisqEasyTradeService.findTrade(tradeId);
            if (optionalTrade.isEmpty()) {
                asyncResponse.resume(buildResponse(Response.Status.NOT_FOUND, "Trade not found for ID " + tradeId));
                return;
            }

            BisqEasyOpenTradeChannel channel = optionalChannel.get();
            BisqEasyTradeReadModel trade = optionalTrade.get();
            String userName = channel.getMyUserIdentity().getUserName();
            BitcoinPaymentRail paymentRail = trade.getContract().getBaseSidePaymentMethodSpec().getPaymentMethod().getPaymentRail();
            CompletableFuture<Void> action = switch (tradeEvent.tradeEventType()) {
                case REJECT_TRADE -> handleRejectTrade(channel, trade, userName);
                case CANCEL_TRADE -> handleCancelTrade(channel, trade, userName);
                case CLOSE_TRADE -> handleCloseTrade(channel, trade);
                case SELLER_SENDS_PAYMENT_ACCOUNT ->
                        handleSellerSendsPaymentAccount(channel, trade, tradeEvent, userName);
                case BUYER_SEND_BITCOIN_PAYMENT_DATA ->
                        handleBuyerSendBitcoinPaymentData(channel, trade, tradeEvent, paymentRail, userName);
                case SELLER_CONFIRM_FIAT_RECEIPT -> handleSellerConfirmFiatReceipt(channel, trade, userName);
                case BUYER_CONFIRM_FIAT_SENT -> handleBuyerConfirmFiatSent(channel, trade, userName);
                case SELLER_CONFIRM_BTC_SENT ->
                        handleSellerConfirmBtcSent(channel, trade, tradeEvent, paymentRail, userName);
                case BTC_CONFIRMED -> handleBtcConfirmed(channel, trade, paymentRail, userName);
            };
            action.whenCompleteAsync((ignored, error) -> {
                if (error == null) {
                    asyncResponse.resume(buildNoContentResponse());
                } else {
                    resumeTradeFailure(asyncResponse, error);
                }
            });
        } catch (Exception error) {
            resumeTradeFailure(asyncResponse, error);
        }
    }

    private CompletableFuture<Void> handleRejectTrade(BisqEasyOpenTradeChannel channel,
                                   BisqEasyTradeReadModel trade,
                                   String userName) {
        return tradeActionService.executeAndAwaitTradeLog(() -> bisqEasyTradeService.rejectTrade(trade), channel, () -> {
            String encoded = Res.encode("bisqEasy.openTrades.tradeLogMessage.rejected", userName);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleCancelTrade(BisqEasyOpenTradeChannel channel,
                                   BisqEasyTradeReadModel trade,
                                   String userName) {
        return tradeActionService.executeAndAwaitTradeLog(() -> bisqEasyTradeService.cancelTrade(trade), channel, () -> {
            String encoded = Res.encode("bisqEasy.openTrades.tradeLogMessage.rejected", userName);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleCloseTrade(BisqEasyOpenTradeChannel channel, BisqEasyTradeReadModel trade) {
        return tradeActionService.closeTrade(bisqEasyTradeService, leavePrivateChatManager, trade, channel);
    }

    private CompletableFuture<Void> handleSellerSendsPaymentAccount(BisqEasyOpenTradeChannel channel,
                                                 BisqEasyTradeReadModel trade,
                                                 TradeEventDto tradeEvent,
                                                 String userName) {
        String paymentAccountData = checkNotNull(tradeEvent.data(), "paymentAccountData must not be null");
        return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.sellerSendsPaymentAccount(trade, paymentAccountData), channel, () -> {
            String encoded = Res.encode("bisqEasy.tradeState.info.seller.phase1.tradeLogMessage", userName, paymentAccountData);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleBuyerSendBitcoinPaymentData(BisqEasyOpenTradeChannel channel,
                                                   BisqEasyTradeReadModel trade,
                                                   TradeEventDto tradeEvent,
                                                   BitcoinPaymentRail paymentRail,
                                                   String userName) {
        String btcRailName = paymentRail.name();
        String key = "bisqEasy.tradeState.info.buyer.phase1a.tradeLogMessage." + btcRailName;
        String bitcoinPaymentData = checkNotNull(tradeEvent.data(), "bitcoinPaymentData must not be null");
        return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.buyerSendBitcoinPaymentData(trade, bitcoinPaymentData), channel, () -> {
            String encoded = Res.encode(key, userName, bitcoinPaymentData);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleSellerConfirmFiatReceipt(BisqEasyOpenTradeChannel channel,
                                                BisqEasyTradeReadModel trade,
                                                String userName) {
        return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.sellerConfirmFiatReceipt(trade), channel, () -> {
            long quoteSideAmount = trade.getContract().getQuoteSideAmount();
            String quoteCurrencyCode = trade.getOffer().getMarket().getQuoteCurrencyCode();
            String formattedQuoteAmount = AmountFormatter.formatQuoteAmountWithCode(Fiat.from(quoteSideAmount, quoteCurrencyCode));
            String encoded = Res.encode("bisqEasy.tradeState.info.seller.phase2b.tradeLogMessage", userName, formattedQuoteAmount);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleBuyerConfirmFiatSent(BisqEasyOpenTradeChannel channel,
                                            BisqEasyTradeReadModel trade,
                                            String userName) {
        return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.buyerConfirmFiatSent(trade), channel, () -> {
            String quoteCurrencyCode = trade.getOffer().getMarket().getQuoteCurrencyCode();
            String encoded = Res.encode("bisqEasy.tradeState.info.buyer.phase2a.tradeLogMessage", userName, quoteCurrencyCode);
            return encoded;
        });
    }

    private CompletableFuture<Void> handleSellerConfirmBtcSent(BisqEasyOpenTradeChannel channel,
                                            BisqEasyTradeReadModel trade,
                                            TradeEventDto tradeEvent,
                                            BitcoinPaymentRail paymentRail,
                                            String userName) {
        Optional<String> paymentProof = Optional.ofNullable(tradeEvent.data());
        boolean isMainChain = paymentRail.equals(BitcoinPaymentRail.MAIN_CHAIN);
        if (isMainChain) {
            checkArgument(paymentProof.isPresent(), "Transaction ID is required for Bitcoin settlement");
        }
        return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.sellerConfirmBtcSent(trade, paymentProof), channel, () -> {
            String encoded;
            if (paymentProof.isEmpty()) {
                encoded = Res.encode("bisqEasy.tradeState.info.seller.phase3a.tradeLogMessage.noProofProvided", userName);
            } else {
                String btcRailName = paymentRail.name();
                String proofType = Res.get("bisqEasy.tradeState.info.seller.phase3a.tradeLogMessage.paymentProof." + btcRailName);
                encoded = Res.encode("bisqEasy.tradeState.info.seller.phase3a.tradeLogMessage", userName, proofType, paymentProof.get());
            }
            return encoded;
        });
    }

    private CompletableFuture<Void> handleBtcConfirmed(BisqEasyOpenTradeChannel channel,
                                    BisqEasyTradeReadModel trade,
                                    BitcoinPaymentRail paymentRail,
                                    String userName) {
        if (paymentRail.equals(BitcoinPaymentRail.LN) && trade.isBuyer()) {
            return tradeActionService.executeAndSendTradeLog(() -> bisqEasyTradeService.btcConfirmed(trade), channel,
                    () -> Res.encode("bisqEasy.tradeState.info.buyer.phase3b.tradeLogMessage.ln", userName));
        }
        return bisqEasyTradeService.btcConfirmed(trade);
    }

    @POST
    @Path("/{tradeId}/mediation")
    @Operation(
            summary = "Request mediation for a trade",
            description = "Request mediation for a specific trade by providing the trade ID. " +
                    "This will initiate the mediation process with the selected mediator for the trade.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Mediation request sent successfully"),
                    @ApiResponse(responseCode = "404", description = "Trade or trade channel not found"),
                    @ApiResponse(responseCode = "400", description = "Invalid request"),
                    @ApiResponse(responseCode = "409", description = "Conflict - No mediator found for this trade"),
                    @ApiResponse(responseCode = "500", description = "Internal server error"),
                    @ApiResponse(responseCode = "503", description = "Service unavailable - Request timed out")
            }
    )
    public void requestMediation(@PathParam("tradeId") String tradeId, @Suspended AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(150, TimeUnit.SECONDS); // We have 120 seconds socket timeout, so we should never get triggered here, as the message will be sent as mailbox message
        asyncResponse.setTimeoutHandler(response -> {
            response.resume(buildResponse(Response.Status.SERVICE_UNAVAILABLE, "Request timed out"));
        });

        try {
            Optional<BisqEasyTradeReadModel> optionalTrade = bisqEasyTradeService.findTrade(tradeId);
            if (optionalTrade.isEmpty()) {
                asyncResponse.resume(buildResponse(Response.Status.NOT_FOUND, "Trade not found for ID " + tradeId));
                return;
            }

            Optional<BisqEasyOpenTradeChannel> optionalChannel = bisqEasyOpenTradeChannelService.findChannelByTradeId(tradeId);
            if (optionalChannel.isEmpty()) {
                asyncResponse.resume(buildResponse(Response.Status.NOT_FOUND, "Open trade channel not found for trade with ID " + tradeId));
                return;
            }

            BisqEasyTradeReadModel trade = optionalTrade.get();
            BisqEasyOpenTradeChannel channel = optionalChannel.get();
            BisqEasyContract contract = trade.getContract();

            bisqEasyMediationRequestService.requestMediation(channel, contract);

            asyncResponse.resume(buildNoContentResponse());
        } catch (NoSuchElementException e) {
            asyncResponse.resume(buildResponse(Response.Status.CONFLICT, "No mediator found for this trade."));
        } catch (IllegalArgumentException e) {
            asyncResponse.resume(buildResponse(Response.Status.BAD_REQUEST, "Invalid request: " + e.getMessage()));
        } catch (Exception e) {
            asyncResponse.resume(buildErrorResponse("An unexpected error occurred: " + e.getMessage()));
        }
    }

}
