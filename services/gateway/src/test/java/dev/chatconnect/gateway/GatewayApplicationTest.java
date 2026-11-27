package dev.chatconnect.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationContext;

/** Smoke: the context starts with the shipped config, including the I14 timeout values and routes. */
@SpringBootTest
class GatewayApplicationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    HttpClientProperties httpClient;

    @Autowired
    GatewayProperties gateway;

    @Value("${spring.application.name}")
    String applicationName;

    @Value("${server.port}")
    int serverPort;

    @Test
    void contextLoadsWithServiceIdentity() {
        assertThat(context).isNotNull();
        assertThat(applicationName).isEqualTo("gateway");
        assertThat(serverPort).isEqualTo(8080);
    }

    @Test
    void downstreamCallsAreBounded() {
        assertThat(httpClient.getConnectTimeout()).isEqualTo(1000);
        assertThat(httpClient.getResponseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(httpClient.getPool().getMaxIdleTime()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void staticRoutesPointAtLocalDefaults() {
        Map<String, RouteDefinition> routes = gateway.getRoutes().stream()
                .collect(Collectors.toMap(RouteDefinition::getId, r -> r));

        assertThat(routes).containsOnlyKeys("auth-service", "chat-service");
        assertThat(routes.get("auth-service").getUri()).isEqualTo(URI.create("http://localhost:8081"));
        assertThat(routes.get("chat-service").getUri()).isEqualTo(URI.create("http://localhost:8082"));
        assertThat(routes.get("auth-service").getPredicates().get(0).getArgs()).containsValue("/api/auth/**");
        assertThat(routes.get("chat-service").getPredicates().get(0).getArgs()).containsValue("/api/chat/**");
        assertThat(routes.values()).allSatisfy(r -> assertThat(r.getFilters()).as("no retry/rewrite filters").isEmpty());
    }
}
