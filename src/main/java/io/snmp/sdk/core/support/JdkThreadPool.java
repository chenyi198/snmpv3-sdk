package io.snmp.sdk.core.support;

import lombok.Data;
import org.snmp4j.util.WorkerPool;
import org.snmp4j.util.WorkerTask;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Use JDK's {@link ThreadPoolExecutor} implement {@link WorkerPool}.
 *
 * @author ssp
 * @since 1.0
 */
public class JdkThreadPool implements WorkerPool {

    private final ThreadPoolExecutor executor;

    public JdkThreadPool(String poolName, int corePoolSize,
                         int maximumPoolSize,
                         long keepAliveTime,
                         TimeUnit unit,
                         int queueCapacity) {

        executor = new ThreadPoolExecutor(
                corePoolSize, maximumPoolSize,
                keepAliveTime, unit,
                createQueue(queueCapacity),
                new NamedThreadFactory(poolName),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public JdkThreadPool(WorkerPoolConfig poolConfig) {
        this(poolConfig.poolName,
                poolConfig.coreSize, poolConfig.maxSize,
                poolConfig.keepAlive, poolConfig.unit,
                poolConfig.queueCapacity
        );
    }

    @Override
    public void execute(WorkerTask task) {
        executor.execute(task);
    }

    @Override
    public boolean tryToExecute(WorkerTask task) {
        executor.execute(task);
        return true;
    }

    @Override
    public void stop() {
        executor.shutdown();
    }

    @Override
    public void cancel() {
        throw new UnsupportedOperationException("JdkThreadPool#cancel un-support!");
    }

    @Override
    public boolean isIdle() {
        return true;
    }

    protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
        if (queueCapacity > 0) {
            return new LinkedBlockingQueue<>(queueCapacity);
        } else {
            return new SynchronousQueue<>();
        }
    }

    @Data
    public static class WorkerPoolConfig {

        /**
         * 线程池名称.
         */
        private String poolName;
        /**
         * 核心线程数量.
         */
        private int coreSize;
        /**
         * 最大线程数量.
         */
        private int maxSize;
        private long keepAlive;
        private Duration keepAliveDuration;
        private TimeUnit unit;
        private int queueCapacity;


        public WorkerPoolConfig() {
        }

        public WorkerPoolConfig(String poolName,
                                int coreSize, int maxSize,
                                Duration keepAliveDuration,
                                int queueCapacity) {
            this.poolName = poolName;
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.keepAliveDuration = keepAliveDuration;
            this.keepAlive = keepAliveDuration.getSeconds();
            this.unit = TimeUnit.SECONDS;
            this.queueCapacity = queueCapacity;
        }

    }

}
