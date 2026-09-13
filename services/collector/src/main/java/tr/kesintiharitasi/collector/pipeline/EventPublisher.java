package tr.kesintiharitasi.collector.pipeline;

import java.util.List;

public interface EventPublisher {

    void publish(List<OutageEvent> events);
}
