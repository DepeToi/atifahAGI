package com.atifah.gateway.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动完成后把 root appender 包装为 AsyncAppender，避免在 Netty 事件循环同步写文件。
 *
 * <p>队列满时丢弃而非阻塞（{@code neverBlock}），保证日志不会拖慢请求；
 * 关闭丢弃阈值（0）以确保 WARN / ERROR 不被提前丢弃。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "atifah.logging.async", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AsyncLoggingConfigurer implements ApplicationListener<ApplicationReadyEvent> {

    private static final String ASYNC_APPENDER_NAME = "ATIFAH_ASYNC";

    private final GatewayAsyncLogProperties properties;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            install(context);
        }
    }

    /**
     * 把指定上下文中的 root appender 包装为异步，供启动事件与测试复用。
     *
     * @param context Logback 上下文
     */
    void install(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (hasAsyncAppender(root)) {
            return;
        }
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        root.iteratorForAppenders().forEachRemaining(appenders::add);
        if (appenders.isEmpty()) {
            return;
        }
        int index = 0;
        for (Appender<ILoggingEvent> appender : appenders) {
            index++;
            AsyncAppender async = new AsyncAppender();
            async.setName(ASYNC_APPENDER_NAME + "-" + index);
            async.setContext(context);
            async.setQueueSize(Math.max(256, properties.getQueueSize()));
            async.setDiscardingThreshold(0);
            async.setNeverBlock(true);
            async.setIncludeCallerData(false);
            async.addAppender(appender);
            async.start();
            root.detachAppender(appender);
            root.addAppender(async);
        }
        log.info("event=async_logging_enabled appenders={} queueSize={}",
                appenders.size(), Math.max(256, properties.getQueueSize()));
    }

    /**
     * 判断 root 是否已挂载异步 appender，避免重复包装。
     *
     * @param root root logger
     * @return 是否已存在
     */
    private boolean hasAsyncAppender(Logger root) {
        var iterator = root.iteratorForAppenders();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof AsyncAppender) {
                return true;
            }
        }
        return false;
    }
}
