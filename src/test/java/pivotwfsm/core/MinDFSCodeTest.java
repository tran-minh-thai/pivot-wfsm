package pivotwfsm.core;

import org.junit.jupiter.api.Test;

import static pivotwfsm.core.DFSCode.EdgeTuple;
import static org.junit.jupiter.api.Assertions.*;

class MinDFSCodeTest {

    private static final int A = 0, B = 1, C = 2, D = 3;
    private static final int E = 0;

    @Test
    void emptyCodeIsTriviallyMinimum() {
        assertTrue(MinDFSCode.isMinimum(DFSCode.empty()));
    }

    @Test
    void singleEdgeABIsMinimumOnlyInCanonicalDirection() {
        // (0,1,A,_,B) is the canonical 1-edge code for an A-B edge.
        DFSCode ab = DFSCode.of(new EdgeTuple(0, 1, A, E, B));
        assertTrue(MinDFSCode.isMinimum(ab));

        // The reversed start (0,1,B,_,A) describes the same edge but is NOT canonical
        // because (B,_,A) is lex-greater than (A,_,B) field-by-field.
        DFSCode ba = DFSCode.of(new EdgeTuple(0, 1, B, E, A));
        assertFalse(MinDFSCode.isMinimum(ba));
        assertEquals(ab, MinDFSCode.minimum(ba));
    }

    @Test
    void pathABCCanonical() {
        // P2 path: A-B-C with code (0,1,A,_,B)(1,2,B,_,C). Canonical.
        DFSCode abc = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C)
        );
        assertTrue(MinDFSCode.isMinimum(abc));
    }

    @Test
    void pathCBANotCanonical_canonicalIsABC() {
        // Traverse the same A-B-C path starting from C: (0,1,C,_,B)(1,2,B,_,A).
        // Since A < C, the canonical form starts at A.
        DFSCode cba = DFSCode.of(
            new EdgeTuple(0, 1, C, E, B),
            new EdgeTuple(1, 2, B, E, A)
        );
        assertFalse(MinDFSCode.isMinimum(cba));

        DFSCode canonical = MinDFSCode.minimum(cba);
        assertEquals(A, canonical.edge(0).fromLabel);
        assertEquals(B, canonical.edge(0).toLabel);
        assertEquals(B, canonical.edge(1).fromLabel);
        assertEquals(C, canonical.edge(1).toLabel);
    }

    @Test
    void triangleABCCanonical() {
        // Canonical triangle: (0,1,A,_,B)(1,2,B,_,C)(2,0,C,_,A)
        DFSCode tri = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(2, 0, C, E, A)
        );
        assertTrue(MinDFSCode.isMinimum(tri),
            "the lex-smallest start (A,_,B) is what triangle's canonical code begins with");
    }

    @Test
    void triangleStartedFromCNotCanonical() {
        // Same triangle but starting at C: (0,1,C,_,B)(1,2,B,_,A)(2,0,A,_,C)
        DFSCode tri = DFSCode.of(
            new EdgeTuple(0, 1, C, E, B),
            new EdgeTuple(1, 2, B, E, A),
            new EdgeTuple(2, 0, A, E, C)
        );
        assertFalse(MinDFSCode.isMinimum(tri),
            "triangle traversal from C is not canonical; canonical starts at A");

        DFSCode canonical = MinDFSCode.minimum(tri);
        // canonical first tuple = (0,1,A,_,B)
        assertEquals(A, canonical.edge(0).fromLabel);
        assertEquals(B, canonical.edge(0).toLabel);
    }

    @Test
    void starStartedFromCenterIsNotCanonical() {
        // Star "B at center, leaves A, C, D". Starting the DFS at the center B
        // yields code (0,1,B,_,A)(0,2,B,_,C)(0,3,B,_,D). The first tuple is
        // (B,_,A) which is lex-larger than (A,_,B), so the canonical form
        // actually starts from leaf A. Hence this code is NOT canonical.
        DFSCode star = DFSCode.of(
            new EdgeTuple(0, 1, B, E, A),
            new EdgeTuple(0, 2, B, E, C),
            new EdgeTuple(0, 3, B, E, D)
        );
        assertFalse(MinDFSCode.isMinimum(star));

        DFSCode canonical = MinDFSCode.minimum(star);
        // Canonical: start from leaf A -> B, then B's other neighbours C and D.
        assertEquals(A, canonical.edge(0).fromLabel);
        assertEquals(B, canonical.edge(0).toLabel);
    }

    @Test
    void starWithLeavesVisitedInWrongOrderNotCanonical() {
        // The same star but the DFS starts at B and visits leaves as D,C,A.
        // Code: (0,1,B,_,D)(0,2,B,_,C)(0,3,B,_,A) - first tuple (B,_,D) is
        // worse than even (B,_,A), and the global canonical begins with (A,_,B).
        DFSCode star = DFSCode.of(
            new EdgeTuple(0, 1, B, E, D),
            new EdgeTuple(0, 2, B, E, C),
            new EdgeTuple(0, 3, B, E, A)
        );
        assertFalse(MinDFSCode.isMinimum(star));

        DFSCode canonical = MinDFSCode.minimum(star);
        // Canonical starts at leaf A regardless of how the input is written.
        assertEquals(A, canonical.edge(0).fromLabel);
        assertEquals(B, canonical.edge(0).toLabel);
    }

    @Test
    void starStartedFromLeafIsCanonical() {
        // Star DFS code starting at leaf A:
        //   (0,1,A,_,B)(1,2,B,_,C)(1,3,B,_,D)
        // First tuple (A,_,B) is the global lex-min over all star edges, so
        // this form IS the canonical one.
        DFSCode fromLeafA = DFSCode.of(
            new EdgeTuple(0, 1, A, E, B),
            new EdgeTuple(1, 2, B, E, C),
            new EdgeTuple(1, 3, B, E, D)
        );
        assertTrue(MinDFSCode.isMinimum(fromLeafA));
    }
}
