package example.rabbitmq.sourcechanger.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Registers the workspace WebSocket endpoint. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * Messages may carry a whole file (up to the storage read cap), so the
     * default 8 KB text buffer would make the server drop the connection.
     */
    public static final int MAX_TEXT_MESSAGE_BYTES = 5 * 1024 * 1024;

    private final WorkspaceWebSocketHandler workspaceWebSocketHandler;

    public WebSocketConfig(WorkspaceWebSocketHandler workspaceWebSocketHandler) {
        this.workspaceWebSocketHandler = workspaceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(workspaceWebSocketHandler, "/ws/workspace").setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        return container;
    }
}
