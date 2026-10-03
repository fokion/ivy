package xyz.fokion.ivy.core.util;

import java.text.Normalizer;

/**
 * Test case slugs ({@code gosimple/slug} with case preserved): "&amp;" becomes "and",
 * "@" "at", accents are removed and other characters become dashes.
 * <p>
 * Unlike unidecode, scripts without a Latin decomposition are not transliterated.
 */
public final class Slug {

    private Slug() {
    }

    public static String make(String s) {
        String slug = GoStrings.trimSpace(s);
        StringBuilder sb = new StringBuilder();
        slug.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> sb.append("and");
                case '@' -> sb.append("at");
                case '"', '\'', '’' -> {
                }
                case '‒', '–', '—', '―' -> sb.append('-');
                default -> sb.appendCodePoint(c);
            }
        });
        slug = Normalizer.normalize(sb, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        slug = slug.replaceAll("[^a-zA-Z0-9\\-_]", "-");
        slug = slug.replaceAll("-+", "-");
        return GoStrings.trim(slug, "-_");
    }
}
