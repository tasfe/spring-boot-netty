package org.kgusarov.integration.spring.netty;

import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ReplayingDecoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.Unpooled.copyLong;
import static org.junit.Assert.assertEquals;

public class TcpServerIntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TcpServerIntegrationTest.class);

    private TcpServer tcpServer;

    @Before
    public void setUp() throws Exception {
        final ChannelOptions childOptions = new ChannelOptions();
        childOptions.setTcpNodelay(true);

        final ChannelOptions options = new ChannelOptions();
        options.setTcpNodelay(true);
        options.setSoReuseAddr(true);

        tcpServer = new TcpServer("test-server");

        tcpServer.setBossThreads(2);
        tcpServer.setWorkerThreads(8);
        tcpServer.setHost("localhost");
        tcpServer.setPort(40000);
        tcpServer.setChildOptions(childOptions);
        tcpServer.setOptions(options);
    }

    @After
    public void tearDown() throws Exception {
        tcpServer.stop();
    }

    @Test
    public void testServerStarts() throws Exception {
        final Future<Void> startFuture = tcpServer.start();
        startFuture.get();
    }

    @Test(expected = IllegalStateException.class)
    public void testOnceServerIsStartedItCannotBeReconfigured() throws Exception {
        final Future<Void> startFuture = tcpServer.start();
        startFuture.get();

        tcpServer.setPort(12345);
    }

    @Test(expected = IllegalStateException.class)
    public void testOnceServerIsStartedNextStartAttemptWillFail() throws Exception {
        final Future<Void> startFuture = tcpServer.start();
        startFuture.get();

        tcpServer.start();
    }

    @Test
    public void testConnectHandlerWorks() throws Exception {
        final AtomicInteger connections = new AtomicInteger(0);
        final ChannelHandler onConnect = new OnConnectHandler(connections);
        final ServerClient client = new ServerClient(40000, "localhost");

        tcpServer.onConnect(() -> onConnect);
        tcpServer.start().get();

        client.connect().get().disconnect();
        client.connect().get().disconnect();

        tcpServer.stop();
        assertEquals(2, connections.get());
    }

    @Test
    public void testMultipleConnectHandlers() throws Exception {
        final AtomicInteger connections = new AtomicInteger(0);
        final ChannelHandler onConnect1 = new OnConnectHandler(connections);
        final ChannelHandler onConnect2 = new OnConnectHandler(connections);
        final ServerClient client = new ServerClient(40000, "localhost");

        tcpServer.onConnect(() -> onConnect1);
        tcpServer.onConnect(() -> onConnect2);
        tcpServer.start().get();

        client.connect().get().disconnect();

        tcpServer.stop();
        assertEquals(2, connections.get());
    }

    @Test
    public void testDisconnectHandlerWorks() throws Exception {
        final AtomicInteger disconnects = new AtomicInteger(0);
        final OnDisconnectHandler onDisconnectHandler = new OnDisconnectHandler(disconnects);
        final ServerClient client = new ServerClient(40000, "localhost");

        tcpServer.onDisconnect(() -> onDisconnectHandler);
        tcpServer.start().get();

        client.connect().get().disconnect();
        client.connect().get().disconnect();

        tcpServer.stop();
        assertEquals(2, disconnects.get());
    }

    @Test
    public void testHandlersWork() throws Exception {
        tcpServer.addHandler("echoHandler", EchoServerHandler::new);
        tcpServer.start().get();

        final SettableFuture<Long> responseHolder = SettableFuture.create();
        final ServerClient client = new ServerClient(40000, "localhost",
                new EchoClientHandler(responseHolder));

        client.connect().get();

        final ByteBuf msg = copyLong(1L);
        client.writeAndFlush(msg).syncUninterruptibly();

        final long actual = responseHolder.get();
        assertEquals(1L, actual);

        client.disconnect();
        tcpServer.stop();
    }

    @Test
    public void testMultipleHandlersWorkForMultipleMessages() throws Exception {
        tcpServer.addHandler("echoDecoder", EchoServerDecoder::new);
        tcpServer.addHandler("echoEncoder", EchoServerEncoder::new);
        tcpServer.addHandler("echoHandler", EchoServerHandler::new);
        tcpServer.start().get();

        final SettableFuture<Long> responseHolder1 = SettableFuture.create();
        final SettableFuture<Long> responseHolder2 = SettableFuture.create();
        final ServerClient client = new ServerClient(40000, "localhost",
                new EchoClientHandler(responseHolder1, responseHolder2));

        client.connect().get();

        final ByteBuf msg1 = copyLong(1L);
        final ByteBuf msg2 = copyLong(2L);
        client.writeAndFlush(msg1).syncUninterruptibly();
        client.writeAndFlush(msg2).syncUninterruptibly();

        final long actual1 = responseHolder1.get();
        final long actual2 = responseHolder2.get();
        assertEquals(1L, actual1);
        assertEquals(2L, actual2);

        client.disconnect();
        tcpServer.stop();
    }

    private static class EchoServerDecoder extends ReplayingDecoder<Long> {
        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
            out.add(in.readLong());
        }
    }

    private static class EchoServerEncoder extends MessageToByteEncoder<Long> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final Long msg, final ByteBuf out) throws Exception {
            out.writeLong(msg);
        }
    }

    private static class EchoClientHandler extends ChannelInboundHandlerAdapter {
        private final SettableFuture<Long>[] responseHolders;
        private int currentResponse = 0;

        private EchoClientHandler(final SettableFuture<Long>... responseHolders) {
            this.responseHolders = responseHolders;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            final ByteBuf buf = (ByteBuf) msg;
            final long i = buf.readLong();

            LOGGER.info("Message from server: " + i);
            responseHolders[currentResponse++].set(i);
        }
    }

    private static class EchoServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            LOGGER.info("Message from client: " + msg);

            ctx.writeAndFlush(msg);
        }
    }

    @ChannelHandler.Sharable
    private static class OnDisconnectHandler implements ChannelFutureListener {
        private final AtomicInteger disconnects;

        public OnDisconnectHandler(final AtomicInteger disconnects) {
            this.disconnects = disconnects;
        }

        @Override
        public void operationComplete(final ChannelFuture future) throws Exception {
            LOGGER.debug("Client disconnected!");
            disconnects.incrementAndGet();
        }
    }

    @ChannelHandler.Sharable
    private static class OnConnectHandler extends ChannelInboundHandlerAdapter {
        private final AtomicInteger connections;

        public OnConnectHandler(final AtomicInteger connections) {
            this.connections = connections;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            LOGGER.debug("Client connected!");
            connections.incrementAndGet();

            ctx.fireChannelActive();
        }
    }
}