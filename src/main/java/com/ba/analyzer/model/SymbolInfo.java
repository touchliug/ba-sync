package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 币安合约交易对信息模型
 * 对应币安/fapi/v1/exchangeInfo接口返回的symbols数组中的每个元素
 * 包含交易对名称、合约类型、报价资产、价格精度等信息
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SymbolInfo {

    private String symbol;

    @JsonProperty("pair")
    private String pair;

    @JsonProperty("contractType")
    private String contractType;

    @JsonProperty("deliveryDate")
    private long deliveryDate;

    @JsonProperty("onboardDate")
    private long onboardDate;

    @JsonProperty("status")
    private String status;

    @JsonProperty("baseAsset")
    private String baseAsset;

    @JsonProperty("quoteAsset")
    private String quoteAsset;

    @JsonProperty("pricePrecision")
    private int pricePrecision;

    @JsonProperty("quantityPrecision")
    private int quantityPrecision;
}
