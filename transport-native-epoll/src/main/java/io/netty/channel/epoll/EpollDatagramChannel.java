/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramChannelConfig;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.ByteBuffer;


/**
 * {@link DatagramChannel} implementation that uses linux EPOLL Edge-Triggered Mode for
 * maximal performance.
 */
public final class EpollDatagramChannel extends AbstractEpollChannel implements DatagramChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(true);

    private volatile InetSocketAddress local;
    private final EpollDatagramChannelConfig config;
    public EpollDatagramChannel() {
        super(Native.socketDgramFd(), Native.EPOLLIN);
        config = new EpollDatagramChannelConfig(this);
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public boolean isActive() {
        return fd != -1 && (
                (config.getOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) && isRegistered()));
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress, ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture joinGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture joinGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface,
                                   ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress, NetworkInterface networkInterface,
                                   InetAddress source) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source,
                                   ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress, ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface,
                                    ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress, NetworkInterface networkInterface,
                                    InetAddress source) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress, NetworkInterface networkInterface,
                                    InetAddress source, ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture block(InetAddress multicastAddress, NetworkInterface networkInterface,
                               InetAddress sourceToBlock) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture block(InetAddress multicastAddress, NetworkInterface networkInterface,
                               InetAddress sourceToBlock, ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock) {
        return newFailedFuture(new UnsupportedOperationException());
    }

    @Override
    public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock, ChannelPromise future) {
        return future.setFailure(new UnsupportedOperationException());
    }

    @Override
    protected AbstractEpollUnsafe newUnsafe() {
        return new EpollDatagramChannelUnsafe();
    }

    @Override
    protected InetSocketAddress localAddress0() {
        return local;
    }

    @Override
    protected InetSocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        InetSocketAddress addr = (InetSocketAddress) localAddress;
        checkResolvable(addr);
        Native.bind(fd, addr.getAddress(), addr.getPort());
        local = Native.localAddress(fd);
        active = true;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearEpollOut();
                break;
            }

            boolean done = false;
            for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                if (doWriteMessage(msg)) {
                    done = true;
                    break;
                }
            }

            if (done) {
                in.remove();
            } else {
                // Did not write all messages.
                setEpollOut();
                break;
            }
        }
    }

    private boolean doWriteMessage(Object msg) throws IOException {
        final Object m;
        final InetSocketAddress remoteAddress;
        ByteBuf data;
        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<Object, InetSocketAddress> envelope = (AddressedEnvelope<Object, InetSocketAddress>) msg;
            remoteAddress = envelope.recipient();
            m = envelope.content();
        } else {
            m = msg;
            remoteAddress = null;
        }

        if (m instanceof ByteBufHolder) {
            data = ((ByteBufHolder) m).content();
        } else if (m instanceof ByteBuf) {
            data = (ByteBuf) m;
        } else {
            throw new UnsupportedOperationException("unsupported message type: " + StringUtil.simpleClassName(msg));
        }

        int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        final int writtenBytes;
        if (data.hasMemoryAddress()) {
            long memoryAddress = data.memoryAddress();
            if (remoteAddress != null) {
                writtenBytes = Native.sendToAddress(fd, memoryAddress, data.readerIndex(), data.writerIndex(),
                        remoteAddress.getAddress(), remoteAddress.getPort());
            } else {
                // TODO: Implement me
                throw new UnsupportedOperationException();
            }
        } else  {
            ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), data.readableBytes());
            if (remoteAddress != null) {
                writtenBytes = Native.sendTo(fd, nioData, nioData.position(), nioData.limit(),
                        remoteAddress.getAddress(), remoteAddress.getPort());
            } else {
                // TODO: FIX ME writtenBytes = javaChannel().write(nioData);
                throw new UnsupportedOperationException();
            }
        }
        return writtenBytes > 0;
    }

    @Override
    public EpollDatagramChannelConfig config() {
        return config;
    }

    @Override
    protected ChannelOutboundBuffer newOutboundBuffer() {
        return EpollDatagramChannelOutboundBuffer.newInstance(this);
    }

    final class EpollDatagramChannelUnsafe extends AbstractEpollUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;
        @Override
        public void connect(SocketAddress socketAddress, SocketAddress socketAddress2, ChannelPromise channelPromise) {
            // TODO: Fix me
            channelPromise.setFailure(new UnsupportedOperationException());
        }

        @Override
        void epollInReady() {
            DatagramChannelConfig config = config();
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }

            assert eventLoop().inEventLoop();
            final ChannelPipeline pipeline = pipeline();
            Throwable exception = null;
            try {
                try {
                    for (;;) {
                        boolean free = true;
                        ByteBuf data = allocHandle.allocate(config.getAllocator());
                        int writerIndex = data.writerIndex();
                        DatagramSocketAddress remoteAddress;
                        if (data.hasMemoryAddress()) {
                            // has a memory address so use optimized call
                            remoteAddress = Native.recvFromAddress(
                                    fd, data.memoryAddress(), writerIndex, data.capacity());
                        } else {
                            ByteBuffer nioData = data.internalNioBuffer(writerIndex, data.writableBytes());
                            remoteAddress = Native.recvFrom(
                                    fd, nioData, nioData.position(), nioData.limit());
                        }

                        if (remoteAddress == null) {
                            break;
                        }

                        int readBytes = remoteAddress.receivedAmount;
                        data.writerIndex(data.writerIndex() + readBytes);
                        allocHandle.record(readBytes);
                        try {
                            readPending = false;
                            pipeline.fireChannelRead(
                                    new DatagramPacket(data, (InetSocketAddress) localAddress(), remoteAddress));
                            free = false;
                        } catch (Throwable t) {
                            // keep on reading as we use epoll ET and need to consume everything from the socket
                            pipeline.fireChannelReadComplete();
                            pipeline.fireExceptionCaught(t);
                        } finally {
                            if (free) {
                                data.release();
                            }
                        }
                    }
                } catch (Throwable t) {
                    exception = t;
                }
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    pipeline.fireExceptionCaught(exception);
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!config().isAutoRead() && !readPending) {
                    clearEpollIn();
                }
            }
        }
    }

    /**
     * Act as special {@link InetSocketAddress} to be able to easily pass all needed data from JNI without the need
     * to create more objects then needed.
     */
    static final class DatagramSocketAddress extends InetSocketAddress {
        // holds the amount of received bytes
        final int receivedAmount;

        DatagramSocketAddress(String addr, int port, int receivedAmount) {
            super(addr, port);
            this.receivedAmount = receivedAmount;
        }
    }
}
