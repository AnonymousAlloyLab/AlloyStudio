package is.fivefivefive.CanDis.theory;

import java.nio.file.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import parser.ast.nodes.ModelUnit;

/** Finite source-bound controls for duplicate CALL context weakening. */
public final class CallContextWeakeningTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static StructuralKey parseKey(String text) {
        class Reader {
            int offset;
            int count() { int colon=text.indexOf(':',offset); int n=Integer.parseInt(text.substring(offset,colon)); offset=colon+1; return n; }
            String string() { int n=count(); String s=text.substring(offset,offset+n); offset+=n; return s; }
            void expect(char c) { if(text.charAt(offset++)!=c) throw new IllegalArgumentException("key delimiter"); }
            StructuralKey read() {
                String tag=string(); expect('['); int n=count(); var scalars=new ArrayList<String>();
                for(int i=0;i<n;i++)scalars.add(string()); expect(']'); expect('{'); n=count();
                var children=new ArrayList<StructuralKey>(); for(int i=0;i<n;i++)children.add(parseKey(string())); expect('}');
                if(offset!=text.length())throw new IllegalArgumentException("key trailing bytes");
                return StructuralKey.of(tag,scalars,children);
            }
        }
        var key=new Reader().read();
        check(key.stableString().equals(text),"lossless retained structural-key decoding");
        return key;
    }
    private static GraphType type(StructuralKey key) {
        var args=key.children().stream().map(CallContextWeakeningTest::type).toList();
        return switch(key.tag()) {
            case "type/INT" -> GraphType.INT;
            case "type/BOOL" -> GraphType.BOOL;
            case "type/TYPE_VARIABLE" -> GraphType.typeVariable(key.scalars().get(0));
            case "type/CONSTRUCTOR" -> GraphType.constructor(key.scalars().get(0),args);
            case "type/RELATION" -> GraphType.relation(args);
            case "type/ARROW" -> GraphType.arrow(args.get(0),args.get(1));
            default -> throw new IllegalArgumentException("not a graph type: "+key.tag());
        };
    }
    private static TypedSlotContext context(StructuralKey key) {
        check(key.tag().equals("context"),"captured caller context");
        return TypedSlotContext.of(key.children().stream().map(s->TypedSlot.of(type(s.children().get(0)),
                SlotAlphabet.valueOf(s.scalars().get(0)),new BigInteger(s.scalars().get(1)))).toList());
    }
    private static CallMetadata.Validated metadata(CallOccurrenceCertificate c, String callee,
            String authority, long occurrence, int arity) {
        var children=new ArrayList<EGraphNode>();
        for(int i=0;i<arity;i++)children.add(new EGraphNode(i+1,EGraphNode.Opcode.CONSTANT,List.of(),false,0,false,Metatype.SET));
        var node=new EGraphNode(0,EGraphNode.Opcode.CALL,children,false,arity,false,Metatype.SET,
                SemanticProfile.alloyOverflowForbidding());
        node.setSourceName(c.sourceName()); node.setSemanticIdentity(callee); node.setSourceType(c.kind());
        node.setCallOccurrenceId(occurrence); node.setDeclaredArity(arity); node.setCallArityAuthority(authority);
        return CallMetadata.require(node);
    }
    private static CallOccurrenceCertificate certificate(CallOccurrenceCertificate base, TypedENode node) {
        return CallOccurrenceCertificate.create(metadata(base,base.qualifiedCallee(),base.arityAuthority().name(),
                base.occurrenceId(),node.ports().size()),base.sourcePath()+"/clone",node,
                node.ports().stream().map(p->(OnePort)p).toList());
    }
    private static CallOccurrenceCertificate alternate(CallOccurrenceCertificate base, String callee,
            String authority,long occurrence,List<OnePort> args) {
        var op=OperatorDeclaration.monomorphic("ALLOY/CALL/"+callee+"/"+args.size()+"/"+base.kind()+"/"+authority,
                args.stream().map(a->(PortSchema)a.schema()).toList(),base.sourceEndpoint().outputType(),Map.of(),null).instantiateMonomorphic();
        var node=TypedENode.construct(op,base.sourceEndpoint().context(),args);
        return CallOccurrenceCertificate.create(metadata(base,callee,authority,occurrence,args.size()),base.sourcePath(),node,args);
    }
    private static boolean oldGate(CallOccurrenceCertificate a,CallOccurrenceCertificate b) {
        return a.occurrenceId()==b.occurrenceId() && a.declaredArity()==b.declaredArity()
                && a.sourceName().equals(b.sourceName()) && a.qualifiedCallee().equals(b.qualifiedCallee())
                && a.kind().equals(b.kind()) && a.arityAuthority()==b.arityAuthority()
                && a.sourceEndpoint().equals(b.sourceEndpoint()) && a.orderedArguments().equals(b.orderedArguments());
    }
    private static void negatives(CallOccurrenceCertificate c) {
        var node=c.sourceEndpoint(); var used=node.support().slots().first();
        var foreign=TypedSlot.of(used.type(),used.alphabet(),used.ordinal().add(BigInteger.valueOf(1000)));
        var mapping=new HashMap<TypedSlot,TypedSlot>();for(var s:node.context().slots())mapping.put(s,s.equals(used)?foreign:s);
        var wrongOwner=node.act(TypedEmbedding.of(node.context(),TypedSlotContext.of(mapping.values()),mapping));
        check(!c.sameParserOccurrencePayloadAs(certificate(c,wrongOwner)),"same-type foreign USED owner rejected");
        reject(()->TypedEmbedding.inclusion(node.support(),node.context().without(used)),"missing USED slot rejected");
        var wrongType=TypedSlot.canonicalBound(GraphType.INT,0);var bad=new HashMap<>(mapping);bad.put(used,wrongType);
        reject(()->TypedEmbedding.of(node.context(),TypedSlotContext.of(bad.values()),bad),"USED type corruption rejected");
        var invocation=((InvocationPortLeaf)c.orderedArguments().get(0).leaf()).invocation();
        var ec=invocation.eclass();var foreignClass=new TypedEClassInterface(EClassId.of(ec.id().value()+1000),ec.outputType(),ec.exposedSlots());
        var other=OnePort.invocation(node.context(),new TypedInvocation(foreignClass,invocation.embedding()));
        var args=new ArrayList<>(c.orderedArguments());args.set(0,other);
        check(!c.sameParserOccurrencePayloadAs(alternate(c,c.qualifiedCallee(),c.arityAuthority().name(),c.occurrenceId(),args)),"same-type foreign argument class rejected");
        check(!c.sameParserOccurrencePayloadAs(alternate(c,c.qualifiedCallee()+"Other",c.arityAuthority().name(),c.occurrenceId(),c.orderedArguments())),"foreign callee rejected");
        check(!c.sameParserOccurrencePayloadAs(alternate(c,c.qualifiedCallee(),"DECLARATION",c.occurrenceId(),c.orderedArguments())),"authority substitution rejected");
        check(!c.sameParserOccurrencePayloadAs(alternate(c,c.qualifiedCallee(),c.arityAuthority().name(),c.occurrenceId()+1,c.orderedArguments())),"foreign parser occurrence rejected");
        var two=alternate(c,c.qualifiedCallee(),c.arityAuthority().name(),c.occurrenceId(),List.of(c.orderedArguments().get(0),other));
        var reversed=alternate(c,c.qualifiedCallee(),c.arityAuthority().name(),c.occurrenceId(),List.of(other,c.orderedArguments().get(0)));
        check(!two.sameParserOccurrencePayloadAs(reversed),"argument order retained");
        reject(()->CallOccurrenceCertificate.create(metadata(c,c.qualifiedCallee(),c.arityAuthority().name(),c.occurrenceId(),1),c.sourcePath(),node,List.of(other)),"detached argument evidence rejected");
    }
    public static void main(String[] args) throws Exception {
        var bindings=new JSONObject(Files.readString(Path.of(args[0])));var results=new JSONArray();
        for(Object item:bindings.getJSONArray("events")) {
            var row=(JSONObject)item;byte[] bytes=Files.readAllBytes(Path.of(row.getString("sourcePath")));
            check(hash(bytes).equals(row.getString("sha")),"natural source hash");
            var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            String file=Path.of(row.getString("sourcePath")).getFileName().toString();String pred=file.substring(file.lastIndexOf('_')+1,file.length()-4);
            for(String name:List.of(pred,pred+"c")) {
                var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);visitor.visit(new ModelUnit(null,module),null);
                var prepared=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
                var adapted=CanonicalAlloyPipeline.prepare(prepared);
                var calls=adapted.semanticArtifact().callOccurrenceCertificates();check(!calls.isEmpty(),"natural call construction retained");
                if(!name.equals(pred))continue;
                var capture=row.getJSONArray("captures");var first=capture.getJSONObject(0);var second=capture.getJSONObject(1);
                var call=calls.stream().filter(c->c.occurrenceId()==first.getLong("occurrence")).findFirst().orElseThrow();
                check(call.sourceEndpoint().structuralKey().stableString().equals(first.getString("sourceEndpoint")),"actual natural source endpoint matches retained typed payload");
                var targetContext=context(parseKey(second.getString("sourceEndpoint")).children().get(1));
                var exact=call.sourceEndpoint().inExactSupportContext();
                var body=exact.act(TypedEmbedding.inclusion(exact.context(),targetContext));
                check(body.structuralKey().stableString().equals(second.getString("sourceEndpoint")),"inverse inclusion reconstructs exact retained body clone, not equal metadata");
                var clone=certificate(call,body);
                check(!oldGate(call,clone)&&!oldGate(clone,call),"old gate rejects both directions");
                check(call.sameParserOccurrencePayloadAs(clone)&&clone.sameParserOccurrencePayloadAs(call),"new exact-support gate accepts both directions");
                check(exact.act(TypedEmbedding.inclusion(exact.context(),call.sourceEndpoint().context())).equals(call.sourceEndpoint()),"domain inverse inclusion");
                check(body.inExactSupportContext().equals(exact),"body exact support");
                negatives(call);negatives(clone);
                results.put(new JSONObject().put("event",row.getInt("id")).put("sourceSha256",row.getString("sha"))
                    .put("rawOldRejection",true).put("typedRoundtrip",true).put("bothDirections",true)
                    .put("sourceEndpoint",call.sourceEndpoint().structuralKey().stableString()).put("bodyEndpoint",body.structuralKey().stableString()));
            }
        }
        Files.writeString(Path.of(args[1]),new JSONObject().put("checks",checks).put("results",results)
                .put("scope","construction duplicate gate, not complete repair-path parity").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("PASS CallContextWeakeningTest "+checks+" checks");
    }
}
