package com.canvashome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where {@link ArtStore} finds paintings. All four are open, need no key, and give public-domain
 * images. Only works with a caption are kept (a sentence from the museum or from Wikipedia):
 *
 *   aic  Art Institute of Chicago: highlighted ("boosted") public-domain paintings, IIIF images at
 *        3000 px wide, the largest the museum serves, so a landscape fills the 4K mat sharply
 *   met  The Met: highlighted paintings (search v1.1; v1 was retired in October 2026). Each one's
 *        record is read when it is about to be saved, and only public-domain ones are kept. The Met
 *        has no descriptions, so the caption comes from the work's English Wikipedia article, found
 *        through the record's Wikidata link
 *   cma  Cleveland Museum of Art: highlighted CC0 paintings, with the museum's "did you know" line
 *   wd   Wikidata: paintings made before 1930 with articles in at least 20 Wikipedias (and one in
 *        English), most famous first; images from Wikimedia Commons (3840 px, or the original if
 *        smaller), captions from its summary
 */
final class ArtSources {
    private ArtSources() {
    }

    static List<ArtStore.Source> all() {
        return Arrays.asList(new Chicago(), new Met(), new Cleveland(), new Wikidata());
    }

    static final class Chicago implements ArtStore.Source {
        public String key() {
            return "aic";
        }

        public String name() {
            return "Art Institute of Chicago";
        }

        public List<ArtStore.Work> list() throws Exception {
            JSONArray filter = new JSONArray()
                    .put(term("is_public_domain", true))
                    .put(term("is_boosted", true))
                    .put(term("classification_titles.keyword", "painting"))
                    .put(new JSONObject().put("exists", new JSONObject().put("field", "image_id")));
            JSONArray fields = new JSONArray();
            for (String s : new String[] {"id", "title", "artist_title", "date_display", "image_id",
                    "short_description", "description", "subject_titles", "term_titles"}) {
                fields.put(s);
            }
            List<ArtStore.Work> out = new ArrayList<ArtStore.Work>();
            for (int page = 1; page <= 5; page++) {
                JSONObject body = new JSONObject()
                        .put("query", new JSONObject().put("bool", new JSONObject().put("filter", filter)))
                        .put("fields", fields).put("limit", 100).put("page", page);
                // The museum asks API users to identify themselves with this header.
                JSONObject r = new JSONObject(ArtStore.fetch("https://api.artic.edu/api/v1/artworks/search",
                        body.toString(), ArtStore.headers("Content-Type", "application/json",
                                "AIC-User-Agent", ArtStore.USER_AGENT), 30000));
                JSONArray data = r.getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject a = data.getJSONObject(i);
                    String image = ArtStore.string(a, "image_id");
                    String title = ArtStore.clean(ArtStore.string(a, "title"));
                    // The short description when there is one, else the first sentence of the long one.
                    String about = ArtStore.clean(ArtStore.string(a, "short_description"));
                    if (about.length() == 0) about = ArtStore.clean(ArtStore.string(a, "description"));
                    List<String> tags = strings(a.optJSONArray("subject_titles"));
                    tags.addAll(strings(a.optJSONArray("term_titles")));
                    if (image == null || about.length() == 0 || ArtStore.violent(title + " " + about, tags)) continue;
                    out.add(new ArtStore.Work("aic:" + a.getInt("id"),
                            "https://www.artic.edu/iiif/2/" + image + "/full/3000,/0/default.jpg", title,
                            credit(ArtStore.string(a, "artist_title"), ArtStore.string(a, "date_display")),
                            ArtStore.firstSentence(about), null));
                }
                if (page >= r.getJSONObject("pagination").optInt("total_pages", 1)) break;
            }
            return out;
        }

        public ArtStore.Work resolve(ArtStore.Work w) {
            return w;
        }
    }

    static final class Met implements ArtStore.Source {
        private static final String API = "https://collectionapi.metmuseum.org/public/collection/";

        public String key() {
            return "met";
        }

        public String name() {
            return "The Met";
        }

        public List<ArtStore.Work> list() throws Exception {
            List<ArtStore.Work> out = new ArrayList<ArtStore.Work>();
            for (int offset = 0; offset < 2000; offset += 100) {
                JSONObject r = new JSONObject(ArtStore.fetch(API + "v1.1/search?isHighlight=true&hasImages=true"
                        + "&medium=Paintings&q=*&limit=100&offset=" + offset, null, null, 30000));
                JSONArray ids = r.optJSONArray("objectIDs");
                if (ids == null || ids.length() == 0) break;
                for (int i = 0; i < ids.length(); i++) {
                    String id = String.valueOf(ids.getInt(i));
                    out.add(new ArtStore.Work("met:" + id, null, null, null, null, id));
                }
                if (offset + 100 >= r.optInt("total", 0)) break;
            }
            return out;
        }

        public ArtStore.Work resolve(ArtStore.Work w) throws Exception {
            JSONObject a = new JSONObject(ArtStore.fetch(API + "v1/objects/" + w.extra, null, null, 30000));
            String image = ArtStore.string(a, "primaryImage");
            if (!a.optBoolean("isPublicDomain") || image == null || image.length() == 0) return null;
            List<String> tags = new ArrayList<String>();
            JSONArray t = a.optJSONArray("tags");
            if (t != null) for (int i = 0; i < t.length(); i++) tags.add(t.getJSONObject(i).optString("term"));
            String title = ArtStore.clean(ArtStore.string(a, "title"));
            if (ArtStore.violent(title, tags)) return null;
            String wikidata = ArtStore.string(a, "objectWikidata_URL");
            if (wikidata == null || wikidata.lastIndexOf('/') < 0) return null;
            String article = englishArticle(wikidata.substring(wikidata.lastIndexOf('/') + 1));
            String extract = article == null ? null : summary(article);
            if (extract == null || ArtStore.violent(extract, null)) return null;
            // The original (often 4000 px and several MB) is shrunk on the TV; "web-large" is only 600 px.
            return new ArtStore.Work(w.id, image, title,
                    credit(ArtStore.string(a, "artistDisplayName"), ArtStore.string(a, "objectDate")),
                    ArtStore.informativeSentence(extract), w.extra);
        }

        /** The English Wikipedia article about a Wikidata item, or null if there is none. */
        private static String englishArticle(String qid) throws Exception {
            JSONObject e = new JSONObject(ArtStore.fetch("https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                    + URLEncoder.encode(qid, "UTF-8") + "&props=sitelinks&sitefilter=enwiki&format=json", null, null, 20000))
                    .getJSONObject("entities").optJSONObject(qid);
            JSONObject links = e == null ? null : e.optJSONObject("sitelinks");
            JSONObject en = links == null ? null : links.optJSONObject("enwiki");
            String title = en == null ? null : ArtStore.string(en, "title");
            return title == null ? null : URLEncoder.encode(title.replace(' ', '_'), "UTF-8");
        }
    }

    static final class Cleveland implements ArtStore.Source {
        public String key() {
            return "cma";
        }

        public String name() {
            return "Cleveland Museum of Art";
        }

        public List<ArtStore.Work> list() throws Exception {
            JSONObject r = new JSONObject(ArtStore.fetch("https://openaccess-api.clevelandart.org/api/artworks/"
                    + "?cc0=1&has_image=1&type=Painting&highlight=1&limit=1000"
                    + "&fields=id,title,creators,creation_date,images,did_you_know,description", null, null, 30000));
            JSONArray data = r.getJSONArray("data");
            List<ArtStore.Work> out = new ArrayList<ArtStore.Work>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i);
                JSONObject images = a.optJSONObject("images");
                JSONObject print = images == null ? null : images.optJSONObject("print");
                String image = print == null ? null : ArtStore.string(print, "url");
                String title = ArtStore.clean(ArtStore.string(a, "title"));
                String fact = ArtStore.clean(ArtStore.string(a, "did_you_know"));
                String about = ArtStore.clean(ArtStore.string(a, "description"));
                String caption = fact.length() > 0 ? ArtStore.firstSentence(fact) : ArtStore.firstSentence(about);
                if (image == null || caption.length() == 0 || ArtStore.violent(title + " " + fact + " " + about, null)) continue;
                // "George Bellows (American, 1882–1925)" becomes "George Bellows".
                JSONArray creators = a.optJSONArray("creators");
                String artist = creators == null || creators.length() == 0 ? null
                        : ArtStore.string(creators.getJSONObject(0), "description");
                if (artist != null && artist.contains(" (")) artist = artist.substring(0, artist.indexOf(" ("));
                out.add(new ArtStore.Work("cma:" + a.get("id"), image, title,
                        credit(artist, ArtStore.string(a, "creation_date")), caption, null));
            }
            return out;
        }

        public ArtStore.Work resolve(ArtStore.Work w) {
            return w;
        }
    }

    static final class Wikidata implements ArtStore.Source {
        private static final String QUERY = "SELECT ?item (SAMPLE(?l) AS ?title) (SAMPLE(?cl) AS ?creator)"
                + " (MIN(YEAR(?inc)) AS ?year) (SAMPLE(?img) AS ?image) (SAMPLE(?n) AS ?links)"
                + " (SAMPLE(?art) AS ?article) (GROUP_CONCAT(DISTINCT ?dl; separator=\"|\") AS ?depicts) WHERE {"
                + " ?item wdt:P31 wd:Q3305213; wdt:P18 ?img; wdt:P571 ?inc; wikibase:sitelinks ?n. FILTER(?n >= 20)"
                + " ?item rdfs:label ?l. FILTER(LANG(?l) = \"en\")"
                + " OPTIONAL { ?item wdt:P170 ?c. ?c rdfs:label ?cl. FILTER(LANG(?cl) = \"en\") }"
                + " OPTIONAL { ?art schema:about ?item; schema:isPartOf <https://en.wikipedia.org/> }"
                + " OPTIONAL { ?item wdt:P180 ?d. ?d rdfs:label ?dl. FILTER(LANG(?dl) = \"en\") }"
                + " } GROUP BY ?item HAVING (MIN(YEAR(?inc)) < 1930) ORDER BY DESC(?links) LIMIT 600";

        public String key() {
            return "wd";
        }

        public String name() {
            return "Wikipedia";
        }

        public List<ArtStore.Work> list() throws Exception {
            JSONArray rows = new JSONObject(ArtStore.fetch("https://query.wikidata.org/sparql?query="
                    + URLEncoder.encode(QUERY, "UTF-8"), null,
                    ArtStore.headers("Accept", "application/sparql-results+json"), 40000))
                    .getJSONObject("results").getJSONArray("bindings");
            List<ArtStore.Work> out = new ArrayList<ArtStore.Work>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                String item = value(r, "item");
                String title = value(r, "title");
                String image = value(r, "image");
                if (item == null || title == null || image == null) continue;
                List<String> depicts = new ArrayList<String>();
                String d = value(r, "depicts");
                if (d != null) depicts.addAll(Arrays.asList(d.split("\\|")));
                String article = value(r, "article");
                if (article == null || ArtStore.violent(title, depicts)) continue;
                out.add(new ArtStore.Work("wd:" + item.substring(item.lastIndexOf('/') + 1),
                        image.replace("http://", "https://") + "?width=3840", title,
                        credit(value(r, "creator"), value(r, "year")), null,
                        article.substring(article.lastIndexOf('/') + 1)));
            }
            return out;
        }

        /** The caption comes from the article's summary, which is checked against the filter as a whole. */
        public ArtStore.Work resolve(ArtStore.Work w) throws Exception {
            String extract = summary(w.extra);
            if (extract == null || ArtStore.violent(extract, null)) return null;
            return new ArtStore.Work(w.id, w.image, w.title, w.artist, ArtStore.informativeSentence(extract), w.extra);
        }

        private static String value(JSONObject row, String key) {
            JSONObject v = row.optJSONObject(key);
            return v == null ? null : ArtStore.string(v, "value");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The summary paragraph of an English Wikipedia article (its title as in the URL), or null. */
    private static String summary(String article) throws Exception {
        if (article == null || article.length() == 0) return null;
        String extract = ArtStore.string(new JSONObject(ArtStore.fetch(
                "https://en.wikipedia.org/api/rest_v1/page/summary/" + article, null, null, 20000)), "extract");
        return extract == null || extract.trim().length() == 0 ? null : extract;
    }

    private static JSONObject term(String field, Object value) throws Exception {
        return new JSONObject().put("term", new JSONObject().put(field, value));
    }

    private static List<String> strings(JSONArray a) throws Exception {
        List<String> out = new ArrayList<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    /** "Claude Monet, 1906", or whichever half is known. */
    private static String credit(String artist, String date) {
        boolean a = artist != null && artist.trim().length() > 0;
        boolean d = date != null && date.trim().length() > 0;
        if (a && d) return artist.trim() + ", " + date.trim();
        return a ? artist.trim() : d ? date.trim() : "";
    }
}
