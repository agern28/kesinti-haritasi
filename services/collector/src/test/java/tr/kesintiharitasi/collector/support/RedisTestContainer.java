package tr.kesintiharitasi.collector.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Tum testlerin paylastigi tek Redis container'i (Testcontainers). */
public final class RedisTestContainer {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(6379);
    }
}
