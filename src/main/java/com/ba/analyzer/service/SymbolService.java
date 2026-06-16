package com.ba.analyzer.service;

import com.ba.analyzer.client.BinanceClient;
import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.SymbolInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 合约信息服务
 * 负责从币安API获取USDT永续合约列表并保存到本地JSON文件
 * 支持从文件加载合约列表（文件不存在时自动从API获取）
 */
@Slf4j
@Service
public class SymbolService {

    private final BinanceClient binanceClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public SymbolService(BinanceClient binanceClient, AppProperties appProperties, ObjectMapper objectMapper) {
        this.binanceClient = binanceClient;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public List<SymbolInfo> fetchAndSaveSymbols() {
        log.info("Fetching USDT futures symbols from Binance...");
        List<SymbolInfo> symbols = binanceClient.getUsdtFuturesSymbols();
        log.info("Fetched {} USDT perpetual futures symbols", symbols.size());

        saveSymbolsToFile(symbols);
        return symbols;
    }

    public List<SymbolInfo> loadSymbolsFromFile() {
        String filePath = appProperties.getSymbol().getFilePath();
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Symbol file not found: {}, fetching from API", filePath);
            return fetchAndSaveSymbols();
        }
        try {
            return objectMapper.readValue(file,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SymbolInfo.class));
        } catch (IOException e) {
            log.error("Failed to read symbol file: {}", filePath, e);
            return Collections.emptyList();
        }
    }

    public List<String> getSymbolNames() {
        return loadSymbolsFromFile().stream()
                .map(SymbolInfo::getSymbol)
                .toList();
    }

    private void saveSymbolsToFile(List<SymbolInfo> symbols) {
        String filePath = appProperties.getSymbol().getFilePath();
        try {
            Path parentDir = Path.of(filePath).getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(filePath), symbols);
            log.info("Saved {} symbols to file: {}", symbols.size(), filePath);
        } catch (IOException e) {
            log.error("Failed to save symbols to file: {}", filePath, e);
        }
    }
}
