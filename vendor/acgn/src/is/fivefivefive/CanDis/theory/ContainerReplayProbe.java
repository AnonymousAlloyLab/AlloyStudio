package is.fivefivefive.CanDis.theory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.json.JSONWriter;

/**
 * Finite observations of general homogeneous container constructors and traces.
 * Explicit atLeast(0) schemas include empty words; this is NOT production Alloy
 * authority for empty containers, nor parser or certificate trace closure.
 *
 * ContainerNormalizationTrace.of checks kind/schema/context preservation and
 * normalization of its supplied inputs. It does not constrain those inputs to
 * the source container's stored children (their count, order, or derivation).
 * Here the constructor already normalizes the source; the supplied trace inputs
 * are the original word. Fibers index that word, not the stored source children.
 *
 * Output order is BOOL then REL, SEQ then BAG then SET, then length and
 * lexicographic word order over {0,1,2}. IDs index the row's atom table; complete
 * port keys retain schema, caller context, e-class interface, and embedding.
 * REL is GraphType.relation(List.of(GraphType.INT)); these are synthetic typed
 * invocations, not parser-authenticated expressions or inserted graph nodes.
 *
 * Run the main class with no arguments for JSON on stdout, or one output path.
 * No expected normalization or fibers are computed by this probe.
 */
public final class ContainerReplayProbe {
    private static final int MAX_LENGTH = 4;
    private static final int ALPHABET_SIZE = 3;

    private ContainerReplayProbe() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: ContainerReplayProbe [output.json]");
        }
        StringBuilder document = new StringBuilder();
        JSONWriter json = new JSONWriter(document);
        json.object()
                .key("schemaVersion").value(1)
                .key("maxLength").value(MAX_LENGTH)
                .key("alphabetSize").value(ALPHABET_SIZE)
                .key("rows").array();
        int rowId = 0;
        for (String carrier : List.of("BOOL", "REL")) {
            GraphType type = carrier.equals("BOOL")
                    ? GraphType.BOOL : GraphType.relation(List.of(GraphType.INT));
            List<TypedSlot> slots = new ArrayList<>();
            for (int id = 0; id < ALPHABET_SIZE; id++) {
                slots.add(TypedSlot.source(type, id));
            }
            TypedSlotContext context = TypedSlotContext.of(slots);
            List<OnePort> atoms = new ArrayList<>();
            for (TypedSlot slot : slots) {
                TypedSlotContext exposed = TypedSlotContext.singleton(slot);
                TypedEClassInterface eclass = new TypedEClassInterface(
                        EClassId.of(slot.ordinal().longValueExact()), type, exposed);
                atoms.add(OnePort.invocation(context, new TypedInvocation(
                        eclass, TypedEmbedding.inclusion(exposed, context))));
            }
            require(new HashSet<>(atoms).size() == ALPHABET_SIZE,
                    "Typed input alphabet must have distinct port identities");
            ArityPolicy policy = ArityPolicy.atLeast(0);
            OnePortSchema element = new OnePortSchema(type);
            List<PortSchema> schemas = List.of(
                    new SeqPortSchema(policy, element),
                    new BagPortSchema(policy, element),
                    new SetPortSchema(policy, element));
            for (PortSchema schema : schemas) {
                int wordCount = 1;
                for (int length = 0; length <= MAX_LENGTH; length++) {
                    for (int word = 0; word < wordCount; word++) {
                        int[] ids = new int[length];
                        int remaining = word;
                        for (int index = length - 1; index >= 0; index--) {
                            ids[index] = remaining % ALPHABET_SIZE;
                            remaining /= ALPHABET_SIZE;
                        }
                        List<PortValue> input = new ArrayList<>();
                        for (int id : ids) {
                            input.add(atoms.get(id));
                        }
                        PortValue container = construct(schema, context, input);
                        ContainerNormalizationTrace trace =
                                ContainerNormalizationTrace.of(container, input, container);
                        writeRow(json, rowId++, carrier, type, policy, slots, atoms,
                                input, container, trace);
                    }
                    wordCount *= ALPHABET_SIZE;
                }
            }
        }
        require(rowId == 726, "Unexpected enumeration size: " + rowId);
        json.endArray().endObject();
        document.append('\n');
        if (args.length == 0) {
            System.out.print(document);
        } else {
            Files.writeString(Path.of(args[0]), document, StandardCharsets.UTF_8);
        }
    }

    private static PortValue construct(
            PortSchema schema, TypedSlotContext context, List<PortValue> input) {
        switch (schema.kind()) {
            case SEQ:
                return new SeqPort((SeqPortSchema) schema, context, input);
            case BAG:
                return new BagPort((BagPortSchema) schema, context, input);
            case SET:
                return new SetPort((SetPortSchema) schema, context, input);
            default:
                throw new IllegalArgumentException("Not a container schema: " + schema);
        }
    }

    private static void writeRow(
            JSONWriter json, int rowId, String carrier, GraphType type,
            ArityPolicy policy, List<TypedSlot> slots, List<OnePort> atoms,
            List<PortValue> input, PortValue container, ContainerNormalizationTrace trace) {
        require(trace.inputOccurrences().equals(input),
                "Trace changed the requested input word in row " + rowId);
        List<Integer> outputIds = new ArrayList<>();
        for (PortValue output : trace.outputOccurrences()) {
            int occurrence = input.indexOf(output);
            require(occurrence >= 0, "Unknown output atom in row " + rowId);
            outputIds.add(atomId(atoms, input.get(occurrence)));
        }
        List<Integer> inputIds = new ArrayList<>();
        for (PortValue occurrence : input) {
            inputIds.add(atomId(atoms, occurrence));
        }
        json.object()
                .key("rowId").value(rowId)
                .key("kind").value(trace.kind().name())
                .key("carrier").value(carrier)
                .key("type").value(type.toString())
                .key("typeEncoding").value(TheoryKeys.type(type).stableString())
                .key("arityPolicy").object()
                    .key("kind").value(policy.kind().name())
                    .key("minimum").value(policy.minimum())
                    .key("encoding").value(policy.structuralKey().stableString())
                .endObject()
                .key("contextEncoding").value(TheoryKeys.context(trace.context()).stableString())
                .key("schemaEncoding").value(trace.schema().structuralKey().stableString())
                .key("atoms").array();
        for (int id = 0; id < atoms.size(); id++) {
            json.object()
                    .key("id").value(id)
                    .key("slotEncoding").value(TheoryKeys.slot(slots.get(id)).stableString())
                    .key("portEncoding").value(atoms.get(id).structuralKey().stableString())
                    .endObject();
        }
        json.endArray()
                .key("input").value(inputIds)
                .key("output").value(outputIds)
                .key("fibers").value(trace.outputFibers())
                .key("containerEncoding").value(container.structuralKey().stableString())
                .key("traceEncoding").value(trace.structuralKey().stableString())
                .endObject();
    }

    private static int atomId(List<OnePort> atoms, PortValue value) {
        int id = atoms.indexOf(value);
        require(id >= 0, "Occurrence has no input alphabet identity");
        return id;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
