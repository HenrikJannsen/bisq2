package bisq.api.dto.chat;

import bisq.api.dto.common.currency.MarketDto;
import bisq.chat.ChatService;
import bisq.chat.bisqeasy.offerbook.BisqEasyOfferbookChannel;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@ToString
@Schema(name = "PublicTradeChannel")
public final class BisqEasyPublicChatChannelDto {
    @EqualsAndHashCode.Include
    private String channelId;
    private String description;
    private String channelTitle;

    @JsonProperty("market")
    private MarketDto marketDto;

    public static BisqEasyPublicChatChannelDto from(ChatService chatService, BisqEasyOfferbookChannel chatChannel) {
        BisqEasyPublicChatChannelDto dto = new BisqEasyPublicChatChannelDto();
        dto.channelId = chatChannel.getId();
        dto.description = chatChannel.getDescription();
        dto.channelTitle = chatService.findChatChannelService(chatChannel)
                .map(service -> service.getChannelTitle(chatChannel))
                .orElse("");
        dto.marketDto = MarketDto.from(chatChannel.getMarket());
        return dto;
    }
}
