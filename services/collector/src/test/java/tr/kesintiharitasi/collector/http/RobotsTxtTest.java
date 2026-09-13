package tr.kesintiharitasi.collector.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Keşifte gordugumuz gercek robots.txt icerikleriyle. */
class RobotsTxtTest {

    private static RobotsTxt parse(String s) {
        return RobotsTxt.parse(s, "KesintiHaritasi");
    }

    @Test
    void tamYasak() {
        RobotsTxt r = parse("User-agent: *\nDisallow: / \n");
        assertThat(r.allows("/")).isFalse();
        assertThat(r.allows("/kesinti")).isFalse();
        assertThat(r.allows("/robots.txt")).isTrue();
    }

    @Test
    void izsuApiYasakSayfaIzinli() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /api/\nDisallow: /icons/\n");
        assertThat(r.allows("/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama")).isTrue();
        assertThat(r.allows("/api/kesintiler")).isFalse();
    }

    @Test
    void ibbJokerVeCrawlDelay() {
        RobotsTxt r = parse("""
                User-agent: *
                Disallow: /dataset/rate/
                Disallow: /revision/
                Disallow: /dataset/*/history
                Disallow: /api/
                Crawl-Delay: 10
                """);
        assertThat(r.allows("/dataset/istanbul-da-meydana-gelen-su-kesintileri")).isTrue();
        assertThat(r.allows("/dataset/abc/history")).isFalse();
        assertThat(r.allows("/dataset/9bc/resource/b41/download/dosya.xlsx")).isTrue();
        assertThat(r.allows("/api/3/action/package_show?id=x")).isFalse();
        assertThat(r.crawlDelay()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void dolarIsaretiSonaBaglar() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /*.xls$\n");
        assertThat(r.allows("/rapor/tablo.xls")).isFalse();
        assertThat(r.allows("/rapor/tablo.xlsx")).isTrue();
    }

    @Test
    void enUzunKuralKazanirEsitlikteAllow() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /a\nAllow: /a/b\nDisallow: /c\nAllow: /c\n");
        assertThat(r.allows("/a/b/c")).isTrue();
        assertThat(r.allows("/a/c")).isFalse();
        assertThat(r.allows("/c")).isTrue();
    }

    @Test
    void kendiGrubumuzVarsaYildizGrubuKullanilmaz() {
        RobotsTxt r = parse("User-agent: *\nDisallow: /\n\nUser-agent: KesintiHaritasi\nAllow: /\n");
        assertThat(r.allows("/herhangi")).isTrue();
    }

    @Test
    void bosDisallowKisitlamaz() {
        RobotsTxt r = parse("# https://www.robotstxt.org/robotstxt.html\nUser-agent: *\nDisallow:\n");
        assertThat(r.allows("/")).isTrue();
    }

    @Test
    void htmlGelirseKuralYok() {
        assertThat(parse("<!doctype html><html><body>404</body></html>").allows("/x")).isTrue();
    }

    @Test
    void erisilemeyenRobotsTamYasak() {
        assertThat(RobotsTxt.disallowAll().allows("/")).isFalse();
    }
}
