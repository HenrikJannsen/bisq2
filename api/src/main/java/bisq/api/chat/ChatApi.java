package bisq.api.chat;


import bisq.api.Api;
import bisq.chat.ChatChannelDomain;
import bisq.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Path("/chat")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Chat API")
public class ChatApi extends Api {

    private final ChatService chatService;

    public ChatApi(@Context Application application) {
        chatService = getCoreServiceProvider().getChatService();
    }

    @GET
    @Path("/public-discussion-channels")
    @Operation(description = "Get a list of all publicly available Discussion Channels.")
    @ApiResponse(responseCode = "200", description = "request successful.",
            content = {
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = bisq.api.chat.CommonPublicChatChannelDto.class)
                    )}
    )
    public List<bisq.api.chat.CommonPublicChatChannelDto> getPublicDiscussionChannels() {
        return chatService.getCommonPublicChatChannelServices().get(ChatChannelDomain.DISCUSSION).getChannels().stream()
                .map(chatChannel -> bisq.api.chat.CommonPublicChatChannelDto.from(chatService, chatChannel))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/public-trade-channels")
    @Operation(description = "Get a list of all publicly available Trade Channels.")
    @ApiResponse(responseCode = "200", description = "request successful.",
            content = {
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = bisq.api.chat.BisqEasyPublicChatChannelDto.class)
                    )}
    )
    public List<bisq.api.chat.BisqEasyPublicChatChannelDto> getPublicTradeChannels() {
        return chatService.getBisqEasyOfferbookChannelService().getChannels().stream()
                .map(chatChannel -> bisq.api.chat.BisqEasyPublicChatChannelDto.from(chatService, chatChannel))
                .collect(Collectors.toList());
    }
}
