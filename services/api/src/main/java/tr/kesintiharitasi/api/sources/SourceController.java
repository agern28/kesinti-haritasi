package tr.kesintiharitasi.api.sources;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Kaynaklar")
public class SourceController {

    private final SourceStatusService service;

    public SourceController(SourceStatusService service) {
        this.service = service;
    }

    @GetMapping("/api/sources")
    @Operation(summary = "Kaynaklarin son basarili tarama zamani",
            description = "Arayuzdeki 'son guncelleme' gostergesi icin. stale=true: kaynak beklenenden uzun suredir taranamadi.")
    public List<SourceStatusService.SourceStatus> sources() {
        return service.list();
    }
}
