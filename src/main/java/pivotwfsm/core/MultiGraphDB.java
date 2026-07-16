package pivotwfsm.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A database of weighted multigraphs (transactional setting). Loaded from
 * the project's common JSON format documented in {@code data/sample/README.md}.
 *
 * <p>Schema (abridged):
 * <pre>
 * {
 *   "name":          "illustrative",
 *   "label_strings": ["A", "B", "C", "D"],         // optional
 *   "graphs": [
 *     { "id": 0,
 *       "vertices": [ {"id": 0, "label": 0}, ... ],
 *       "edges":    [ {"src": 0, "dst": 1, "weight": 0.85}, ... ] }
 *   ]
 * }
 * </pre>
 *
 * <p>The loader accepts multiple edge entries with the same {@code (src,dst)}
 * pair - that is the multigraph case.
 */
public final class MultiGraphDB {

    private final String name;
    private final List<String> labelStrings;
    private final List<MultiGraph> graphs;

    private MultiGraphDB(String name, List<String> labelStrings, List<MultiGraph> graphs) {
        this.name = name;
        this.labelStrings = Collections.unmodifiableList(labelStrings);
        this.graphs = Collections.unmodifiableList(graphs);
    }

    public String name() {
        return name;
    }

    /** Returns the int → string mapping for vertex labels, or empty if none was provided. */
    public List<String> labelStrings() {
        return labelStrings;
    }

    public int size() {
        return graphs.size();
    }

    public MultiGraph get(int index) {
        return graphs.get(index);
    }

    public List<MultiGraph> graphs() {
        return graphs;
    }

    /**
     * Load and validate a multigraph database from a JSON file.
     *
     * <p>Parsed with Jackson's streaming API (token pull) rather than a full DOM
     * tree: each graph is materialised into compact {@code int[]} arrays and the
     * source tokens are discarded as we advance. Peak transient memory during
     * load is therefore one graph plus the accumulated {@link MultiGraph}
     * arrays ({@code O(M)}), not a whole in-memory JSON tree - which is what lets
     * the loader ingest the large molecular databases (tens of thousands of
     * graphs, hundreds of MB) without a multi-GB parse spike. Validation is
     * identical to the previous DOM loader.
     */
    public static MultiGraphDB loadJson(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        String name = "";
        List<String> labelStrings = new ArrayList<>();
        List<MultiGraph> graphs = new ArrayList<>();

        JsonFactory factory = new JsonFactory();
        try (InputStream in = Files.newInputStream(file);
             JsonParser p = factory.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Top-level JSON must be an object (at " + file + ")");
            }
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken(); // advance to the field value
                switch (field) {
                    case "name" -> name = p.getValueAsString("");
                    case "label_strings" -> {
                        expectToken(p, JsonToken.START_ARRAY, file, "label_strings");
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            labelStrings.add(p.getValueAsString());
                        }
                    }
                    case "graphs" -> {
                        expectToken(p, JsonToken.START_ARRAY, file, "graphs");
                        int gi = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            graphs.add(parseGraph(p, gi, file));
                            gi++;
                        }
                    }
                    default -> p.skipChildren(); // ignore unknown top-level fields
                }
            }
        } catch (IOException e) {
            throw new IOException("Failed to read JSON at " + file, e);
        }

        if (graphs.isEmpty()) {
            // Mirror the DOM loader, which required a 'graphs' array to exist.
            throw new IOException("Top-level 'graphs' must be a non-empty array (at " + file + ")");
        }
        return new MultiGraphDB(name, labelStrings, graphs);
    }

    /** Parse one graph object; {@code p} is positioned at its START_OBJECT. */
    private static MultiGraph parseGraph(JsonParser p, int gi, Path file) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("graph[" + gi + "] must be an object (at " + file + ")");
        }
        int id = gi;
        int[] vertexLabels = null;
        int[] edgeSrc = new int[4];
        int[] edgeDst = new int[4];
        double[] edgeWeight = new double[4];
        int m = 0;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "id" -> id = p.getIntValue();
                case "vertices" -> {
                    expectToken(p, JsonToken.START_ARRAY, file, "graph[" + gi + "].vertices");
                    int[] labels = new int[8];
                    int n = 0;
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        int vid = n;      // default: positional id (matches DOM asInt(v))
                        int lbl = -1;
                        while (p.nextToken() != JsonToken.END_OBJECT) {
                            String vf = p.currentName();
                            p.nextToken();
                            if (vf.equals("id")) {
                                vid = p.getIntValue();
                            } else if (vf.equals("label")) {
                                lbl = p.getIntValue();
                            } else {
                                p.skipChildren();
                            }
                        }
                        if (vid != n) {
                            throw new IOException("graph[" + gi + "].vertices must be in id "
                                + "order; expected id=" + n + " got id=" + vid);
                        }
                        if (lbl < 0) {
                            throw new IOException("graph[" + gi + "].vertices[" + n
                                + "].label must be non-negative");
                        }
                        if (n == labels.length) {
                            labels = Arrays.copyOf(labels, labels.length * 2);
                        }
                        labels[n++] = lbl;
                    }
                    vertexLabels = Arrays.copyOf(labels, n);
                }
                case "edges" -> {
                    expectToken(p, JsonToken.START_ARRAY, file, "graph[" + gi + "].edges");
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        int src = -1;
                        int dst = -1;
                        double w = Double.NaN;
                        while (p.nextToken() != JsonToken.END_OBJECT) {
                            String ef = p.currentName();
                            p.nextToken();
                            switch (ef) {
                                case "src" -> src = p.getIntValue();
                                case "dst" -> dst = p.getIntValue();
                                case "weight" -> w = p.getDoubleValue();
                                default -> p.skipChildren();
                            }
                        }
                        if (src < 0 || dst < 0) {
                            throw new IOException(
                                "graph[" + gi + "].edges[" + m + "] missing src/dst");
                        }
                        if (Double.isNaN(w)) {
                            throw new IOException(
                                "graph[" + gi + "].edges[" + m + "].weight missing or non-numeric");
                        }
                        if (m == edgeSrc.length) {
                            edgeSrc = Arrays.copyOf(edgeSrc, m * 2);
                            edgeDst = Arrays.copyOf(edgeDst, m * 2);
                            edgeWeight = Arrays.copyOf(edgeWeight, m * 2);
                        }
                        edgeSrc[m] = src;
                        edgeDst[m] = dst;
                        edgeWeight[m] = w;
                        m++;
                    }
                }
                default -> p.skipChildren();
            }
        }

        if (vertexLabels == null) {
            throw new IOException("graph[" + gi + "].vertices must be an array");
        }
        return MultiGraph.of(id, vertexLabels,
            Arrays.copyOf(edgeSrc, m), Arrays.copyOf(edgeDst, m), Arrays.copyOf(edgeWeight, m));
    }

    private static void expectToken(JsonParser p, JsonToken want, Path file, String what)
            throws IOException {
        if (p.currentToken() != want) {
            throw new IOException(what + " must be a" + (want == JsonToken.START_ARRAY
                ? "n array" : " " + want) + " (at " + file + ")");
        }
    }

    /** Total edge count across all graphs (handy for sanity assertions in tests). */
    public long totalEdges() {
        long t = 0;
        for (MultiGraph g : graphs) {
            t += g.numEdges();
        }
        return t;
    }

    /** Total vertex count across all graphs. */
    public long totalVertices() {
        long t = 0;
        for (MultiGraph g : graphs) {
            t += g.numVertices();
        }
        return t;
    }
}
