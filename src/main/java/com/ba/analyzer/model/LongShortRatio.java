package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LongShortRatio {
    private String symbol;
    // 账户/持仓类
    private String longShortRatio;
    private String longAccount;
    private String shortAccount;
    // taker 类
    private String buySellRatio;
    private String buyVol;
    private String sellVol;
    private long timestamp;
}
