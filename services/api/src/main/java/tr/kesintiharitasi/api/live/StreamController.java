package tr.kesintiharitasi.api.live;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "Canli")
public class StreamController {

    private final SseHub hub;

    public StreamController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Canli kesinti olaylari (SSE)",
            description = "Olaylar: outage.created, outage.updated, outage.ended, outage.resync. "
                    + "Koparsa tarayici Last-Event-ID ile yeniden baglanir ve kacirdiklarini alir. "
                    + "Swagger UI SSE akisini gosteremez; /canli.html sayfasini kullanin.")
    public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
                             @RequestParam(name = "lastEventId", required = false) String lastEventIdParam,
                             HttpServletResponse response) {
        // nginx gibi proxy'ler olaylari tamponlamasin
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return hub.connect(lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam);
    }
}
