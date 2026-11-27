package dev.chatconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Smoke: the context starts with the shipped config (catches Boot/Cloud train mismatches). */
@SpringBootTest
class AuthServiceApplicationTest {

    @Autowired
    ApplicationContext context;

    @Value("${spring.application.name}")
    String applicationName;

    @Value("${server.port}")
    int serverPort;

    @Test
    void contextLoadsWithServiceIdentity() {
        assertThat(context).isNotNull();
        assertThat(applicationName).isEqualTo("auth-service");
        assertThat(serverPort).isEqualTo(8081);
    }
}
