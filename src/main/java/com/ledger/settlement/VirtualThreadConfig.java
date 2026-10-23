package com.ledger.settlement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The application's task executor: one new virtual thread per task, no pool to size.
 * Defining an Executor bean makes Spring Boot back off its own auto-configured executor, so this
 * replaces it (it is the executor used for @Async methods and async MVC request handling).
 * HTTP requests themselves run on virtual threads via spring.threads.virtual.enabled.
 */
@Configuration(proxyBeanMethods = false)
class VirtualThreadConfig {

    @Bean(destroyMethod = "close")
    ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "applicationTaskExecutor")
    AsyncTaskExecutor applicationTaskExecutor(ExecutorService virtualThreadExecutor) {
        return new TaskExecutorAdapter(virtualThreadExecutor);
    }
}
