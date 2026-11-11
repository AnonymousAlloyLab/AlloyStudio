package is.fivefivefive.CanDis.metric;

import java.util.Objects;
import java.util.function.ToIntFunction;
import org.json.JSONObject;

/** Immutable numeric selection and executable metric-representation program.
 * This is in-process authority, not a source-construction or wire certificate. */
public final class NativeRepairTrace {
    public static final class ReplayFailure extends IllegalArgumentException {
        private final int operation;
        private final String payloadPath;
        ReplayFailure(int operation,String payloadPath,String message,Throwable cause) {
            super("NATIVE_TRACE operation "+operation+" at "+payloadPath+": "+message,cause);
            this.operation=operation;this.payloadPath=payloadPath;
        }
        public int operation(){return operation;}
        public String payloadPath(){return payloadPath;}
    }
    public static final class CaptureFailure extends IllegalStateException {
        private final String selected;
        private CaptureFailure(JSONObject selected,RuntimeException cause) {
            super("NATIVE_TRACE_EXTRACTION_FAILED: "+cause.getMessage(),cause);
            TraceResources.requireJson(selected);this.selected=selected.toString();
        }
        public JSONObject selectedReceipt(){return new JSONObject(selected);}
    }
    public static CaptureFailure captureFailure(JSONObject selected,RuntimeException cause) {
        return new CaptureFailure(selected,cause);
    }
    private final String retained;
    private final int distance;
    private final int temporal,quantifiers,matrix;
    private final boolean exactForStoredOrbits;
    private final long binderAlignments;
    private final ToIntFunction<JSONObject> replayer;

    private NativeRepairTrace(JSONObject evidence, ToIntFunction<JSONObject> replayer) {
        this.replayer=Objects.requireNonNull(replayer);
        distance=DeclarationReplay.integer(evidence,"distance");
        temporal=DeclarationReplay.integer(evidence,"temporalDistance");quantifiers=DeclarationReplay.integer(evidence,"quantifierDistance");
        matrix=DeclarationReplay.integer(evidence,"matrixDistance");
        exactForStoredOrbits=evidence.optBoolean("exactForStoredOrbits");binderAlignments=evidence.optLong("binderAlignments");
        int total=Math.addExact(temporal,Math.addExact(quantifiers,matrix));
        DeclarationReplay.require(distance==total,"Native trace component sum");
        DeclarationReplay.require(replayer.applyAsInt(evidence)==total,"Native trace executable sum");
        evidence.put("nativeDeclarationRepresentationSteps",declarationOrders(evidence));
        evidence.put("orderedNativeOperations",ordered(evidence));
        verifyOrder(evidence);
        evidence.put("schema","acgn-distance-derived-trace-v1")
                .put("metricRepresentationExecutionChecked",true)
                .put("publicSequenceLinked",false).put("sourceCertificateWireReplayChecked",false)
                .put("javaLeanCorrespondenceChecked",false).put("certificationStatus","BLOCKED");
        TraceResources.requireJson(evidence);
        retained=evidence.toString();
    }

    /** Producers supply the retained decision and a fixed-choice, non-optimizing executor. */
    public static NativeRepairTrace checked(JSONObject evidence,ToIntFunction<JSONObject> replayer) {
        return new NativeRepairTrace(evidence,replayer);
    }

    public int distance() { return distance; }
    public int temporalDistance() { return temporal; }
    public int quantifierDistance() { return quantifiers; }
    public int matrixDistance() { return matrix; }
    public boolean exactForStoredOrbits() { return exactForStoredOrbits; }
    public long binderAlignments() { return binderAlignments; }
    public JSONObject json() { return new JSONObject(retained); }

    public java.util.List<String> paidDescriptions() {
        if(distance==0)return java.util.List.of("no-op");
        JSONObject trace=json();var descriptions=new java.util.ArrayList<String>();
        for(Object value:trace.getJSONObject("nativeTemporalProgram").getJSONArray("operations"))
            descriptions.add("temporal: "+((JSONObject)value).toString());
        int phase=0;
        for(Object value:trace.getJSONArray("nativeDeclarationPrograms")) {
            for(Object action:((JSONObject)value).getJSONArray("operations")) {
                JSONObject op=(JSONObject)action;
                if(op.getInt("cost")!=0)descriptions.add("declaration["+phase+"]: "+op);
            }
            phase++;
        }
        for(Object value:trace.getJSONArray("phases")) {
            JSONObject component=(JSONObject)value;
            for(Object action:component.getJSONObject("program").getJSONArray("operations")) {
                JSONObject op=(JSONObject)action;if(op.getInt("cost")==0)continue;
                String type="";
                if(!op.isNull("before")&&!op.isNull("after")) {
                    var a=new org.json.JSONArray(op.getJSONObject("before").getString("head"));
                    var b=new org.json.JSONArray(op.getJSONObject("after").getString("head"));
                    if(!java.util.Objects.equals(a.get(1),b.get(1)))type=" (result type/payload "+a.get(1)+" -> "+b.get(1)+")";
                }
                descriptions.add("matrix["+component.getInt("phase")+"]: "+op.getString("kind")+" "
                        +op.get("source")+" -> "+op.get("target")+type+" cost="+op.getInt("cost"));
            }
        }
        return java.util.List.copyOf(descriptions);
    }

    /** Fresh detached stores are built on every execution. Mutating json() cannot change authority. */
    public int replay(JSONObject program) {
        TraceResources.requireJson(program);
        verifyOrder(program);
        JSONObject expected=new JSONObject(retained);
        int replayed;
        try {replayed=replayer.applyAsInt(program);}
        catch(RuntimeException failure) {
            var order=expected.getJSONArray("orderedNativeOperations");
            for(int i=0;i<order.length();i++) {
                String path=order.getJSONObject(i).getString("payloadPath");
                boolean equal;
                try {equal=((JSONObject)at(expected,path)).similar(at(program,path));}
                catch(RuntimeException missing){equal=false;}
                if(!equal)throw new ReplayFailure(i,path,failure.getMessage(),failure);
            }
            throw failure;
        }
        DeclarationReplay.require(replayed==distance,"Selected native trace cost");
        DeclarationReplay.require(expected.similar(program),"Changed retained native decision or construction link");
        return replayed;
    }

    private static Object at(JSONObject root,String path) {
        Object value=root;
        for(String part:path.substring(1).split("/"))value=value instanceof JSONObject object?object.get(part)
                :((org.json.JSONArray)value).get(Integer.parseInt(part));
        return value;
    }

    private static org.json.JSONArray ordered(JSONObject program) {
        var actions=new org.json.JSONArray();var temporal=program.getJSONObject("nativeTemporalProgram");
        if(program.has("nativePhaseRepresentationSteps"))
            append(actions,program.getJSONArray("nativePhaseRepresentationSteps"),"phase-representation","/nativePhaseRepresentationSteps/");
        append(actions,temporal.getJSONArray("zeroCostReads"),"temporal-read","/nativeTemporalProgram/zeroCostReads/");
        append(actions,temporal.getJSONArray("operations"),"temporal","/nativeTemporalProgram/operations/");
        append(actions,program.getJSONArray("nativeDeclarationRepresentationSteps"),"declaration-representation","/nativeDeclarationRepresentationSteps/");
        var declarations=program.getJSONArray("nativeDeclarationPrograms");
        for(int i=0;i<declarations.length();i++)append(actions,declarations.getJSONObject(i).getJSONArray("operations"),
                "declaration","/nativeDeclarationPrograms/"+i+"/operations/");
        var phases=program.getJSONArray("phases");
        for(int i=0;i<phases.length();i++) {
            var matrix=phases.getJSONObject(i).getJSONObject("program");
            String prefix="/phases/"+i+"/program/";
            append(actions,matrix.getJSONArray("representationSteps"),"representation",prefix+"representationSteps/");
            append(actions,matrix.getJSONArray("operations"),"matrix",prefix+"operations/");
        }
        return actions;
    }

    private static void append(org.json.JSONArray out,org.json.JSONArray operations,String component,String prefix) {
        for(int i=0;i<operations.length();i++)out.put(new JSONObject().put("component",component)
                .put("payloadPath",prefix+i).put("cost",DeclarationReplay.integer(operations.getJSONObject(i),"cost")));
    }

    private static void verifyOrder(JSONObject program) {
        DeclarationReplay.require(declarationOrders(program).similar(program.getJSONArray("nativeDeclarationRepresentationSteps")),
                "Native declaration occurrence/order correspondence");
        var expected=ordered(program);var supplied=program.getJSONArray("orderedNativeOperations");
        DeclarationReplay.require(expected.length()==supplied.length(),"Native ordered operation coverage");
        int total=0;
        for(int i=0;i<expected.length();i++) {
            if(!expected.getJSONObject(i).similar(supplied.getJSONObject(i)))throw new ReplayFailure(i,
                    expected.getJSONObject(i).getString("payloadPath"),"Changed selected operation order or charge",null);
            total=Math.addExact(total,DeclarationReplay.integer(supplied.getJSONObject(i),"cost"));
        }
        DeclarationReplay.require(total==DeclarationReplay.integer(program,"distance"),"Native ordered component total");
    }

    private static org.json.JSONArray declarationOrders(JSONObject program) {
        var orders=new org.json.JSONArray();var declarations=program.getJSONArray("nativeDeclarationPrograms");
        for(int i=0;i<declarations.length();i++)for(String side:java.util.List.of("source","target")) {
            var occurrences=new org.json.JSONArray();
            for(Object value:declarations.getJSONObject(i).getJSONArray(side))occurrences.put(((JSONObject)value).getString("occurrence"));
            orders.put(new JSONObject().put("component",i).put("side",side).put("kind","metric-coordinate-order")
                    .put("originalOccurrencesInSelectedOrder",occurrences).put("cost",0)
                    .put("authority","Original typed atom addresses in the selected native metric representation; not a new semantic exchange law"));
        }
        if(program.has("selectedGroupedDeclarationPermutation"))orders.put(new JSONObject().put("kind","selected-grouped-orbit")
                .put("selection",new JSONObject(program.getJSONObject("selectedGroupedDeclarationPermutation").toString())).put("cost",0));
        if(program.has("selectedDeclarationPermutation"))orders.put(new JSONObject().put("kind","selected-certified-orbit")
                .put("selection",new JSONObject(program.getJSONObject("selectedDeclarationPermutation").toString())).put("cost",0));
        return orders;
    }

    /** Retains construction derivations separately; they are not charged repair operations. */
    public NativeRepairTrace linkConstruction(JSONObject evidence,Runnable checkPremises) {
        JSONObject linked=json().put("constructionDerivations",evidence);
        return checked(linked,program->{checkPremises.run();return replayer.applyAsInt(program);});
    }
}
