package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic traversal that binds descriptor laws to concrete port occurrences. */
final class BinderOccurrenceProofs {
    private BinderOccurrenceProofs() {
    }

    static List<BinderOccurrenceAutomorphismCertificate> collect(TypedENode node) {
        List<BinderOccurrenceAutomorphismCertificate> result = new ArrayList<>();
        for (int port = 0; port < node.ports().size(); port++) {
            collect(node, node.ports().get(port), path(port), result);
        }
        return Collections.unmodifiableList(result);
    }

    private static void collect(
            TypedENode enclosingRoot,
            PortValue port,
            List<Integer> path,
            List<BinderOccurrenceAutomorphismCertificate> target) {
        if (port instanceof BindBlockPort) {
            BindBlockPort block = (BindBlockPort) port;
            BinderBlockDescriptor descriptor = block.schema().descriptor();
            descriptor.automorphisms().requireCertifiedFor(descriptor);
            for (TypedPermutation generator : descriptor.automorphisms().generators()) {
                target.add(BinderOccurrenceAutomorphismCertificate.create(
                        enclosingRoot, block, path, generator));
            }
            collect(enclosingRoot, block.body(), child(path, 0), target);
            return;
        }
        if (port instanceof BindPort) {
            collect(enclosingRoot, ((BindPort) port).body(), child(path, 0), target);
            return;
        }
        List<? extends PortValue> children;
        if (port instanceof SeqPort) {
            children = ((SeqPort) port).elements();
        } else if (port instanceof BagPort) {
            children = ((BagPort) port).occurrences();
        } else if (port instanceof SetPort) {
            children = ((SetPort) port).elements();
        } else {
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            collect(enclosingRoot, children.get(index), child(path, index), target);
        }
    }

    private static List<Integer> path(int root) {
        return Collections.singletonList(root);
    }

    private static List<Integer> child(List<Integer> parent, int coordinate) {
        List<Integer> result = new ArrayList<>(parent.size() + 1);
        result.addAll(parent);
        result.add(coordinate);
        return Collections.unmodifiableList(result);
    }
}
