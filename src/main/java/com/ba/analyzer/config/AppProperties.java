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
    private InitConfig init = new InitConfig();
    private LsrConfig lsr = new LsrConfig();
    private RetentionConfig retention = new RetentionConfig();

    /**
     * 下架币定时清理。
     *
     * <p>三道安全阀：宽限期（防状态误判）、单次上限（防批量误删）、先归档再删（币安下架后数据拉不回来）。
     */
    @Data
    public static class RetentionConfig {
        /** 总开关。 */
        private boolean enabled = true;
        /** 默认为凌晨 4:30，避开 06:00 合约列表更新与 08:10 日线 OI 同步。 */
        private String cron = "0 30 4 * * ?";
        /** 下架满多少天才允许清理；给状态误判留出人工发现与回滚的窗口。 */
        @Min(1)
        private int delistedGraceDays = 7;
        /** 单次最多清理几个币，超过则整体中止（防止某次 exchangeInfo 异常导致大批误标后清库）。 */
        @Min(1)
        private int maxSymbolsPerRun = 20;
        /** 归档目录（相对 start.sh 所在目录）。 */
        private String backupDir = "./archive";
        /** 归档保留天数，超期自动删除。 */
        @Min(1)
        private int backupKeepDays = 90;
        /** 是否连 symbols 行一起删除；false 则只清历史数据、保留元信息。 */
        private boolean deleteSymbolsRow = true;
    }

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
}
