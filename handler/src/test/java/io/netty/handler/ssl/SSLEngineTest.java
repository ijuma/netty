/*
 * Copyright 2015 The Netty Project
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
package io.netty.handler.ssl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.verify;

public abstract class SSLEngineTest {

    @Mock
    protected MessageReciever serverReceiver;
    @Mock
    protected MessageReciever clientReceiver;

    protected Throwable serverException;
    protected Throwable clientException;
    protected SslContext serverSslCtx;
    protected SslContext clientSslCtx;
    protected ServerBootstrap sb;
    protected Bootstrap cb;
    protected Channel serverChannel;
    protected Channel serverConnectedChannel;
    protected Channel clientChannel;
    protected CountDownLatch serverLatch;
    protected CountDownLatch clientLatch;

    interface MessageReciever {
        void messageReceived(ByteBuf msg);
    }

    protected static final class MessageDelegatorChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final MessageReciever receiver;
        private final CountDownLatch latch;

        public MessageDelegatorChannelHandler(MessageReciever receiver, CountDownLatch latch) {
            super(false);
            this.receiver = receiver;
            this.latch = latch;
        }

        @Override
        protected void messageReceived(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            receiver.messageReceived(msg);
            latch.countDown();
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        serverLatch = new CountDownLatch(1);
        clientLatch = new CountDownLatch(1);
    }

    @After
    public void tearDown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
            Future<?> serverGroup = sb.group().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            Future<?> serverChildGroup = sb.childGroup().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            Future<?> clientGroup = cb.group().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            serverGroup.sync();
            serverChildGroup.sync();
            clientGroup.sync();
        }
        clientChannel = null;
        serverChannel = null;
        serverConnectedChannel = null;
        serverException = null;
    }

    @Test
    public void testMutualAuthSameCerts() throws Exception {
        mySetupMutualAuth(new File(getClass().getResource("test_unencrypted.pem").getFile()),
                          new File(getClass().getResource("test.crt").getFile()),
                          null);
        runTest(null);
    }

    @Test
    public void testMutualAuthDiffCerts() throws Exception {
        File serverKeyFile = new File(getClass().getResource("test_encrypted.pem").getFile());
        File serverCrtFile = new File(getClass().getResource("test.crt").getFile());
        String serverKeyPassword = "12345";
        File clientKeyFile = new File(getClass().getResource("test2_encrypted.pem").getFile());
        File clientCrtFile = new File(getClass().getResource("test2.crt").getFile());
        String clientKeyPassword = "12345";
        mySetupMutualAuth(clientCrtFile, serverKeyFile, serverCrtFile, serverKeyPassword,
                          serverCrtFile, clientKeyFile, clientCrtFile, clientKeyPassword);
        runTest(null);
    }

    @Test
    public void testMutualAuthDiffCertsServerFailure() throws Exception {
        File serverKeyFile = new File(getClass().getResource("test_encrypted.pem").getFile());
        File serverCrtFile = new File(getClass().getResource("test.crt").getFile());
        String serverKeyPassword = "12345";
        File clientKeyFile = new File(getClass().getResource("test2_encrypted.pem").getFile());
        File clientCrtFile = new File(getClass().getResource("test2.crt").getFile());
        String clientKeyPassword = "12345";
        // Client trusts server but server only trusts itself
        mySetupMutualAuth(serverCrtFile, serverKeyFile, serverCrtFile, serverKeyPassword,
                          serverCrtFile, clientKeyFile, clientCrtFile, clientKeyPassword);
        assertTrue(serverLatch.await(2, TimeUnit.SECONDS));
        assertTrue(serverException instanceof SSLHandshakeException);
    }

    @Test
    public void testMutualAuthDiffCertsClientFailure() throws Exception {
        File serverKeyFile = new File(getClass().getResource("test_unencrypted.pem").getFile());
        File serverCrtFile = new File(getClass().getResource("test.crt").getFile());
        String serverKeyPassword = null;
        File clientKeyFile = new File(getClass().getResource("test2_unencrypted.pem").getFile());
        File clientCrtFile = new File(getClass().getResource("test2.crt").getFile());
        String clientKeyPassword = null;
        // Server trusts client but client only trusts itself
        mySetupMutualAuth(clientCrtFile, serverKeyFile, serverCrtFile, serverKeyPassword,
                          clientCrtFile, clientKeyFile, clientCrtFile, clientKeyPassword);
        assertTrue(clientLatch.await(2, TimeUnit.SECONDS));
        assertTrue(clientException instanceof SSLHandshakeException);
    }

    @Test
    public void testWrapWrapUnwrap() throws Exception {
        final SslContext clientContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(sslProvider())
                .build();
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext serverContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .sslProvider(sslProvider())
                .build();
        SSLEngine clientEngine = clientContext.newEngine(UnpooledByteBufAllocator.DEFAULT);
        SSLEngine serverEngine = serverContext.newEngine(UnpooledByteBufAllocator.DEFAULT);

        int netBufferSize = 17 * 1024;
        ByteBuffer cTOs = ByteBuffer.allocateDirect(netBufferSize);
        ByteBuffer sTOc = ByteBuffer.allocateDirect(netBufferSize);

        ByteBuffer serverAppReadBuffer = ByteBuffer.allocateDirect(serverEngine.getSession().getApplicationBufferSize());
        ByteBuffer clientAppReadBuffer = ByteBuffer.allocateDirect(clientEngine.getSession().getApplicationBufferSize());

        ByteBuffer serverOut = ByteBuffer.wrap("Some data".getBytes());
        ByteBuffer serverOut2 = ByteBuffer.wrap("More data!".getBytes());
        ByteBuffer combinedServerOut = ByteBuffer.allocateDirect(serverOut.limit() + serverOut2.limit());
        combinedServerOut.put(serverOut);
        combinedServerOut.put(serverOut2);
        serverOut.clear();
        serverOut2.clear();

        ByteBuffer clientIn = ByteBuffer.allocateDirect(combinedServerOut.limit() + 50);

        clientEngine.beginHandshake();
        serverEngine.beginHandshake();

        ByteBuffer empty = ByteBuffer.allocate(0);

        SSLEngineResult result;
        //handshake
        do {
            result = clientEngine.wrap(empty, cTOs);
            runDelegatedTasks(result, clientEngine);
            result = serverEngine.wrap(empty, sTOc);
            runDelegatedTasks(result, serverEngine);
            cTOs.flip();
            sTOc.flip();
            result = clientEngine.unwrap(sTOc, clientAppReadBuffer);
            runDelegatedTasks(result, clientEngine);
            result = serverEngine.unwrap(cTOs, serverAppReadBuffer);
            runDelegatedTasks(result, serverEngine);
            cTOs.compact();
            sTOc.compact();
        } while (isHandshaking(result) && isHandshaking(result));

        serverEngine.wrap(serverOut, sTOc);
        int positionAfterFirstWrap = sTOc.position();
        serverEngine.wrap(serverOut2, sTOc);
        int positionAfterSecondWrap = sTOc.position();
        sTOc.flip();

        clientEngine.unwrap(sTOc, clientIn);
        // If we only consume the bytes for one record (like the JDK's `SSLEngineImpl` does), we need to call `unwrap`
        // again. If we consume the bytes for both records (like OpenSslEngine does), we don't call `unwrap` a second
        // time to verify that we produce the same number of records that we consume
        // (see https://github.com/netty/netty/issues/4238)
        if (sTOc.position() == positionAfterFirstWrap) {
            clientEngine.unwrap(sTOc, clientIn);
        }
        assertEquals(positionAfterSecondWrap, sTOc.position());

        checkTransfer(combinedServerOut, clientIn);

        clientEngine.closeOutbound();
        serverEngine.closeOutbound();
    }

    private boolean isHandshaking(SSLEngineResult result) {
        return result.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING &&
                result.getHandshakeStatus() != HandshakeStatus.FINISHED;
    }

    private static void checkTransfer(ByteBuffer a, ByteBuffer b)
            throws Exception {
        a.flip();
        b.flip();
        assertEquals(a, b);
        a.position(a.limit());
        b.position(b.limit());
        a.limit(a.capacity());
        b.limit(b.capacity());
    }

    private static void runDelegatedTasks(SSLEngineResult result,
                                          SSLEngine engine) throws Exception {
        if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
            Runnable runnable;
            while ((runnable = engine.getDelegatedTask()) != null) {
                runnable.run();
            }
            HandshakeStatus hsStatus = engine.getHandshakeStatus();
            if (hsStatus == HandshakeStatus.NEED_TASK) {
                throw new Exception("handshake shouldn't need additional tasks");
            }
        }
    }

    private void mySetupMutualAuth(File keyFile, File crtFile, String keyPassword)
            throws SSLException, InterruptedException {
        mySetupMutualAuth(crtFile, keyFile, crtFile, keyPassword, crtFile, keyFile, crtFile, keyPassword);
    }

    private void mySetupMutualAuth(
            File servertTrustCrtFile, File serverKeyFile, File serverCrtFile, String serverKeyPassword,
            File clientTrustCrtFile, File clientKeyFile, File clientCrtFile, String clientKeyPassword)
            throws InterruptedException, SSLException {
        serverSslCtx = SslContext.newServerContext(sslProvider(), servertTrustCrtFile, null,
                                                   serverCrtFile, serverKeyFile, serverKeyPassword, null,
                                                   null, IdentityCipherSuiteFilter.INSTANCE, null, 0, 0);
        clientSslCtx = SslContext.newClientContext(sslProvider(), clientTrustCrtFile, null,
                                                   clientCrtFile, clientKeyFile, clientKeyPassword, null,
                                                   null, IdentityCipherSuiteFilter.INSTANCE,
                                                   null, 0, 0);

        serverConnectedChannel = null;
        sb = new ServerBootstrap();
        cb = new Bootstrap();

        sb.group(new NioEventLoopGroup(), new NioEventLoopGroup());
        sb.channel(NioServerSocketChannel.class);
        sb.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                SSLEngine engine = serverSslCtx.newEngine(ch.alloc());
                engine.setUseClientMode(false);
                engine.setNeedClientAuth(true);
                p.addLast(new SslHandler(engine));
                p.addLast(new MessageDelegatorChannelHandler(serverReceiver, serverLatch));
                p.addLast(new ChannelHandlerAdapter() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        if (cause.getCause() instanceof SSLHandshakeException) {
                            serverException = cause.getCause();
                            serverLatch.countDown();
                        } else {
                            ctx.fireExceptionCaught(cause);
                        }
                    }
                });
                serverConnectedChannel = ch;
            }
        });

        cb.group(new NioEventLoopGroup());
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast(clientSslCtx.newHandler(ch.alloc()));
                p.addLast(new MessageDelegatorChannelHandler(clientReceiver, clientLatch));
                p.addLast(new ChannelHandlerAdapter() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        cause.printStackTrace();
                        if (cause.getCause() instanceof SSLHandshakeException) {
                            clientException = cause.getCause();
                            clientLatch.countDown();
                        } else {
                            ctx.fireExceptionCaught(cause);
                        }
                    }
                });
            }
        });

        serverChannel = sb.bind(new InetSocketAddress(0)).sync().channel();
        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        ChannelFuture ccf = cb.connect(new InetSocketAddress(NetUtil.LOCALHOST, port));
        assertTrue(ccf.awaitUninterruptibly().isSuccess());
        clientChannel = ccf.channel();
    }

    protected void runTest(String expectedApplicationProtocol) throws Exception {
        final ByteBuf clientMessage = Unpooled.copiedBuffer("I am a client".getBytes());
        final ByteBuf serverMessage = Unpooled.copiedBuffer("I am a server".getBytes());
        try {
            writeAndVerifyReceived(clientMessage.retain(), clientChannel, serverLatch, serverReceiver);
            writeAndVerifyReceived(serverMessage.retain(), serverConnectedChannel, clientLatch, clientReceiver);
            if (expectedApplicationProtocol != null) {
                verifyApplicationLevelProtocol(clientChannel, expectedApplicationProtocol);
                verifyApplicationLevelProtocol(serverConnectedChannel, expectedApplicationProtocol);
            }
        } finally {
            clientMessage.release();
            serverMessage.release();
        }
    }

    private static void verifyApplicationLevelProtocol(Channel channel, String expectedApplicationProtocol) {
        SslHandler handler = channel.pipeline().get(SslHandler.class);
        assertNotNull(handler);
        String appProto = handler.applicationProtocol();
        assertEquals(appProto, expectedApplicationProtocol);
    }

    private static void writeAndVerifyReceived(ByteBuf message, Channel sendChannel, CountDownLatch receiverLatch,
                                               MessageReciever receiver) throws Exception {
        List<ByteBuf> dataCapture = null;
        try {
            sendChannel.writeAndFlush(message);
            receiverLatch.await(5, TimeUnit.SECONDS);
            message.resetReaderIndex();
            ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
            verify(receiver).messageReceived(captor.capture());
            dataCapture = captor.getAllValues();
            assertEquals(message, dataCapture.get(0));
        } finally {
            if (dataCapture != null) {
                for (ByteBuf data : dataCapture) {
                    data.release();
                }
            }
        }
    }

    protected abstract SslProvider sslProvider();
}
