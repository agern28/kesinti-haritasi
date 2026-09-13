package tr.kesintiharitasi.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Tum testlerin paylastigi PostgreSQL ve Redis container'lari (Testcontainers). */
public final class Containers {

    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("postgres:18-alpine"))
                    .withEnv("POSTGRES_DB", "kesinti")
                    .withEnv("POSTGRES_USER", "kesinti")
                    .withEnv("POSTGRES_PASSWORD", "test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private Containers() {
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/kesinti";
    }

    public static void register(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers::jdbcUrl);
        r.add("spring.datasource.username", () -> "kesinti");
        r.add("spring.datasource.password", () -> "test");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** Ayni JVM'de ikinci bir api ornegi baslatmak icin. */
    public static String[] properties() {
        return new String[] {
            "spring.datasource.url=" + jdbcUrl(),
            "spring.datasource.username=kesinti",
            "spring.datasource.password=test",
            "spring.data.redis.host=" + REDIS.getHost(),
            "spring.data.redis.port=" + REDIS.getMappedPort(6379)
        };
    }
}
