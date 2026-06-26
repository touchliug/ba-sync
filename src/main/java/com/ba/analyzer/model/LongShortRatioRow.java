package com.ba.analyzer.model;

public record LongShortRatioRow(String symbol, String ratioType, String period, long timestamp,
                                String ratio, String longValue, String shortValue) {}
