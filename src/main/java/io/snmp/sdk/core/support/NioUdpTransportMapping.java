package io.snmp.sdk.core.support;

import org.snmp4j.SNMP4JSettings;
import org.snmp4j.TransportStateReference;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.WorkerTask;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 多路复用通信模型实现.
 *
 * <p>覆写{@link DefaultUdpTransportMapping}核心方法，转用多路复用NIO模型.</p>
 *
 * @author ssp
 * @since 1.0
 */
public class NioUdpTransportMapping extends DefaultUdpTransportMapping {

    private NioListenThread listenerThread;

    private final InetSocketAddress localBindAddress;

    /**
     * remote-channel.
     */
    private Map<UdpAddress, DatagramChannel> channelsTable = new ConcurrentHashMap<>();


    public NioUdpTransportMapping() throws IOException {
        localBindAddress = new InetSocketAddress(0);
    }


    public NioUdpTransportMapping(UdpAddress udpAddress, boolean reuseAddress) throws IOException {
        super(udpAddress, reuseAddress);
        localBindAddress = new InetSocketAddress(udpAddress.getInetAddress().getHostAddress(), 0);
    }

    public NioUdpTransportMapping(UdpAddress udpAddress) throws IOException {
        super(udpAddress);
        localBindAddress = new InetSocketAddress(udpAddress.getInetAddress().getHostAddress(), 0);
    }

    @Override
    public synchronized void listen() throws IOException {
        if (listener != null) {
            throw new SocketException("Port already listening");
        }
        listenerThread = new NioListenThread();

        listener = SNMP4JSettings.getThreadFactory().createWorkerThread(
                "DefaultUDPTransportMapping_" + getAddress(), listenerThread, true);
        listener.run();
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

                listenerThread.register(datagramChannel);

                return datagramChannel;
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    class NioListenThread implements WorkerTask {

        private final Selector selector;

        private volatile boolean running = true;

        private List<Runnable> registerTasks = new LinkedList<>();

        private final ReentrantLock putLock = new ReentrantLock();


        public NioListenThread() throws IOException {
            selector = Selector.open();
        }

        @Override
        public void run() {

            do {
                try {
                    selector.select(100);
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
                                    new TransportStateReference(NioUdpTransportMapping.this, new UdpAddress(localAddress.getAddress(), localAddress.getPort()), null,
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

        @Override
        public void terminate() {
            running = false;
        }

        @Override
        public void join() throws InterruptedException {

        }

        @Override
        public void interrupt() {
            terminate();
        }

        public void register(DatagramChannel datagramChannel) {

            try {
                putLock.lock();
                registerTasks.add(() -> {
                    try {
                        datagramChannel.register(selector, SelectionKey.OP_READ); //注册channel到多路复用器上，设置关注事件:read
                    } catch (ClosedChannelException e) {
                        e.printStackTrace();
                    }
                });
            } finally {
                putLock.unlock();
            }
        }
    }
}
