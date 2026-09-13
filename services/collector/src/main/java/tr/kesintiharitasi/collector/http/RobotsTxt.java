package tr.kesintiharitasi.collector.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * robots.txt ayrıştırıcı (RFC 9309).
 * - Kendi urun adimizla (kesintiharitasi) eslesen grup varsa o, yoksa "*" grubu kullanilir.
 * - Allow/Disallow desenlerinde * ve $ desteklenir, en uzun eslesen kural kazanir, esitlikte Allow.
 * - Crawl-delay standart disi ama uyuyoruz.
 */
public final class RobotsTxt {

    private record Rule(boolean allow, String pattern, Pattern regex) {
    }

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        Duration delay;
    }

    private static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of(), null, false);
    private static final RobotsTxt DISALLOW_ALL = new RobotsTxt(List.of(), null, true);

    private final List<Rule> rules;
    private final Duration crawlDelay;
    private final boolean disallowAll;

    private RobotsTxt(List<Rule> rules, Duration crawlDelay, boolean disallowAll) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
        this.disallowAll = disallowAll;
    }

    public static RobotsTxt allowAll() {
        return ALLOW_ALL;
    }

    /** robots.txt'e ulasilamadiginda (5xx, baglanti hatasi) RFC 9309 tam yasak der. */
    public static RobotsTxt disallowAll() {
        return DISALLOW_ALL;
    }

    public static RobotsTxt parse(String content, String productToken) {
        String token = productToken.toLowerCase(Locale.ROOT);
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean lastWasAgent = false;
        for (String rawLine : content.split("\\r?\\n|\\r")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "user-agent" -> {
                    if (current == null || !lastWasAgent) {
                        current = new Group();
                        groups.add(current);
                    }
                    current.agents.add(value.toLowerCase(Locale.ROOT));
                    lastWasAgent = true;
                }
                case "allow", "disallow" -> {
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(rule("allow".equals(key), value));
                    }
                    lastWasAgent = false;
                }
                case "crawl-delay" -> {
                    if (current != null) {
                        current.delay = parseDelay(value);
                    }
                    lastWasAgent = false;
                }
                default -> {
                    // sitemap vb. satirlar gruplari etkilemez
                }
            }
        }

        List<Group> selected = groups.stream().filter(g -> g.agents.contains(token)).toList();
        if (selected.isEmpty()) {
            selected = groups.stream().filter(g -> g.agents.contains("*")).toList();
        }
        List<Rule> rules = new ArrayList<>();
        Duration delay = null;
        for (Group g : selected) {
            rules.addAll(g.rules);
            if (g.delay != null && (delay == null || g.delay.compareTo(delay) > 0)) {
                delay = g.delay;
            }
        }
        return new RobotsTxt(List.copyOf(rules), delay, false);
    }

    /** @param pathAndQuery "/yol?sorgu" bicimi */
    public boolean allows(String pathAndQuery) {
        if (disallowAll) {
            return false;
        }
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        if ("/robots.txt".equals(path)) {
            return true;
        }
        Rule best = null;
        for (Rule r : rules) {
            Matcher m = r.regex().matcher(path);
            if (m.lookingAt()) {
                if (best == null
                        || r.pattern().length() > best.pattern().length()
                        || (r.pattern().length() == best.pattern().length() && r.allow())) {
                    best = r;
                }
            }
        }
        return best == null || best.allow();
    }

    /** Crawl-delay yoksa null. */
    public Duration crawlDelay() {
        return crawlDelay;
    }

    private static Rule rule(boolean allow, String pattern) {
        boolean anchored = pattern.endsWith("$");
        String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder("^");
        for (String part : body.split("\\*", -1)) {
            if (regex.length() > 1) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        if (anchored) {
            regex.append('$');
        }
        return new Rule(allow, pattern, Pattern.compile(regex.toString()));
    }

    private static Duration parseDelay(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return seconds > 0 ? Duration.ofMillis((long) (seconds * 1000)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
