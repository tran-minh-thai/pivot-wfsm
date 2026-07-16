package pivotwfsm.baselines.wfsm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EdgeClassAndOccurrenceListTest {

    @Test
    void occurrenceListMpfShrinksAsTargetEdgeCountRises() {
        // Paper §4.2: gx has OL = {q1={G1}_4; q2={G3}_6; q3={G2}_7}.
        // mpf(gx_4) = 3 (all partitions); mpf(gx_6) = 2 (drop G1); mpf(gx_7) = 1 (only G2).
        OccurrenceList ol = new OccurrenceList(List.of(
            new OccurrenceList.OLM(4, new int[]{1}),
            new OccurrenceList.OLM(6, new int[]{3}),
            new OccurrenceList.OLM(7, new int[]{2})
        ));

        assertEquals(3, ol.maxPossibleFrequency(4));
        assertEquals(2, ol.maxPossibleFrequency(6));
        assertEquals(1, ol.maxPossibleFrequency(7));
        assertEquals(0, ol.maxPossibleFrequency(8), "beyond max edge count");
    }

    @Test
    void occurrenceListSupportSumsAllOLMSizes() {
        OccurrenceList ol = new OccurrenceList(List.of(
            new OccurrenceList.OLM(4, new int[]{1, 5}),
            new OccurrenceList.OLM(6, new int[]{3})
        ));
        assertEquals(3, ol.support());
    }

    @Test
    void occurrenceListMembersAreAscendingByEdgeCount() {
        // Even if constructed out of order, members must come back ascending.
        OccurrenceList ol = new OccurrenceList(List.of(
            new OccurrenceList.OLM(7, new int[]{2}),
            new OccurrenceList.OLM(4, new int[]{1}),
            new OccurrenceList.OLM(6, new int[]{3})
        ));
        List<OccurrenceList.OLM> ms = ol.members();
        assertEquals(4, ms.get(0).edgeCount);
        assertEquals(6, ms.get(1).edgeCount);
        assertEquals(7, ms.get(2).edgeCount);
    }
}
