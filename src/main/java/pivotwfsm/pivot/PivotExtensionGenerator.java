package pivotwfsm.pivot;

import pivotwfsm.core.DFSCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Right-most path extension (gSpan
 * sense) restricted to frequent 1-edge templates; anchors ordered by distance
 * to the pivot (ordering affects exploration priority only, never
 * completeness).
 */
public final class PivotExtensionGenerator {

    /** A frequent 1-edge template: undirected (lowLabel <= highLabel). */
    public record Template(int lowLabel, int edgeLabel, int highLabel) {}

    private PivotExtensionGenerator() {
    }

    public static List<DFSCode> childCodes(PatternGraph parent, int pivot,
                                           Set<Template> frequentTemplates) {
        List<DFSCode> children = new ArrayList<>();
        int[] rightMostPath = parent.rightMostPath();
        int rightMost = rightMostPath[rightMostPath.length - 1];
        DFSCode parentCode = parent.code();

        int rmLabel = parent.vertexLabel(rightMost);
        for (int target : rightMostPath) {
            if (target == rightMost) {
                continue;
            }
            int targetLabel = parent.vertexLabel(target);
            for (Template t : frequentTemplates) {
                if (matchesPair(t, rmLabel, targetLabel)) {
                    children.add(parentCode.extend(new DFSCode.EdgeTuple(
                        rightMost, target, rmLabel, t.edgeLabel(), targetLabel)));
                }
            }
        }

        int[] pivotDistance = parent.bfsDistances(pivot);
        List<Integer> anchors = new ArrayList<>();
        for (int anchor : rightMostPath) {
            anchors.add(anchor);
        }
        anchors.sort(Comparator.comparingInt(a -> pivotDistance[a]));

        int newVertex = parent.numVertices();
        for (int anchor : anchors) {
            int anchorLabel = parent.vertexLabel(anchor);
            for (Template t : frequentTemplates) {
                if (t.lowLabel() == anchorLabel) {
                    children.add(parentCode.extend(new DFSCode.EdgeTuple(
                        anchor, newVertex, anchorLabel, t.edgeLabel(), t.highLabel())));
                }
                if (t.highLabel() == anchorLabel && t.highLabel() != t.lowLabel()) {
                    children.add(parentCode.extend(new DFSCode.EdgeTuple(
                        anchor, newVertex, anchorLabel, t.edgeLabel(), t.lowLabel())));
                }
            }
        }

        return children;
    }

    private static boolean matchesPair(Template t, int labelA, int labelB) {
        int lo = Math.min(labelA, labelB);
        int hi = Math.max(labelA, labelB);
        return t.lowLabel() == lo && t.highLabel() == hi;
    }
}
