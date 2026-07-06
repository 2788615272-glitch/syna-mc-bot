package com.syna.bridge.mobility.path;

public final class CostModel {
    public int walkCost() {
        return 10;
    }

    public int jumpCost() {
        return 18;
    }

    public int digCost() {
        return 80;
    }

    public int placeSupportCost() {
        return 120;
    }
}