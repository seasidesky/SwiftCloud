/*****************************************************************************
 * Copyright 2011-2012 INRIA
 * Copyright 2011-2012 Universidade Nova de Lisboa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/
package sys.net.impl.providers.netty.tcp;

import static sys.Sys.Sys;
import static sys.net.impl.NetworkingConstants.NETTY_CORE_THREADS;
import static sys.net.impl.NetworkingConstants.NETTY_MAX_MEMORY_PER_CHANNEL;
import static sys.net.impl.NetworkingConstants.NETTY_MAX_TOTAL_MEMORY;
import static sys.net.impl.NetworkingConstants.NETTY_WRITEBUFFER_DEFAULTSIZE;
import static sys.net.impl.NetworkingConstants.TCP_CONNECTION_TIMEOUT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.MemoryAwareThreadPoolExecutor;

import sys.net.api.Endpoint;
import sys.net.api.Message;
import sys.net.api.TransportConnection;
import sys.net.impl.AbstractEndpoint;
import sys.net.impl.AbstractLocalEndpoint;
import sys.net.impl.FailedTransportConnection;
import sys.net.impl.KryoLib;
import sys.net.impl.providers.InitiatorInfo;
import sys.net.impl.providers.RemoteEndpointUpdater;
import sys.utils.Threading;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

final public class TcpEndpoint extends AbstractLocalEndpoint {

    private static Logger Log = Logger.getLogger(TcpEndpoint.class.getName());

    final Executor bossExecutors, workerExecutors;
    final ExecutionHandler executionHandler;

    public TcpEndpoint(Endpoint local, int tcpPort) throws IOException {
        this.localEndpoint = local;
        this.gid = Sys.rg.nextLong() >>> 1;

        bossExecutors = Executors.newCachedThreadPool();
        workerExecutors = Executors.newCachedThreadPool();

        executionHandler = new ExecutionHandler(new MemoryAwareThreadPoolExecutor(NETTY_CORE_THREADS,
                NETTY_MAX_MEMORY_PER_CHANNEL, NETTY_MAX_TOTAL_MEMORY));

        boolean isServer = tcpPort >= 0;
        if (isServer) {
            ServerBootstrap bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(bossExecutors,
                    workerExecutors));

            // Set up the pipeline factory.
            bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
                public ChannelPipeline getPipeline() throws Exception {
                    ChannelPipeline res = Channels.pipeline();
                    res.addLast("FrameDecoder", new MessageFrameDecoder());
                    res.addLast("MessageHandler", new IncomingConnectionHandler());
                    return res;
                }
            });

            bootstrap.setOption("child.tcpNoDelay", true);
            bootstrap.setOption("child.keepAlive", true);
            bootstrap.setOption("child.reuseAddress", true);
            bootstrap.setOption("child.connectTimeoutMillis", TCP_CONNECTION_TIMEOUT);

            Channel ch = bootstrap.bind(new InetSocketAddress(tcpPort));
            super.setSocketAddress(((InetSocketAddress) ch.getLocalAddress()).getPort());
            Log.fine("Bound to: " + this);
        } else {
            super.setSocketAddress(0);
        }
    }

    public void setExecutor(Executor executor) {
    }

    public void setOption(String op, Object val) {
    }

    public void start() throws IOException {
        handler = localEndpoint.getHandler();
    }

    public TransportConnection connect(Endpoint remote) {
        final OutgoingConnectionHandler res = new OutgoingConnectionHandler(remote);
        ClientBootstrap bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(bossExecutors,
                workerExecutors));
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new MessageFrameDecoder(), res);
            }
        });
        ChannelFuture future = bootstrap.connect(((AbstractEndpoint) remote).sockAddress());
        future.awaitUninterruptibly();
        if (!future.isSuccess()) {
            Log.severe("Bad connection to:" + remote);
            return new FailedTransportConnection(localEndpoint, remote, future.getCause());
        } else {
            res.channelConnected(future.getChannel());
            res.send(new InitiatorInfo(res.localEndpoint()));
            return res;
        }
    }

    class AbstractConnection extends SimpleChannelUpstreamHandler implements TransportConnection, RemoteEndpointUpdater {

        Channel channel;
        boolean failed;
        Endpoint remote;
        Throwable cause;
        AtomicLong incomingBytesCounter = new AtomicLong();
        AtomicLong outgoingBytesCounter = new AtomicLong();

        AbstractConnection() {
        }

        @Override
        public boolean failed() {
            return failed;
        }

        public boolean send(final Message msg) {
            try {
                while (!channel.isWritable())
                    Threading.synchronizedWaitOn(this, 10);

                ChannelBuffer buf = ChannelBuffers.dynamicBuffer(NETTY_WRITEBUFFER_DEFAULTSIZE);
                buf.writeInt(0);
                Output output = new Output(new ChannelBufferOutputStream(buf));

                KryoLib.kryo().writeClassAndObject(output, msg);
                output.close();

                int uploadTotal = output.total();
                buf.setInt(0, uploadTotal);

                ChannelFuture fut = channel.write(buf);

                Sys.uploadedBytes.getAndAdd(uploadTotal + 4);
                outgoingBytesCounter.getAndAdd(uploadTotal + 4);

                return fut.awaitUninterruptibly().isSuccess();

            } catch (Throwable t) {
                t.printStackTrace();
            }
            return false;
        }

        @Override
        public Endpoint localEndpoint() {
            return localEndpoint;
        }

        @Override
        public Endpoint remoteEndpoint() {
            return remote;
        }

        @Override
        public void dispose() {
            channel.close();
            handler.onClose(this);
        }

        public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) {
            if (channel.isWritable())
                Threading.synchronizedNotifyAllOn(this);
        }

        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Message msg;
            try {
                Input in = new Input(((ChannelBuffer) e.getMessage()).array());
                msg = (Message) KryoLib.kryo().readClassAndObject(in);
                e = null;

                int downloadTotal = in.position() + 4;

                Sys.downloadedBytes.getAndAdd(downloadTotal);
                incomingBytesCounter.addAndGet(downloadTotal);

                msg.deliverTo(AbstractConnection.this, handler);
            } catch (Exception x) {
                x.printStackTrace();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            failed = true;
            cause = e.getCause();
            e.getChannel().close();
            handler.onFailure(this);
        }

        @Override
        public Throwable causeOfFailure() {
            return cause;
        }

        public void setRemoteEndpoint(Endpoint remote) {
            this.remote = remote;
            this.incomingBytesCounter = remote.getIncomingBytesCounter();
            this.outgoingBytesCounter = remote.getOutgoingBytesCounter();
            channel.getPipeline().addAfter("FrameDecoder", "Executor", executionHandler);
        }

        @Override
        public void setOption(String op, Object value) {
        }
    }

    class IncomingConnectionHandler extends AbstractConnection {

        synchronized public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            channel = e.getChannel();
        }
    }

    class OutgoingConnectionHandler extends AbstractConnection {
        public OutgoingConnectionHandler(Endpoint remote) {
            super.remote = remote;
        }

        public void channelConnected(Channel ch) {
            channel = ch;
            handler.onConnect(this);
        }

        public String toString() {
            return "" + localEndpoint + " -> " + remote + ": " + channel.getLocalAddress();
        }
    }

    static final class MessageFrameDecoder extends LengthFieldBasedFrameDecoder {
        public MessageFrameDecoder() {
            super(Integer.MAX_VALUE, 0, 4, 0, 4, false);
        }
    }
}