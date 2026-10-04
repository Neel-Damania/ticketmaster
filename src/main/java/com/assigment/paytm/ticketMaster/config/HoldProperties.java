package com.assigment.paytm.ticketMaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hold")
public class HoldProperties {
    private long ttlSeconds = 300;
    private long sweepIntervalMs = 5000;
    private int sweepBatch = 1000;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    public void setSweepIntervalMs(long sweepIntervalMs) {
        this.sweepIntervalMs = sweepIntervalMs;
    }

    public int getSweepBatch() {
        return sweepBatch;
    }

    public void setSweepBatch(int sweepBatch) {
        this.sweepBatch = sweepBatch;
    }
}
