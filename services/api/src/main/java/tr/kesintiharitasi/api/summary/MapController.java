package tr.kesintiharitasi.api.summary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Harita")
public class MapController {

    public record MapSummary(Instant generatedAt, List<DistrictSummary> districts) {
    }

    private final SummaryCache cache;
    private final Clock clock;

    public MapController(SummaryCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    @GetMapping("/api/map/summary")
    @Operation(summary = "Ilce bazinda aktif kesinti sayilari",
            description = "Harita renklendirme icin. Redis'te cache'li; bir kesinti degisince ilgili ilce aninda guncellenir.")
    public MapSummary summary() {
        return new MapSummary(clock.instant(), cache.all());
    }
}
