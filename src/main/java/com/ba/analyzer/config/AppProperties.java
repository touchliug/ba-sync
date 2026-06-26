package com.ba.analyzer.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "binance")
public class AppProperties {

    @NotBlank
    private String baseUrl = "https://fapi.binance.com";
    private ProxyConfig proxy = new ProxyConfig();
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();
    private SymbolConfig symbol = new SymbolConfig();
    private AnalysisConfig analysis = new AnalysisConfig();
    private ReportConfig report = new ReportConfig();
    private InitConfig init = new InitConfig();
    private LsrConfig lsr = new LsrConfig();

    @Data
    public static class InitConfig {
        /** 启动时是否执行数据完整性校验与补齐。 */
        private boolean enabled = true;
        private DailyInit daily = new DailyInit();
    }

    @Data
    public static class DailyInit {
        /** 日线级(日K/日线OI/资金费率)校验窗口天数。 */
        @Min(1)
        private int days = 30;
        /**
         * 容差天数: 实际记录数 >= (days - tolerance) 即视为完整, 不补。
         * 吸收 UTC 日界对齐 / 资金费率结算时点抖动等边界误差, 避免对老币重复全量补拉。
         */
        @Min(0)
        private int toleranceDays = 2;
    }

    @Data
    public static class LsrConfig {
        private boolean enabled = true;
        private String period = "1h";
        private int limit = 30;
    }

    @Data
    public static class ProxyConfig {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        private int port = 7890;
    }

    @Data
    public static class ConcurrencyConfig {
        @Min(1) @Max(50)
        private int maxRequests = 5;
        @Min(0)
        private long requestIntervalMs = 200;
        @Min(1)
        private int historyBufferDays = 30;
        @Min(10)
        private int taskTimeoutSeconds = 120;
        /** X-MBX-USED-WEIGHT-1M 软上限: 已用权重达此值即主动退避(币安/fapi上限2400/min, 留余量默认2000)。 */
        @Min(1)
        private int weightSoftLimit = 2000;
    }

    @Data
    public static class SymbolConfig {
        private String filePath = "./data/symbols.json";
    }

    @Data
    public static class AnalysisConfig {
        private ConsecutiveRiseConfig consecutiveRise = new ConsecutiveRiseConfig();
        private RiseThenDropConfig riseThenDrop = new RiseThenDropConfig();
        private HighRiseConfig highRise = new HighRiseConfig();
        private DropThenRiseConfig dropThenRise = new DropThenRiseConfig();
        private SlowRiseConfig slowRise = new SlowRiseConfig();
        private PriceDropOiRiseConfig priceDropOiRise = new PriceDropOiRiseConfig();
        private LowPriceConsolidationConfig lowPriceConsolidation = new LowPriceConsolidationConfig();
        private BullishAccumulationConfig bullishAccumulation = new BullishAccumulationConfig();
        private FirstYinDayConfig firstYinDay = new FirstYinDayConfig();
        private OiConsecutiveRiseConfig oiConsecutiveRise = new OiConsecutiveRiseConfig();
        private NMinMaxConfig nMinMax = new NMinMaxConfig();
        private NDayLowConfig nDayLow = new NDayLowConfig();
        private AltcoinPumpAlertConfig altcoinPumpAlert = new AltcoinPumpAlertConfig();
        private ReversalLongConfig reversalLong = new ReversalLongConfig();
        private CompositeScoreConfig compositeScore = new CompositeScoreConfig();
    }

    @Data
    public static class ConsecutiveRiseConfig {
        private boolean enabled = true;
        private int days = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class RiseThenDropConfig {
        private boolean enabled = true;
        private int days = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class HighRiseConfig {
        private boolean enabled = true;
        private int days = 7;
        private double thresholdPercent = 50;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class DropThenRiseConfig {
        private boolean enabled = true;
        private int days = 5;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class SlowRiseConfig {
        private boolean enabled = true;
        private int days = 7;
        private double maxDailyChangePercent = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class PriceDropOiRiseConfig {
        private boolean enabled = true;
        private int days = 5;
        private double maxPriceRisePercent = 10;
        private double minOiRisePercent = 20;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class LowPriceConsolidationConfig {
        private boolean enabled = true;
        private int days = 7;
        private double maxPriceChangePercent = 10;
        private double lowPricePercentile = 30;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class BullishAccumulationConfig {
        private boolean enabled = false;
        // 分析窗口(天)
        private int days = 14;
        // 最低总分
        private int minScore = 60;
        // 价格位置: 回溯天数
        private int priceLookbackDays = 30;
        // 价格位置: 最低回撤% (从回溯期高点)
        private double drawdownMin = 10.0;
        // 低点抬高: 后半段最低点需高于前半段最低点
        private boolean requireHigherLow = true;
        // 量能回升: 后半段均量/前半段均量 >= 此值
        private double volumeIncreaseMin = 1.2;
        // 振幅收敛: 后半段振幅/前半段振幅 <= 此值
        private double rangeContractionMax = 0.8;
    }

    @Data
    public static class FirstYinDayConfig {
        private boolean enabled = false;
        private int days = 3;
        private double minTotalRisePercent = 15;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class OiConsecutiveRiseConfig {
        private boolean enabled = false;
        private int days = 5;
        private double maxPriceRisePercent = 7;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class NMinMaxConfig {
        private boolean enabled = false;
        private int days = 7;
    }

    @Data
    public static class NDayLowConfig {
        private boolean enabled = false;
        private int days = 7;
    }

    @Data
    public static class AltcoinPumpAlertConfig {
        private boolean enabled = false;
        private int days = 7;
        private int minScore = 65;
        // 窗口内价格跌幅上限(%), 防止接飞刀. 放宽以捕捉趋势延续型暴涨
        private double maxPriceDeclinePercent = 15.0;
        private int oiConsecutiveDays = 3;
        private double volumeExpansionRatio = 1.5;
        private double lowPricePercentile = 35.0;
        private double wickBodyRatio = 1.5;
        private double silentBuyerRatio = 1.3;
        // 窗口内最大回撤上限(%), 排除仍在暴跌的币
        private double maxDrawdownPercent = 25.0;
    }

    @Data
    public static class ReversalLongConfig {
        private boolean enabled = false;
        // 连跌检测: 最少连续下跌天数
        private int declineMinDays = 4;
        // 累计跌幅范围(%): 绝对值, 下限
        private double declineMinPct = 5.0;
        // 累计跌幅范围(%): 绝对值, 上限
        private double declineMaxPct = 12.0;
        // 反转日涨幅下限(%)
        private double reversalMinPct = 1.0;
        // 反转日涨幅上限(%)
        private double reversalMaxPct = 4.0;
        // 止盈点位(%)
        private double takeProfitPct = 6.0;
        // 止损点位(%)
        private double stopLossPct = 4.0;
        // 反转日量能确认: 反转日成交量/跌势均量 >= 此值才有效 (1.0=不启用)
        private double volumeConfirmRatio = 1.0;
        // 反转质量评分最低分(0-100): 低于此分的信号过滤掉 (0=不过滤,仅排序)。
        // 回测(2739笔)显示: >=55胜率55%, >=65胜率60%, >=75胜率62%。生产默认55。
        private int qualityMinScore = 55;
    }

    @Data
    public static class CompositeScoreConfig {
        private boolean enabled = true;
        // 最低综合分(0-100): 低于此分不输出
        private int minScore = 50;
        // 各维度权重 (你有OI/价格/费率/量能4维, 社媒/多交易所暂为扩展位=0)
        private int oiWeight = 25;
        private int priceWeight = 20;
        private int fundingWeight = 15;
        private int volumeWeight = 15;
        private int socialWeight = 0;   // 预留, 无数据源
        private int venueWeight = 0;    // 预留, 无数据源
        // 价格"早期"窗口上限(%): 24h涨幅超此值价格分递减(防追高)
        private double earlyPriceMaxPct = 15.0;
    }

    @Data
    public static class ReportConfig {
        private String dir = "./reports";
    }
}
