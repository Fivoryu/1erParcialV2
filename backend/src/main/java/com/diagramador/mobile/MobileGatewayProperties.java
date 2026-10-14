package com.diagramador.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Component;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Operator-owned upstream boundary; it is off until generated deployments are network-isolated. */
@Component
@Validated
@ConfigurationProperties("diagrammer.mobile-gateway")
public class MobileGatewayProperties {
    private boolean enabled;
    private String upstreamHost = "127.0.0.1";
    @Min(1) @Max(60_000) private int connectTimeoutMillis = 2_000;
    @Min(1) @Max(60_000) private int requestTimeoutMillis = 5_000;
    @Min(1) @Max(1_048_576) private int maxResponseBytes = 1_048_576;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUpstreamHost() { return upstreamHost; }
    public void setUpstreamHost(String upstreamHost) { this.upstreamHost = upstreamHost; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int value) { this.connectTimeoutMillis = value; }
    public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(int value) { this.requestTimeoutMillis = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
}
