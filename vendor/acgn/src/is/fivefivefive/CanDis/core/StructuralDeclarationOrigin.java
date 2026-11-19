package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import org.json.*;

/** Sealed original declaration objects, independent of compared descriptors. */
public final class StructuralDeclarationOrigin {
    private record Located(EGraphNode owner,EGraphNode declaration,List<Integer> path) { }
    private record Context(EGraphNode node,EGraphNode.Opcode opcode,EGraphNode.Metatype role,ExactAlloyType type,long occurrence) {
        Context(EGraphNode n){this(n,n.getOpcode(),n.getMetatype(),n.getExactAlloyType(),n.getSourceOccurrenceLineage());}
        boolean valid(EGraphNode n){return n==node&&n.getOpcode()==opcode&&n.getMetatype()==role&&sameType(type,n.getExactAlloyType())&&n.getSourceOccurrenceLineage()==occurrence;}
    }
    private record Slot(QuantiVar binding,NormalForm.DeclarationFrame frame,ExactAlloyType type,
            QuantiVar.Quantifier quantifier,QuantiVar.Cardinality cardinality,int disjointness,String path,String coordinate,List<Dependency> dependencies) { }
    private record Dependency(QuantiVar binding,ExactAlloyType type,QuantiVar.Quantifier quantifier,
            QuantiVar.Cardinality cardinality,int disjointness,String path,String coordinate,
            List<NormalForm.DeclarationFrame> frames,List<String> phases,List<NormalForm.TemporalOp> roles) {
        Dependency(QuantiVar q){this(q,q.getExactAlloyType(),q.getQuantifier(),q.getCardinality(),q.getDisjointnessClass(),q.getBindingPath(),q.getDeBruijnKey(),
            q.getDeclarationFrames(),q.getDeclarationFrames().stream().map(f->f.owner().getPhasePath()).toList(),q.getDeclarationFrames().stream().map(f->f.owner().getTemporalOp()).toList());}
        boolean valid(){
            if(!binding.isFrozenForCertification()||type==null||!type.hasParserAuthenticatedAncestry()||!sameType(type,binding.getExactAlloyType())
                    ||quantifier!=binding.getQuantifier()||cardinality!=binding.getCardinality()||disjointness!=binding.getDisjointnessClass()
                    ||!path.equals(binding.getBindingPath())||!coordinate.equals(binding.getDeBruijnKey())||frames.size()!=binding.getDeclarationFrames().size())return false;
            for(int i=0;i<frames.size();i++)if(frames.get(i)!=binding.getDeclarationFrames().get(i)||!phases.get(i).equals(frames.get(i).owner().getPhasePath())||roles.get(i)!=frames.get(i).owner().getTemporalOp())return false;
            return true;
        }
    }
    private static final class Snapshot {
        final EGraphNode node;
        final EGraphNode.Opcode opcode;
        final EGraphNode.Metatype role;
        final ExactAlloyType type;
        final long occurrence;
        final String name,sourceType,identity,alpha;
        final List<Snapshot> children;
        final List<Object> childClasses;
        final List<Map<String,String>> invocations;
        Snapshot(EGraphNode n){
            node=n;opcode=n.getOpcode();role=n.getMetatype();type=n.getExactAlloyType();occurrence=n.getSourceOccurrenceLineage();
            name=n.getSourceName();sourceType=n.getSourceType();identity=n.getSemanticIdentity();alpha=n.getAlphaName();
            require(n.isFrozenForCertification()&&occurrence>0,"Mutable or absent source occurrence");
            require(structural(n)||role==EGraphNode.Metatype.BOOLEAN||type!=null,"Untyped declaration operand");
            children=n.getChildren().stream().map(Snapshot::new).toList();
            childClasses=n.getChildClasses().stream().map(r->(Object)r.getEClass()).toList();
            invocations=n.getChildClasses().stream().map(r->Map.copyOf(r.getSlotMap())).toList();
        }
        boolean matches(EGraphNode n,boolean original){
            if(original&&n!=node||n.getOpcode()!=opcode||n.getMetatype()!=role||n.getSourceOccurrenceLineage()!=occurrence
                    ||!Objects.equals(name,n.getSourceName())||!Objects.equals(sourceType,n.getSourceType())
                    ||!Objects.equals(identity,n.getSemanticIdentity())||original&&!Objects.equals(alpha,n.getAlphaName())
                    ||!sameType(type,n.getExactAlloyType())||children.size()!=n.getChildren().size())return false;
            for(int i=0;i<children.size();i++)if(!children.get(i).matches(n.getChildren().get(i),original))return false;
            if(original)for(int i=0;i<childClasses.size();i++)if(childClasses.get(i)!=n.getChildClasses().get(i).getEClass()
                    ||!invocations.get(i).equals(n.getChildClasses().get(i).getSlotMap()))return false;
            return true;
        }
    }
    private final NormalForm phase;
    private final String phasePath;
    private final NormalForm.TemporalOp phaseRole;
    private final EGraphNode originalRoot,liveRoot;
    private final Located original,live;
    private final Snapshot originalSnapshot,liveSnapshot;
    private final List<Context> originalContext,liveContext;
    private final List<Slot> slots;

    private StructuralDeclarationOrigin(NormalForm phase,Located original,Located live){
        this.phase=phase;phasePath=phase.getPhasePath();phaseRole=phase.getTemporalOp();
        originalRoot=phase.getCertificationMatrixEGraph();liveRoot=phase.getMatrixEGraph();this.original=original;this.live=live;
        originalContext=context(originalRoot,original);liveContext=context(liveRoot,live);
        require(original.owner.getSourceOccurrenceLineage()==live.owner.getSourceOccurrenceLineage()
                &&original.owner.getOpcode()==live.owner.getOpcode(),"Changed source owner");
        originalSnapshot=new Snapshot(original.declaration);liveSnapshot=new Snapshot(live.declaration);
        require(originalSnapshot.matches(live.declaration,false),"Changed original declaration group");
        require(original.declaration.getMetatype()==EGraphNode.Metatype.CONTROL&&original.declaration.getExactAlloyType()==null,"Expression is not a structural declaration");
        var cs=original.declaration.getChildren();require(cs.size()>=2,"Incomplete declaration group");
        var transport=phase.originalDeclarationTransport(original.declaration);
        require(transport!=null&&transport.slots().size()==cs.size()-1,"Missing original reseed transport");
        require(originalSnapshot.matches(transport.declaration(),false),"Changed actual reseeded declaration");
        var captured=new ArrayList<Slot>();
        for(int i=1;i<cs.size();i++){
            var variable=cs.get(i);require(variable.getOpcode()==EGraphNode.Opcode.VARIABLE,"Nonvariable declaration slot");
            var bindings=phase.getLocalQuantiVars().stream().filter(q->q.getName().equals(variable.getAlphaName())).toList();
            require(bindings.size()==1,"Missing original slot identity");var q=bindings.get(0);
            final int tuple=i-1;
            var frame=transport.frames().get(tuple);
            require(frame.owner()==phase&&frame.variable()==q&&frame.tupleSlot()==tuple
                &&transport.slots().get(tuple).getSourceOccurrenceLineage()==variable.getSourceOccurrenceLineage()
                &&q.getDeclarationFrames().stream().anyMatch(f->f==frame)&&q.isFrozenForCertification(),"Missing source-issued declaration frame");
            require(q.getExactAlloyType()!=null&&q.getExactAlloyType().hasParserAuthenticatedAncestry()
                &&q.getExactAlloyType().sameOccurrenceEvidenceAs(variable.getExactAlloyType()),"Foreign slot type authority");
            require(phase.matchesOriginalDeclarationDomain(frame,original.declaration),"Changed domain or ordered dependency occurrences");
            require(q.isDisj()==(original.declaration.getOpcode()==EGraphNode.Opcode.DISJ||original.declaration.getOpcode()==EGraphNode.Opcode.DISJVAR),"Changed disjointness");
            captured.add(new Slot(q,frame,q.getExactAlloyType(),q.getQuantifier(),q.getCardinality(),q.getDisjointnessClass(),q.getBindingPath(),q.getDeBruijnKey(),frame.dependencies().stream().map(Dependency::new).toList()));
        }
        slots=List.copyOf(captured);
    }
    static boolean structural(EGraphNode n){return n.getOpcode()==EGraphNode.Opcode.DISJ||n.getOpcode()==EGraphNode.Opcode.VAR||n.getOpcode()==EGraphNode.Opcode.DISJVAR;}
    private static boolean sameType(ExactAlloyType a,ExactAlloyType b){return a==null?b==null:b!=null&&a.sameOccurrenceEvidenceAs(b);}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException("STRUCTURAL_DECLARATION: "+why);}
    private static void locate(EGraphNode node,EGraphNode parent,List<Integer> path,Map<Long,Located> result){
        if(structural(node)){
            long occurrence=node.getSourceOccurrenceLineage();
            if(parent==null||result.containsKey(occurrence))result.put(occurrence,null);
            else result.put(occurrence,new Located(parent,node,List.copyOf(path)));
        }
        for(int i=0;i<node.getChildren().size();i++){var next=new ArrayList<>(path);next.add(i);locate(node.getChildren().get(i),node,next,result);}
    }
    static Map<EGraphNode,StructuralDeclarationOrigin> seal(NormalForm phase){
        var originals=new LinkedHashMap<Long,Located>();var live=new LinkedHashMap<Long,Located>();
        locate(phase.getCertificationMatrixEGraph(),null,List.of(),originals);locate(phase.getMatrixEGraph(),null,List.of(),live);
        var result=new IdentityHashMap<EGraphNode,StructuralDeclarationOrigin>();
        for(var entry:originals.entrySet()){
            var current=live.get(entry.getKey());if(current==null||entry.getValue()==null)continue;
            try{var origin=new StructuralDeclarationOrigin(phase,entry.getValue(),current);result.put(origin.original.declaration,origin);result.put(origin.live.declaration,origin);}
            catch(IllegalArgumentException unsupported){/* No authority is issued for a changed or unsupported original group. */}
        }
        return result;
    }
    private static List<Context> context(EGraphNode root,Located group){
        var result=new ArrayList<Context>();var node=root;for(int index:group.path){result.add(new Context(node));node=node.getChildren().get(index);}return List.copyOf(result);
    }
    private static boolean at(EGraphNode root,Located group,List<Context> context){
        EGraphNode node=root,parent=null;int step=0;for(int index:group.path){if(!context.get(step++).valid(node))return false;parent=node;if(index<0||index>=node.getChildren().size())return false;node=node.getChildren().get(index);}
        return node==group.declaration&&parent==group.owner;
    }
    public boolean valid(){
        if(!phase.isFrozenForCertification()||!phasePath.equals(phase.getPhasePath())||phaseRole!=phase.getTemporalOp()
                ||phase.getCertificationMatrixEGraph()!=originalRoot||phase.getMatrixEGraph()!=liveRoot
                ||!at(originalRoot,original,originalContext)||!at(liveRoot,live,liveContext)||!originalSnapshot.matches(original.declaration,true)
                ||!liveSnapshot.matches(live.declaration,true))return false;
        for(var s:slots){if(!phase.getLocalQuantiVars().stream().anyMatch(q->q==s.binding)
                ||!s.binding.getDeclarationFrames().stream().anyMatch(f->f==s.frame)||!s.binding.isFrozenForCertification()
                ||!sameType(s.type,s.binding.getExactAlloyType())||s.quantifier!=s.binding.getQuantifier()||s.cardinality!=s.binding.getCardinality()
                ||s.disjointness!=s.binding.getDisjointnessClass()||!s.path.equals(s.binding.getBindingPath())||!s.coordinate.equals(s.binding.getDeBruijnKey())
                ||!phase.matchesOriginalDeclarationDomain(s.frame,original.declaration)||s.dependencies.size()!=s.frame.dependencies().size())return false;
            for(int i=0;i<s.dependencies.size();i++)if(s.dependencies.get(i).binding!=s.frame.dependencies().get(i)||!s.dependencies.get(i).valid())return false;
        }
        return true;
    }
    public boolean admits(EGraphNode comparison){return valid()&&comparison.comparisonDeclarationSource()==live.declaration
        &&comparison.hasUnchangedComparisonDeclarationInvocations()&&liveSnapshot.matches(comparison,false);}
    public EGraphNode originalDeclaration(){return original.declaration;}
    public EGraphNode originalOwner(){return original.owner;}
    public EGraphNode projectionDeclaration(){return live.declaration;}
    public EGraphNode projectionOwner(){return live.owner;}
    public JSONObject evidence(){
        require(valid(),"Original group changed");var rows=new JSONArray();
        for(var s:slots){var deps=new JSONArray();for(var q:s.frame.dependencies())deps.put(new JSONObject().put("coordinate",q.getDeBruijnKey()).put("type",q.getExactAlloyType().stableString()));
            rows.put(new JSONObject().put("occurrence",s.frame.variableOccurrence()).put("tuple",s.frame.tupleSlot()).put("binding",s.coordinate)
                .put("normalizedOccurrence",original.declaration.getChildren().get(s.frame.tupleSlot()+1).getSourceOccurrenceLineage())
                .put("type",s.type.stableString()).put("quantifier",s.quantifier).put("cardinality",s.cardinality).put("disjointness",s.disjointness)
                .put("domain",s.frame.domain().stableString()).put("dependencies",deps));}
        return new JSONObject().put("occurrence",original.declaration.getSourceOccurrenceLineage()).put("opcode",original.declaration.getOpcode())
            .put("originalDeclarationOccurrence",slots.get(0).frame.declarationOccurrence())
            .put("role","CONTROL").put("ownerOccurrence",original.owner.getSourceOccurrenceLineage()).put("ownerOpcode",original.owner.getOpcode())
            .put("phase",phasePath).put("phaseRole",phaseRole).put("originalPath",new JSONArray(original.path)).put("livePath",new JSONArray(live.path))
            .put("domainOccurrence",original.declaration.getChildren().get(0).getSourceOccurrenceLineage()).put("slots",rows);
    }
}
