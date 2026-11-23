package com.helpdesk;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Real infrastructure in throwaway Docker containers: Postgres, RabbitMQ (with its STOMP
 * plugin, for the WebSocket relay) and Mailpit (the mail server). No mocks of any of them,
 * so migrations, triggers, grants, SKIP LOCKED, publisher confirms and SMTP are all tested
 * for real. Image tags are pinned so tests don't break when "latest" moves.
 *
 * <p>Like production, Flyway runs as the schema owner (the container's superuser here) and
 * the application as {@code helpdesk_app}, with only the privileges the migrations grant.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_ROLE = "helpdesk_app";
    static final String APP_PASSWORD = "helpdesk-app-test";
    static final String RABBIT_USER = "helpdesk";
    static final String RABBIT_PASSWORD = "rabbit-test-secret";
    static final int STOMP_PORT = 61613;

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("helpdesk")
                .withInitScript("db/test-roles.sql");
    }

    @Bean
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3-alpine"))
                .withAdminUser(RABBIT_USER)
                .withAdminPassword(RABBIT_PASSWORD)
                .withCopyFileToContainer(MountableFile.forClasspathResource("rabbitmq/enabled_plugins"),
                        "/etc/rabbitmq/enabled_plugins")
                .withExposedPorts(5672, STOMP_PORT);
    }

    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.33"))
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forListeningPorts(1025, 8025));
    }

    @Bean
    DynamicPropertyRegistrar containerProperties(PostgreSQLContainer postgres, RabbitMQContainer rabbit,
            GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=helpdesk");
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.appRole", () -> APP_ROLE);

            registry.add("spring.rabbitmq.host", rabbit::getHost);
            registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
            registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
            registry.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
            registry.add("helpdesk.stomp-relay.host", rabbit::getHost);
            registry.add("helpdesk.stomp-relay.port", () -> rabbit.getMappedPort(STOMP_PORT));
            registry.add("helpdesk.stomp-relay.login", () -> RABBIT_USER);
            registry.add("helpdesk.stomp-relay.passcode", () -> RABBIT_PASSWORD);

            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(1025));
            registry.add("helpdesk.mail.inbound.mailpit-url",
                    () -> "http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(8025));
        };
    }
}
