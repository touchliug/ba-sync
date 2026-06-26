package com.ba.analyzer.client;

public enum LsrType {
    GLOBAL_ACCOUNT("/futures/data/globalLongShortAccountRatio", false),
    TOP_ACCOUNT("/futures/data/topLongShortAccountRatio", false),
    TOP_POSITION("/futures/data/topLongShortPositionRatio", false),
    TAKER("/futures/data/takerlongshortRatio", true);

    private final String path;
    private final boolean taker;

    LsrType(String path, boolean taker) { this.path = path; this.taker = taker; }
    public String getPath() { return path; }
    public boolean isTaker() { return taker; }
    public String dbValue() { return name(); }
}
