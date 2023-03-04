package io.snmp.sdk.core.support;

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

/**
 * 多路复用通信模型实现——n*多路复用器.
 *
 * <p>覆写{@link DefaultUdpTransportMapping}核心方法，转用多路复用NIO模型.</p>
 * <p>n个多路复用器轮询多个udp-channel：将多个channel均衡地注册到n个多路复用器上.</p>
 *
 * @author ssp
 * @since 1.0
 */
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

        return channelsTable.computeIfAbsent(targetAddress, udpAddress -> {
            try {
                final DatagramChannel datagramChannel = DatagramChannel.open();

                //设置非阻塞模式
                datagramChannel.configureBlocking(false);
                datagramChannel.socket().setReuseAddress(true);
                datagramChannel.bind(localBindAddress);

                //TODO socket读写缓冲区设置

                datagramChannel.connect(new InetSocketAddress(
                        udpAddress.getInetAddress().getHostAddress(),
                        udpAddress.getPort()));

                listenerThreadGroup.register(datagramChannel);

                return datagramChannel;
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    class NioListenThread extends Thread {

        private final Selector selector;

        private volatile boolean running = true;

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
                        SelectionKey fd = itr.next();
                        itr.remove();

                        if (fd.isValid() && fd.isReadable()) {

                            DatagramChannel sc = (DatagramChannel) fd.channel();
                            final DatagramSocket socket = sc.socket();

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
                    e.printStackTrace();
                }

            } while (running);

        }

        public void terminate() {
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
                selector.wakeup();
            } finally {
                putLock.unlock();
            }
        }

    }

    class NioListenThreadGroup {

        private final NioListenThread[] threadPool;

        private final int multi;

        private final String poolName;

        private final AtomicInteger tId = new AtomicInteger();

        public NioListenThreadGroup(int multi, String poolName) throws IOException {
            this.multi = multi;
            this.threadPool = new NioListenThread[multi];
            this.poolName = poolName;
            createPool();
        }

        private void createPool() throws IOException {
            for (int i = 0; i < multi; i++) {
                final NioListenThread thread = new NioListenThread(generateTName());
                threadPool[i] = thread;
                thread.start();
            }
        }

        private String generateTName() {
            return poolName + "-" + tId.getAndIncrement();
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

    }

}
