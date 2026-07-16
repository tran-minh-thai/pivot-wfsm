package pivotwfsm.sanity;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the streaming JSON loader on cases a pull-parser can easily get wrong:
 * unknown fields (must be skipped), fields in an unexpected order (edges before
 * vertices; id after them), unknown fields nested inside vertex/edge objects,
 * and parallel edges (the multigraph case). Complements
 * {@link IllustrativeDatasetLoadTest}, which pins the exact structure of the
 * shipped sample.
 */
class StreamingLoaderRobustnessTest {

    private static Path write(Path dir, String json) throws IOException {
        Path f = dir.resolve("g.json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void unknownFieldsAndFieldOrderIgnored(@TempDir Path dir) throws IOException {
        // "edges" appears before "vertices"; "id" last; extra unknown fields at
        // every level; two parallel 0->1 edges. A correct streaming parser must
        // skipChildren over the unknowns and stay token-aligned.
        String json = """
            {
              "name": "robust",
              "extra_top": {"nested": [1, 2, 3]},
              "label_strings": ["X", "Y"],
              "graphs": [
                {
                  "edges": [
                    {"src": 0, "dst": 1, "weight": 0.9, "note": "keep"},
                    {"src": 0, "dst": 1, "weight": 0.4},
                    {"src": 1, "dst": 2, "weight": 0.5}
                  ],
                  "vertices": [
                    {"id": 0, "label": 0, "attr": {"z": 1}},
                    {"id": 1, "label": 1},
                    {"label": 0}
                  ],
                  "id": 7
                }
              ]
            }
            """;
        MultiGraphDB db = MultiGraphDB.loadJson(write(dir, json));

        assertEquals("robust", db.name());
        assertEquals(2, db.labelStrings().size());
        assertEquals(1, db.size());

        MultiGraph g = db.get(0);
        assertEquals(7, g.id(), "explicit id must survive even when it comes last");
        assertEquals(3, g.numVertices(), "third vertex uses positional id default");
        assertEquals(3, g.numEdges(), "both parallel 0->1 edges must be kept");
    }

    @Test
    void missingVerticesRejected(@TempDir Path dir) {
        String json = "{\"graphs\":[{\"edges\":[]}]}";
        assertThrows(IOException.class, () -> MultiGraphDB.loadJson(write(dir, json)));
    }

    @Test
    void outOfOrderVertexIdRejected(@TempDir Path dir) {
        String json = """
            {"graphs":[{"vertices":[{"id":0,"label":0},{"id":5,"label":1}],"edges":[]}]}
            """;
        assertThrows(IOException.class, () -> MultiGraphDB.loadJson(write(dir, json)));
    }

    @Test
    void negativeLabelRejected(@TempDir Path dir) {
        String json = "{\"graphs\":[{\"vertices\":[{\"id\":0,\"label\":-1}],\"edges\":[]}]}";
        assertThrows(IOException.class, () -> MultiGraphDB.loadJson(write(dir, json)));
    }

    @Test
    void missingWeightRejected(@TempDir Path dir) {
        String json = """
            {"graphs":[{"vertices":[{"id":0,"label":0},{"id":1,"label":0}],
             "edges":[{"src":0,"dst":1}]}]}
            """;
        assertThrows(IOException.class, () -> MultiGraphDB.loadJson(write(dir, json)));
    }
}
