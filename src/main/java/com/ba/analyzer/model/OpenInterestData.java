package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 持仓量数据模型
 * 对应币安/fapi/v1/openInterest接口返回的数据
 * 包含交易对名称、当前持仓量、时间戳
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenInterestData {

    private String symbol;
    private String openInterest;
    private long time;
    private String sumOpenInterest;
    private String sumOpenInterestValue;
    private long timestamp;

    public double getOpenInterestValue() {
        if (openInterest != null && !openInterest.isEmpty()) {
            return Double.parseDouble(openInterest);
        }
        if (sumOpenInterest != null && !sumOpenInterest.isEmpty()) {
            return Double.parseDouble(sumOpenInterest);
        }
        return 0;
    }

    public double getOpenInterestNotionalValue() {
        if (sumOpenInterestValue != null && !sumOpenInterestValue.isEmpty()) {
            return Double.parseDouble(sumOpenInterestValue);
        }
        return 0;
    }
}
