package tr.kesintiharitasi.api.ingest;

/** Olay bozuk (eksik alan, bilinmeyen tur). Tekrar denemek ise yaramaz; onaylanip atlanir. */
public class BadEventException extends RuntimeException {

    public BadEventException(String message) {
        super(message);
    }
}
