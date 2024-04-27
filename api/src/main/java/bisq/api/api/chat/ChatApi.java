package bisq.api.api.chat;

import bisq.api.api.Api;
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
                            schema = @Schema(implementation = CommonPublicChatChannelDto.class)
                    )}
    )
    public List<CommonPublicChatChannelDto> getPublicDiscussionChannels() {
        return chatService.getCommonPublicChatChannelServices().get(ChatChannelDomain.DISCUSSION).getChannels().stream()
                .map(chatChannel -> CommonPublicChatChannelDto.from(chatService, chatChannel))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/public-trade-channels")
    @Operation(description = "Get a list of all publicly available Trade Channels.")
    @ApiResponse(responseCode = "200", description = "request successful.",
            content = {
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = BisqEasyPublicChatChannelDto.class)
                    )}
    )
    public List<BisqEasyPublicChatChannelDto> getPublicTradeChannels() {
        return chatService.getBisqEasyOfferbookChannelService().getChannels().stream()
                .map(chatChannel -> BisqEasyPublicChatChannelDto.from(chatService, chatChannel))
                .collect(Collectors.toList());
    }
}
