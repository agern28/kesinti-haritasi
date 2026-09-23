package tr.kesintiharitasi.api.outage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/outages")
@Tag(name = "Kesintiler")
public class OutageController {

    static final int MAX_SIZE = 500;
    /** Derin sayfalama: offset buyudukce sorgu yavasliyor, bu siniri gecen istek 400 aliyor. */
    static final long MAX_OFFSET = 50_000;

    private final OutageRepository repository;

    public OutageController(OutageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Kesinti listesi", description = "En yeni baslangic once. il/ilce Turkce karakter ve buyuk/kucuk harf farki gozetmez.")
    public PageResponse<Outage> list(
            @Parameter(description = "ELECTRICITY, WATER, GAS") @RequestParam(required = false) OutageType type,
            @Parameter(description = "BEDAS, AEDAS, CEDAS, KCETAS, IZSU, ISKI") @RequestParam(required = false) String source,
            @Parameter(example = "İstanbul") @RequestParam(required = false) String il,
            @Parameter(example = "Esenler") @RequestParam(required = false) String ilce,
            @Parameter(description = "true: su an suren, false: bitmis ya da baslamamis") @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "En fazla 500") @RequestParam(defaultValue = "100") int size) {
        if (page < 0 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 0 ve size >= 1 olmali");
        }
        if ((long) page * Math.min(size, MAX_SIZE) > MAX_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cok derin sayfa: page * size en fazla " + MAX_OFFSET + " olabilir");
        }
        return repository.search(new OutageRepository.Query(type, source, il, ilce, active), page,
                Math.min(size, MAX_SIZE));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Tek kesinti")
    public ResponseEntity<Outage> get(@PathVariable UUID id) {
        return ResponseEntity.of(repository.findById(id));
    }
}
