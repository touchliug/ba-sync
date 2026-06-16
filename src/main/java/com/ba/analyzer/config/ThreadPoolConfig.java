package com.ba.analyzer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "httpExecutor", destroyMethod = "shutdown")
    public ExecutorService httpExecutor(AppProperties props) {
        int maxRequests = props.getConcurrency().getMaxRequests();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                maxRequests, maxRequests, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                namedThreadFactory("http-pool"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
