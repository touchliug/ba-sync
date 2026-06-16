package com.ba.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K线(蜡烛图)数据模型
 * 对应币安/fapi/v1/klines接口返回的每条K线数据
 * 包含开盘时间、开高低收价格、成交量等信息，并提供涨跌幅计算方法
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlineData {

    private long openTime;
    private String open;
    private String high;
    private String low;
    private String close;
    private String volume;
    private long closeTime;
    private String quoteAssetVolume;
    private int numberOfTrades;
    private String takerBuyBaseAssetVolume;
    private String takerBuyQuoteAssetVolume;

    private transient volatile boolean parsed = false;
    private transient double cachedOpenPrice;
    private transient double cachedClosePrice;
    private transient double cachedHighPrice;
    private transient double cachedLowPrice;
    private transient double cachedVolumeValue;
    private transient double cachedQuoteVolume;

    private void initParsed() {
        if (parsed) return;
        synchronized (this) {
            if (parsed) return;
            cachedOpenPrice = parseDouble(open);
            cachedClosePrice = parseDouble(close);
            cachedHighPrice = parseDouble(high);
            cachedLowPrice = parseDouble(low);
            cachedVolumeValue = parseDouble(volume);
            cachedQuoteVolume = parseDouble(quoteAssetVolume);
            parsed = true;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public double getOpenPrice() {
        initParsed();
        return cachedOpenPrice;
    }

    public double getClosePrice() {
        initParsed();
        return cachedClosePrice;
    }

    public double getHighPrice() {
        initParsed();
        return cachedHighPrice;
    }

    public double getLowPrice() {
        initParsed();
        return cachedLowPrice;
    }

    public double getVolumeValue() {
        initParsed();
        return cachedVolumeValue;
    }

    public double getQuoteVolume() {
        initParsed();
        return cachedQuoteVolume;
    }

    public double getChangePercent() {
        double op = getOpenPrice();
        if (op == 0) return 0;
        return ((getClosePrice() - op) / op) * 100;
    }
}
