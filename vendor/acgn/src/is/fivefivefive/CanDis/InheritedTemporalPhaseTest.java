package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Source-linked witnesses for duplicate leaves in one inherited environment. */
public final class InheritedTemporalPhaseTest {
    private static int checks, witnesses;
    private static final StringBuilder lean = new StringBuilder(
        "import InheritedTemporalPhase\nnamespace InheritedPhaseGenerated\nopen InheritedTemporalPhase Focused225CallViews\n");
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    private static void requireMatched(NativeOperationComparison.Status status, String reason, String message) {
        check(status==NativeOperationComparison.Status.MATCHED && "CHECKED_NATIVE_ACTIONS_EQUAL".equals(reason), message);
    }
    private static Object call(Class<?> type, Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args);
    }
    private static NormalForm copy(NormalForm source) throws Exception {
        return (NormalForm) call(NormalForm.class, source, "fastComparisonView", new Class<?>[0]);
    }
    private static String key(NormalForm owner, NormalForm child, EGraphNode matrix) throws Exception {
        return (String) call(NormalForm.class, null, "inheritedComparisonPhaseKey",
            new Class<?>[]{NormalForm.class, NormalForm.class, EGraphNode.class}, owner, child, matrix);
    }
    private static String quote(Object value) { return JSONObject.quote(String.valueOf(value)); }
    private static List<QuantiVar> visible(NormalForm owner) {
        var result = new ArrayList<QuantiVar>(); result.addAll(owner.getParams());
        result.addAll(owner.getInheritedQuantiVars()); result.addAll(owner.getMatrixQuantiVars()); return result;
    }
    private static String shape(NormalForm owner, int phase, EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            var bindings = visible(owner);
            var q = bindings.stream().filter(b -> b.getName().equals(node.getAlphaName())).findFirst().orElseThrow();
            return "(.variable { owner := "+quote(owner.getPhasePath())+", coordinate := "+quote(bindings.indexOf(q))
                +", type := "+quote(q.getExactAlloyType().stableString())+", phase := "+phase+" })";
        }
        String args = ".nil"; var children = node.getChildren();
        for (int i=children.size()-1;i>=0;i--) args="(.cons "+shape(owner,phase,children.get(i))+" "+args+")";
        String identity = node.getSemanticIdentity()!=null ? node.getSemanticIdentity()
            : node.getAlphaName()!=null ? node.getAlphaName() : node.getSourceName()!=null ? node.getSourceName() : "";
        String type = node.getExactAlloyType()==null ? "BOOL_ROOT" : node.getExactAlloyType().stableString();
        var metadata=List.of(node.getSemanticProfile().fingerprint(),String.valueOf(node.getArityPolicy()),
            String.valueOf(node.getSiblingQuotient()),String.valueOf(node.getFlatLicense()),String.valueOf(node.getUnitLicense()));
        return "(.node { opcode := "+quote(node.getOpcode())+", sourceName := \"\", identity := "+quote(identity)
            +", type := "+quote(type)+", lineage := 0, metadata := ["
            +String.join(",",metadata.stream().map(InheritedTemporalPhaseTest::quote).toList())+"] } "+args+")";
    }
    private static JSONObject witnesses(Canonical.Prepared endpoint) throws Exception {
        int before = witnesses; var rows = new JSONArray();
        for (var owner : endpoint.normalizedForms()) {
            var seen = new HashMap<String, NormalForm>();
            for (var child : owner.getTemporalChildren()) {
                var matrix = copy(child).getMatrixEGraph(); var body = key(owner,child,matrix);
                if (body==null) continue;
                var previous = seen.putIfAbsent(child.getTemporalOp()+":"+body,child);
                if (previous==null || child.getInheritedQuantiVars().isEmpty()) continue;
                check(!(boolean)call(NormalForm.class,null,"closedComparisonPhase",new Class<?>[]{NormalForm.class},child),"exact old gate rejection");
                int phase=endpoint.normalizedForms().indexOf(owner);
                String name="phase"+(witnesses++),left=shape(owner,phase,copy(previous).getMatrixEGraph()),right=shape(owner,phase,matrix);
                check(left.equals(right),"Java key corresponds to full typed owned Lean shape");
                lean.append("def ").append(name).append("Left : Shape := ").append(left).append('\n');
                lean.append("def ").append(name).append("Right : Shape := ").append(right).append('\n');
                lean.append("def ").append(name).append("Gate : Gate := { params := 0, declarations := 0, inherited := ")
                    .append(child.getInheritedQuantiVars().size()).append(", localImports := 0, localSeeds := 0, children := 0, operator := ")
                    .append(quote(child.getTemporalOp())).append(" }\n");
                lean.append("theorem ").append(name).append("_old : oldGate ").append(name).append("Gate = false := by decide\n");
                lean.append("theorem ").append(name).append("_new : leafGate ").append(name).append("Gate = true := by decide\n");
                lean.append("theorem ").append(name).append("_key : ").append(name).append("Left = ").append(name).append("Right := by decide\n");
                lean.append("theorem ").append(name).append("_sound {V W : Type} (env : ScopedCallCorrespondence.Slot → V) ")
                    .append("(op : W → ScopedCallCorrespondence.Head → List V → V) (holds : Option V → Prop) (temporal : (W → Prop) → W → Prop) : ")
                    .append("∀ w, temporal (fun t => holds (body env (op t) ").append(name).append("Left)) w ↔ ")
                    .append("temporal (fun t => holds (body env (op t) ").append(name).append("Right)) w := ")
                    .append("same_inherited_phase env op holds temporal _ _ ").append(name).append("_key\n");
                rows.put(new JSONObject().put("witness",name).put("owner",owner.getPhasePath()).put("ownerPhase",phase)
                    .put("first",previous.getPhasePath()).put("second",child.getPhasePath()).put("operator",child.getTemporalOp())
                    .put("inherited",child.getInheritedQuantiVars().stream().map(QuantiVar::getName).toList()).put("key",body));
            }
        }
        return new JSONObject().put("count",witnesses-before).put("witnesses",rows);
    }
    public static void main(String[] args) throws Exception {
        var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(var status:List.of(NativeOperationComparison.Status.MISMATCH,NativeOperationComparison.Status.UNCHECKABLE)) {
            boolean rejected=false;
            try { requireMatched(status,"CHECKED_NATIVE_ACTIONS_EQUAL","tampered status"); }
            catch(AssertionError expected) { rejected=true; }
            check(rejected,"nonmatched status must fail the positive regression");
        }
        for (Object item : new JSONObject(Files.readString(Path.of(args[0]))).getJSONArray("cases")) {
            var identity=((JSONObject)item).getJSONObject("identity");long start=System.nanoTime();
            var pair=LocalOwnerTraceTest.prepare(identity);int before=witnesses;
            var observed=new JSONArray();for(var endpoint:pair)observed.put(witnesses(endpoint));
            check(witnesses>before,"source-linked old rejection/new key: "+identity.getString("id"));
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);
                var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                var receipt=new JSONObject().put("identity",identity).put("direction",d).put("witnesses",observed);
                QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),receipt);
                var result=NativeOperationComparison.compare(a,b,f,c);
                receipt.put("fast",f.json()).put("certified",c.json()).put("status",result.status()).put("reason",result.reason()).put("comparison",result.evidence());
                Files.writeString(out.resolve(identity.getString("id")+"-"+d+".json"),receipt.toString(),StandardOpenOption.CREATE_NEW);
                requireMatched(result.status(),result.reason(),
                    "source-linked phase correspondence: "+identity.getString("id")+" direction "+d+" "+result.reason());
                rows.put(new JSONObject().put("id",identity.getString("id")).put("direction",d).put("status",result.status()).put("reason",result.reason()));
            }
            check((System.nanoTime()-start)/1e9<60,"60-second per-file invariant");
        }
        lean.append("end InheritedPhaseGenerated\n");
        Files.writeString(out.resolve("InheritedPhaseGenerated.lean"),lean.toString(),StandardOpenOption.CREATE_NEW);
        var result=new JSONObject().put("checks",checks).put("witnesses",witnesses).put("rows",rows);
        Files.writeString(out.resolve("summary.json"),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println(result.toString(2));
    }
}
