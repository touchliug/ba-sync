package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 下架币定时清理。
 *
 * <p>三道安全阀：
 * <ol>
 *   <li><b>宽限期</b>：只清理 delisted_at 早于 now-N 天的币，给状态误判留出人工发现窗口；</li>
 *   <li><b>单次上限</b>：候选数超过 maxSymbolsPerRun 直接中止，避免某次 exchangeInfo 异常导致批量误标后清库；</li>
 *   <li><b>先归档再删</b>：删除前把数据导出为 gz 的 INSERT 语句 —— 币安下架后数据再也拉不回来。</li>
 * </ol>
 *
 * <p>删除粒度为「一个币一个事务」，避免大表上的长事务持锁与 undo 膨胀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private static final long DAY_MS = 86_400_000L;
    /** 归档时按此顺序导出，symbols 放最后（方便手工恢复时先建币再灌数据）。 */
    private static final String[] DUMP_TABLES = {
            "klines", "open_interest", "long_short_ratio", "funding_rate", "ticker_24h", "premium_index", "symbols"
    };

    private final JdbcDataStore dataStore;
    private final AppProperties appProperties;
    private final TransactionTemplate tx;

    public void purgeDelisted() {
        AppProperties.RetentionConfig cfg = appProperties.getRetention();
        if (!cfg.isEnabled()) {
            log.debug("Retention disabled, skip");
            return;
        }

        long cutoff = System.currentTimeMillis() - (long) cfg.getDelistedGraceDays() * DAY_MS;
        int candidates = dataStore.countPurgeCandidates(cutoff);
        if (candidates == 0) {
            log.debug("No delisted symbol past grace period ({} days)", cfg.getDelistedGraceDays());
            return;
        }

        // 安全阀 2：单次上限
        if (shouldAbort(candidates, cfg.getMaxSymbolsPerRun())) {
            log.error("Purge ABORTED: {} candidates exceed cap {}. "
                    + "This usually means symbol sync mislabeled a batch as DELISTED — investigate before raising the cap.",
                    candidates, cfg.getMaxSymbolsPerRun());
            return;
        }

        List<String> symbols = dataStore.listPurgeCandidates(cutoff, cfg.getMaxSymbolsPerRun());
        log.info("Purging {} delisted symbols (grace {}d, deleteSymbolsRow={}): {}",
                symbols.size(), cfg.getDelistedGraceDays(), cfg.isDeleteSymbolsRow(), symbols);

        // 安全阀 3：先归档
        String backup = writeBackup(cfg, symbols);

        long totalRows = 0;
        int ok = 0;
        for (String symbol : symbols) {
            try {
                Long n = tx.execute(status -> dataStore.deleteSymbolData(symbol, cfg.isDeleteSymbolsRow()));
                long rows = n == null ? 0 : n;
                dataStore.insertPurgeLog(symbol, rows, backup);
                totalRows += rows;
                ok++;
                log.info("  purged {}: {} rows", symbol, rows);
            } catch (Exception e) {
                // 单币失败不影响其他币；下次运行会重试（幂等）
                log.error("  purge {} failed, will retry next run", symbol, e);
            }
        }

        cleanOldBackups(cfg);
        log.info("Purge done: {}/{} symbols, {} rows deleted, backup={}", ok, symbols.size(), totalRows, backup);
    }

    /** 单次上限判断（抽成静态方法便于单测）。 */
    static boolean shouldAbort(int candidates, int maxPerRun) {
        return candidates > maxPerRun;
    }

    /** 导出为 gz 的 INSERT 语句，返回文件路径；失败返回 null（不阻断删除，但会记 error）。 */
    private String writeBackup(AppProperties.RetentionConfig cfg, List<String> symbols) {
        File dir = new File(cfg.getBackupDir());
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Cannot create backup dir {}, continue without backup", dir.getAbsolutePath());
            return null;
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File out = new File(dir, "purged-" + ts + ".sql.gz");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8))) {
            w.write("-- 下架币归档 " + ts + "\n-- 币种: " + String.join(",", symbols) + "\n\n");
            for (String symbol : symbols) {
                for (String table : DUMP_TABLES) {
                    dumpTable(w, table, symbol);
                }
            }
            log.info("Backup written: {} ({} KB)", out.getPath(), out.length() / 1024);
            return out.getPath();
        } catch (Exception e) {
            log.error("Backup failed, purge will still proceed", e);
            return null;
        }
    }

    private void dumpTable(Writer w, String table, String symbol) throws Exception {
        List<Map<String, Object>> rows = dataStore.rawRows(table, symbol);
        if (rows.isEmpty()) return;
        StringBuilder head = null;
        for (Map<String, Object> row : rows) {
            if (head == null) {
                head = new StringBuilder("INSERT INTO " + table + " (");
                boolean first = true;
                for (String col : row.keySet()) {
                    if (!first) head.append(",");
                    head.append("`").append(col).append("`");
                    first = false;
                }
                head.append(") VALUES ");
            }
            w.write(head.toString());
            w.write('(');
            boolean first = true;
            for (Object v : row.values()) {
                if (!first) w.write(',');
                if (v == null) w.write("NULL");
                else w.write('\'' + String.valueOf(v).replace("\\", "\\\\").replace("'", "\\'")
                        .replace("\n", "\\n").replace("\r", "\\r") + '\'');
                first = false;
            }
            w.write(");\n");
        }
    }

    /** 清理超过保留期的归档文件。 */
    private void cleanOldBackups(AppProperties.RetentionConfig cfg) {
        File dir = new File(cfg.getBackupDir());
        File[] files = dir.listFiles((d, name) -> name.startsWith("purged-") && name.endsWith(".sql.gz"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (long) cfg.getBackupKeepDays() * DAY_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff && f.delete()) {
                log.info("Removed expired backup {}", f.getName());
            }
        }
    }
}
