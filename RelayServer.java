import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.*;

import com.fasterxml.jackson.databind.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RelayServer {
    private static final int CLIENT_PORT = 7000;
    private static final String BACKHAUL_HOST = "home.python.host";
    private static final int BACKHAUL_PORT = 6000;
    private static final AtomicInteger ID_GEN = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Channel> clients = new ConcurrentHashMap<>();
    private static Channel backhaulChannel;
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws InterruptedException {
        // 1) Connect to Python backhaul
        EventLoopGroup backGroup = new NioEventLoopGroup();
        Bootstrap backBootstrap = new Bootstrap();
        backBootstrap.group(backGroup)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new LineBasedFrameDecoder(1024),
                        new StringDecoder(),
                        new StringEncoder()
                    ).addLast(new BackhaulHandler());
                }
            });
        backhaulChannel = backBootstrap.connect(BACKHAUL_HOST, BACKHAUL_PORT).sync().channel();

        // 2) Start client listener
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.group(bossGroup, workerGroup)
              .channel(NioServerSocketChannel.class)
              .childHandler(new ChannelInitializer<SocketChannel>() {
                  protected void initChannel(SocketChannel ch) {
                      int clientId = ID_GEN.getAndIncrement();
                      ch.pipeline().addLast(
                          new LineBasedFrameDecoder(1024),
                          new StringDecoder(),
                          new StringEncoder()
                      ).addLast(new ClientHandler(clientId));
                  }
              });
            sb.bind(CLIENT_PORT).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            backGroup.shutdownGracefully();
        }
    }

    // Handles inbound from clients
    static class ClientHandler extends SimpleChannelInboundHandler<String> {
        private final int clientId;
        private Channel channel;

        ClientHandler(int id) { this.clientId = id; }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            clients.put(clientId, channel);
            System.out.println("Client connected: " + clientId);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            // Wrap and forward to backhaul
            ObjectNode packet = mapper.createObjectNode();
            packet.put("clientId", clientId);
            packet.put("data", msg);
            backhaulChannel.writeAndFlush(mapper.writeValueAsString(packet) + "\n");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            clients.remove(clientId);
            System.out.println("Client disconnected: " + clientId);
        }
    }

    // Handles responses from backhaul
    static class BackhaulHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            JsonNode node = mapper.readTree(msg);
            int cid = node.get("clientId").asInt();
            String data = node.get("data").asText();
            Channel clientCh = clients.get(cid);
            if (clientCh != null && clientCh.isActive()) {
                clientCh.writeAndFlush(data + "\n");
            }
        }
    }
}
