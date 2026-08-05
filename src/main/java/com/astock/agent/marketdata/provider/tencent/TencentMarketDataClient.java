package com.astock.agent.marketdata.provider.tencent;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import com.astock.agent.marketdata.provider.ProviderResponse;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.List;

/**
 * 腾讯核心行情和日 K 线适配器。
 *
 * <p>腾讯是核心行情优先来源。适配器负责编码、请求和响应解析，身份、OHLC、日期顺序等
 * 通用质量检查由 validation 层完成，避免每个 Provider 重复定义业务规则。</p>
 */
public final class TencentMarketDataClient {

    private static final Charset GBK = Charset.forName("GBK");
    private final ProviderHttpClient http;
    private final TencentResponseParser parser;
    private final Clock clock;

    public TencentMarketDataClient(ProviderHttpClient http, TencentResponseParser parser, Clock clock) {
        this.http = http;
        this.parser = parser;
        this.clock = clock;
    }

    public SourcedPayload<Quote> fetchQuote(SecurityId security) {
        // Quote 的 security 必须与请求代码一致，解析器会拒绝跨股票响应。
        URI uri = URI.create("https://qt.gtimg.cn/q=" + security.tencentCode());
        ProviderResponse response = http.get(ProviderId.TENCENT, uri, "https://gu.qq.com/");
        Quote quote = parser.parseQuote(response.text(GBK), security);
        return new SourcedPayload<>(quote, provenance(uri, quote.quotedAt()));
    }

    public SourcedPayload<List<DailyBar>> fetchDailyBars(SecurityId security, int count) {
        int boundedCount = Math.max(260, Math.min(count, 800));
        URI uri = URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + security.tencentCode() + ",day,,," + boundedCount + ",qfq");
        ProviderResponse response = http.get(ProviderId.TENCENT, uri, "https://gu.qq.com/");
        List<DailyBar> bars = parser.parseDailyBars(response.utf8Text(), security);
        return new SourcedPayload<>(bars, provenance(uri, null));
    }

    private Provenance provenance(URI uri, java.time.Instant providerTime) {
        return new Provenance(ProviderId.TENCENT.displayName(), uri, providerTime, clock.instant(), false, null);
    }

    public record SourcedPayload<T>(T payload, Provenance provenance) {
    }
}
