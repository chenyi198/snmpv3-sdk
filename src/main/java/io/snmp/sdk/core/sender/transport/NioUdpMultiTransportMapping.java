package io.snmp.sdk.core.sender.transport;

import lombok.extern.slf4j.Slf4j;
import org.snmp4j.TransportStateReference;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 多路复用通信模型实现——n*多路复用器.
 *
 * <p>覆写{@link DefaultUdpTransportMapping}核心方法，转用多路复用NIO模型.</p>
 * <p>n个多路复用器轮询多个udp-channel：将多个channel均衡地注册到n个多路复用器上.</p>
 *
 * @author ssp
 * @since 1.0
 */
@Slf4j
public class NioUdpMultiTransportMapping extends DefaultUdpTransportMapping {

    private NioListenThreadGroup listenerThreadGroup;

    private final int multi;
    private String nioPoolName = "nio-snmp-multi-transport";

    private final InetSocketAddress localBindAddress;

    /**
     * remote-channel.
     */
    private Map<UdpAddress, DatagramChannel> channelsTable = new ConcurrentHashMap<>();

    public NioUdpMultiTransportMapping() throws IOException {
        this.multi = 0;
        localBindAddress = new InetSocketAddress(0);
    }

    public NioUdpMultiTransportMapping(int multi) throws IOException {
        this.multi = multi;
        localBindAddress = new InetSocketAddress(0);
    }

    public NioUdpMultiTransportMapping(UdpAddress udpAddress, boolean reuseAddress, int multi) throws IOException {
        super(udpAddress, reuseAddress);
        localBindAddress = new InetSocketAddress(udpAddress.getInetAddress().getHostAddress(), 0);
        this.multi = multi;
    }

    public NioUdpMultiTransportMapping(int multi, UdpAddress udpAddress) throws IOException {
        super(udpAddress);
        this.multi = multi;
        localBindAddress = new InetSocketAddress(udpAddress.getInetAddress().getHostAddress(), 0);
    }

    @Override
    public synchronized void listen() throws IOException {
        if (listener != null) {
            throw new SocketException("Port already listening");
        }
        listenerThreadGroup = new NioListenThreadGroup(multi == 0 ? Runtime.getRuntime().availableProcessors() * 2 : multi, nioPoolName);
    }

    @Override
    public void sendMessage(UdpAddress targetAddress, byte[] message, TransportStateReference tmStateReference) throws IOException {
        InetSocketAddress targetSocketAddress =
                new InetSocketAddress(targetAddress.getInetAddress(),
                        targetAddress.getPort());
        //TODO 异步化，提交写任务到IO线程
        DatagramChannel s = ensureSocket(targetAddress);
        s.send(ByteBuffer.wrap(message), targetSocketAddress);
    }

    private DatagramChannel ensureSocket(UdpAddress targetAddress) {
        return channelsTable.computeIfAbsent(targetAddress, channelBuilder);
    }

    final Function<UdpAddress, DatagramChannel> channelBuilder = targetAddress -> {
        try {
            final DatagramChannel datagramChannel = DatagramChannel.open();

            //设置非阻塞模式
            datagramChannel.configureBlocking(false);
            datagramChannel.socket().setReuseAddress(true);
            datagramChannel.bind(NioUdpMultiTransportMapping.this.localBindAddress);

            //TODO socket读写缓冲区设置

            datagramChannel.connect(new InetSocketAddress(
                    targetAddress.getInetAddress().getHostAddress(),
                    targetAddress.getPort()));

            //注册到selector线程.
            listenerThreadGroup.register(datagramChannel);

            return datagramChannel;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    };

    class NioListenThread extends Thread {

        private final Selector selector;

        private volatile boolean running = true;

        /**
         * registerTasks.
         * <p>
         * Protected by putLock.
         */
        private List<Runnable> registerTasks = new LinkedList<>();

        private final ReentrantLock putLock = new ReentrantLock();


        public NioListenThread(String threadName) throws IOException {
            super(threadName);
            selector = Selector.open();
        }

        @Override
        public void run() {

            do {
                try {
                    selector.select();
                    final Set<SelectionKey> keys = selector.selectedKeys();

                    Iterator<SelectionKey> itr = keys.iterator();
                    while (itr.hasNext()) {
                        try {
                            SelectionKey fd = itr.next();
                            itr.remove();

                            if (fd.isValid()) {
                                if (fd.isReadable()) {
                                    DatagramChannel sc = (DatagramChannel) fd.channel();
                                    final DatagramSocket socket = sc.socket();

                                    //TODO 读缓冲区大小设定.
                                    ByteBuffer bis = ByteBuffer.allocate(getMaxInboundMessageSize());
                                    sc.read(bis);
                                    bis.flip();

                                    final InetSocketAddress localAddress = (InetSocketAddress) (sc.getLocalAddress());
                                    TransportStateReference stateReference =
                                            new TransportStateReference(NioUdpMultiTransportMapping.this, new UdpAddress(localAddress.getAddress(), localAddress.getPort()), null,
                                                    SecurityLevel.undefined, SecurityLevel.undefined,
                                                    false, socket);
                                    fireProcessMessage(new UdpAddress(socket.getInetAddress(),
                                            socket.getPort()), bis, stateReference);
                                }
                            } else {
                                fd.cancel();
                            }
                        } catch (IOException e) {
                            log.warn("Do channel io exception: {}", e.getMessage());
                        }
                    }

                    //do all register task once
                    if (putLock.tryLock()) {
                        try {
                            registerTasks.forEach(Runnable::run);
                            registerTasks.clear();
                        } finally {
                            putLock.unlock();
                        }
                    }

                } catch (Exception e) {
                    log.error("Do select or io exception!", e);
                }

            } while (running);

            try {
                selector.close();
            } catch (IOException e) {
                //ignore
            }
            log.info("shutdown [{}] success.", getName());
        }

        private void notifySelector() {
            selector.wakeup();
        }

        public void shutdown() {
            running = false;
        }

        public void register(DatagramChannel datagramChannel) {

            try {
                putLock.lock();
                registerTasks.add(() -> {
                    try {
                        datagramChannel.register(selector, SelectionKey.OP_READ); //注册channel到多路复用器上，设置关注事件:read
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                notifySelector();
            } finally {
                putLock.unlock();
            }
        }

    }

    class NioListenThreadGroup {

        private final NioListenThread[] threadPool;

        private final int multi;

        private final String poolName;

        public NioListenThreadGroup(int multi, String poolName) throws IOException {
            this.multi = multi;
            this.threadPool = new NioListenThread[multi];
            this.poolName = poolName;
            createPool();
        }

        private void createPool() throws IOException {
            for (int i = 0; i < multi; i++) {
                final NioListenThread thread = new NioListenThread(generateTName(i));
                threadPool[i] = thread;
                thread.start();
            }
        }

        private String generateTName(int i) {
            return poolName + "-" + i;
        }

        public void register(DatagramChannel datagramChannel) {
            final NioListenThread nextThread = next();
            nextThread.register(datagramChannel);
        }

        /*均衡注册channel*/
        private final AtomicInteger nextIdx = new AtomicInteger();

        private NioListenThread next() {
            return threadPool[Math.abs(nextIdx.getAndIncrement() % multi)];
        }

        public void shutdownGracefully() {
            for (NioListenThread nioListenThread : threadPool) {
                nioListenThread.shutdown();
            }
        }

    }

}
