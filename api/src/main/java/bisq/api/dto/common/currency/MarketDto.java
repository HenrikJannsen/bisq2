package bisq.api.dto.common.currency;

import bisq.common.currency.Market;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Schema(name = "Market")
public final class MarketDto {
    public static MarketDto from(Market market) {
        return new MarketDto(market.getBaseCurrencyCode(),
                market.getQuoteCurrencyCode(),
                market.getBaseCurrencyName(),
                market.getQuoteCurrencyName()
        );
    }

    private final String baseCurrencyCode;
    private final String quoteCurrencyCode;
    private final String baseCurrencyName;
    private final String quoteCurrencyName;

    public MarketDto(String baseCurrencyCode, String quoteCurrencyCode, String baseCurrencyName, String quoteCurrencyName) {
        this.baseCurrencyCode = baseCurrencyCode;
        this.quoteCurrencyCode = quoteCurrencyCode;
        this.baseCurrencyName = baseCurrencyName;
        this.quoteCurrencyName = quoteCurrencyName;
    }
}


