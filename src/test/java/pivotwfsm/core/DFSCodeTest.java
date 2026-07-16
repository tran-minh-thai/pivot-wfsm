package pivotwfsm.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static pivotwfsm.core.DFSCode.EdgeTuple;
import static org.junit.jupiter.api.Assertions.*;

class DFSCodeTest {

    // Labels for readability (mirrors the illustrative dataset).
    private static final int A = 0, B = 1, C = 2, D = 3;
    private static final int E = 0; // single sentinel edge label

    @Test
    void emptyCodeHasNoEdgesNoVertices() {
        DFSCode empty = DFSCode.empty();
        assertEquals(0, empty.numEdges());
        assertEquals(0, empty.numVertices());
        assertTrue(empty.edges().isEmpty());
    }

    @Test
    void singleForwardEdgeBuildsTwoVertices() {
        DFSCode code = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        assertEquals(1, code.numEdges());
        assertEquals(2, code.numVertices());
        assertArrayEquals(new int[]{A, B}, code.vertexLabels());
    }

    @Test
    void pathABCBuildsThreeVertices() {
        // Pattern P2 from the illustrative running example
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        assertEquals(2, code.numEdges());
        assertEquals(3, code.numVertices());
        assertArrayEquals(new int[]{A, B, C}, code.vertexLabels());
    }

    @Test
    void triangleABCHasBackwardEdge() {
        // Pattern P4 from the illustrative running example
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)  // backward edge closing the triangle
        );
        assertEquals(3, code.numEdges());
        assertEquals(3, code.numVertices());
        assertTrue(code.edge(0).isForward());
        assertTrue(code.edge(1).isForward());
        assertTrue(code.edge(2).isBackward());
    }

    @Test
    void starBPattern() {
        // Pattern P5: B at center, three leaves A, C, D
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, B, E, A),
            new EdgeTuple(0, 2, B, E, C),
            new EdgeTuple(0, 3, B, E, D)
        );
        assertEquals(3, code.numEdges());
        assertEquals(4, code.numVertices());
        assertArrayEquals(new int[]{B, A, C, D}, code.vertexLabels());
    }

    @Test
    void rejectsForwardEdgeIntroducingVertexOutOfOrder() {
        // Cannot jump from vertex 0 directly to vertex 5.
        assertThrows(IllegalArgumentException.class, () -> DFSCode.of(
            new EdgeTuple(0, 5, A, E, B)
        ));
    }

    @Test
    void rejectsBackwardEdgeToUnseenVertex() {
        // Cannot point a backward edge at vertex 7 when we've only seen 0,1.
        assertThrows(IllegalArgumentException.class, () -> DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 7, B, E, C)   // 7 is brand-new but treated as backward (7 > seen)
        ));
    }

    @Test
    void rejectsSelfLoop() {
        assertThrows(IllegalArgumentException.class, () -> DFSCode.of(
            new EdgeTuple(0, 0, A, E, A)
        ));
    }

    @Test
    void equalCodesHashAndCompareEqual() {
        DFSCode a = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        DFSCode b = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(0, a.compareTo(b));
    }

    @Test
    void lexComparatorTuples() {
        // (0,1,A,_,B) < (0,1,A,_,C)  by toLabel
        EdgeTuple t1 = new EdgeTuple(0, 1, A, E, B);
        EdgeTuple t2 = new EdgeTuple(0, 1, A, E, C);
        assertTrue(t1.compareTo(t2) < 0);
        assertTrue(t2.compareTo(t1) > 0);

        // backward (1,0) < forward (1,2) at same i=1 - gSpan special rule
        EdgeTuple back = new EdgeTuple(1, 0, B, E, A);
        EdgeTuple fwd  = new EdgeTuple(1, 2, B, E, C);
        assertTrue(back.compareTo(fwd) < 0);
    }

    @Test
    void extendAppendsAndValidates() {
        DFSCode singleton = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        DFSCode extended = singleton.extend(new EdgeTuple(1, 2, B, E, C));
        assertEquals(2, extended.numEdges());
        assertArrayEquals(new int[]{A, B, C}, extended.vertexLabels());
        // The original is unchanged (immutability).
        assertEquals(1, singleton.numEdges());
    }

    @Test
    void rightMostPathOfSingleEdge() {
        DFSCode code = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        assertArrayEquals(new int[]{0, 1}, code.rightMostPath());
    }

    @Test
    void rightMostPathOfTwoEdgePath() {
        // P2 = A-B-C path. Right-most path is the whole path.
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        assertArrayEquals(new int[]{0, 1, 2}, code.rightMostPath());
    }

    @Test
    void rightMostPathOfStar() {
        // P5 star with B at center. Last forward edge is (0,3,B,_,D).
        // Right-most path traces vertex 3 back to 0 directly.
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, B, E, A),
            new EdgeTuple(0, 2, B, E, C),
            new EdgeTuple(0, 3, B, E, D)
        );
        assertArrayEquals(new int[]{0, 3}, code.rightMostPath());
    }

    @Test
    void rightMostPathOfTriangleIgnoresBackwardEdge() {
        // Triangle: two forward edges plus a backward edge that closes the loop.
        // The backward edge does not affect the right-most path.
        DFSCode code = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)
        );
        assertArrayEquals(new int[]{0, 1, 2}, code.rightMostPath());
    }

    @Test
    void edgesViewIsImmutable() {
        DFSCode code = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        List<EdgeTuple> view = code.edges();
        assertThrows(UnsupportedOperationException.class,
            () -> view.add(new EdgeTuple(1, 2, B, E, C)));
    }
}
