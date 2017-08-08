package com.orctom.laputa.service.internal;

import com.orctom.laputa.exception.IllegalConfigException;
import com.orctom.laputa.service.config.Configurator;
import com.orctom.laputa.utils.HostUtils;
import com.typesafe.config.Config;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;

import static com.orctom.laputa.service.Constants.*;

/**
 * boot netty service
 * Created by hao on 1/6/16.
 */
public class Bootstrapper extends Thread {

  private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrapper.class);

  private static LaputaRequestProcessor requestProcessor = new LaputaRequestProcessor();

  private int port;
  private boolean useSSL;

  private SslContext sslContext;

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;

  public Bootstrapper(int port, boolean useSSL) {
    this.port = port;
    this.useSSL = useSSL;
  }

  @Override
  public void run() {
    Config config = Configurator.getInstance().getConfig();
    boolean useNativeEpoll = config.hasPath(CFG_SERVER_USE_EPOLL) && config.getBoolean(CFG_SERVER_USE_EPOLL);

    Class<? extends ServerSocketChannel> channelClass;
    if (useNativeEpoll) {
      bossGroup = new EpollEventLoopGroup(1);
      workerGroup = new EpollEventLoopGroup();
      channelClass = EpollServerSocketChannel.class;

    } else {
      bossGroup = new NioEventLoopGroup(1);
      workerGroup = new NioEventLoopGroup();
      channelClass = NioServerSocketChannel.class;
    }

    try {
      setupSSLContext();

      ServerBootstrap b = new ServerBootstrap();
      b.option(ChannelOption.SO_BACKLOG, 1024)
          .group(bossGroup, workerGroup)
          .channel(channelClass)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childHandler(new LaputaServerInitializer(
              sslContext,
              getCorsConfig(config),
              getWebSocketPath(config),
              requestProcessor
          ));

      Channel ch = b.bind(port).sync().channel();

      String ip = HostUtils.getIP();
      LOGGER.info("Service started {}{}:{}", (useSSL ? "https://" : "http://"), ip, port);

      ch.closeFuture().sync();

    } catch (IllegalConfigException e) {
      LOGGER.error(e.getMessage());

    } catch (IOException e) {
      LOGGER.error(e.getMessage() + ", port: " + port, e);

    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);

    } finally {
      shutdown();
    }
  }

  private void setupSSLContext() throws CertificateException, SSLException {
    if (useSSL) {
      File certificate = new File(getConfigAsString("server.https.certificate"));
      assertFileExist(certificate, "Failed to find certificate.");

      File privateKey = new File(getConfigAsString("server.https.privateKey"));
      assertFileExist(privateKey, "Failed to find private key.");
      sslContext = SslContextBuilder.forServer(certificate, privateKey).build();
    }
  }

  private String getConfigAsString(String key) {
    try {
      return Configurator.getInstance().getConfig().getString(key);
    } catch (Exception e) {
      throw new IllegalConfigException(e.getMessage());
    }
  }

  private void assertFileExist(File file, String message) {
    if (!file.exists()) {
      throw new IllegalArgumentException(message + " Path: " + file.getAbsolutePath());
    }
  }

  private CorsConfig getCorsConfig(Config config) {
    if (!config.hasPath(CFG_SERVER_CORS_ALLOWS_ORIGINS)) {
      return null;
    }

    List<String> origins = config.getStringList(CFG_SERVER_CORS_ALLOWS_ORIGINS);
    if (null == origins || origins.isEmpty()) {
      return null;
    }

    CorsConfigBuilder builder = null;
    for (String origin : origins) {
      if (SIGN_STAR.equals(origin)) {
        builder = CorsConfigBuilder.forAnyOrigin();
        break;
      }
    }

    if (null == builder) {
      builder = CorsConfigBuilder.forOrigins(origins.toArray(new String[origins.size()]));
    }

    if (config.hasPath(CFG_SERVER_CORS_ALLOWS_CREDENTIALS)) {
      boolean allowCredentials = config.getBoolean(CFG_SERVER_CORS_ALLOWS_CREDENTIALS);
      if (allowCredentials) {
        builder.allowCredentials();
      }
    }

    return builder.build();
  }

  private String getWebSocketPath(Config config) {
    return config.getString(CFG_WEBSOCKET_PATH);
  }

  private void shutdown() {
    LOGGER.warn("shutting down {}...", useSSL ? "https" : "http");
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
  }
}
