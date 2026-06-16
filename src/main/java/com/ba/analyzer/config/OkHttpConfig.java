package com.ba.analyzer.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp客户端配置类
 * 根据配置决定是否启用HTTP代理，设置连接/读取/写入超时时间
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient(AppProperties appProperties) {
        int maxRequests = appProperties.getConcurrency().getMaxRequests();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(60))
                .connectionPool(new ConnectionPool(maxRequests, 5, TimeUnit.MINUTES));

        if (appProperties.getProxy().isEnabled()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(
                            appProperties.getProxy().getHost(),
                            appProperties.getProxy().getPort()));
            builder.proxy(proxy);
        }

        return builder.build();
    }
}
