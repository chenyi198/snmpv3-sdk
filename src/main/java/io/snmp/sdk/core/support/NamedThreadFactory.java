package io.snmp.sdk.core.support;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named thread ThreadFactory.
 *
 * @author ssp
 * @since 1.0
 */
public class NamedThreadFactory implements ThreadFactory {

    private final AtomicInteger threadId = new AtomicInteger(1);

    private final String prefix;

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, prefix + "-" + threadId.getAndIncrement());
    }
}
