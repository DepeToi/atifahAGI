package com.atifah.gateway.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 异步日志装配测试：每个底层 appender 单独包装为 AsyncAppender，各自持有独立队列与丢弃策略。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class AsyncLoggingConfigurerTest {

    @Test
    void wrapsEachRootAppenderSeparately() {
        LoggerContext context = new LoggerContext();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(mockAppender("CONSOLE"));
        root.addAppender(mockAppender("FILE"));

        GatewayAsyncLogProperties properties = new GatewayAsyncLogProperties();
        new AsyncLoggingConfigurer(properties).install(context);

        List<AsyncAppender> asyncAppenders = new ArrayList<>();
        root.iteratorForAppenders().forEachRemaining(appender -> {
            if (appender instanceof AsyncAppender async) {
                asyncAppenders.add(async);
            }
        });

        assertEquals(2, asyncAppenders.size());
        for (AsyncAppender async : asyncAppenders) {
            int attached = 0;
            var iterator = async.iteratorForAppenders();
            while (iterator.hasNext()) {
                iterator.next();
                attached++;
            }
            assertEquals(1, attached);
        }
        context.stop();
    }

    private Appender<ILoggingEvent> mockAppender(String name) {
        @SuppressWarnings("unchecked")
        Appender<ILoggingEvent> appender = mock(Appender.class);
        when(appender.getName()).thenReturn(name);
        return appender;
    }
}
