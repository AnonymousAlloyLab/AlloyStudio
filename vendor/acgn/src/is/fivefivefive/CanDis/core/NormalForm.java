package is.fivefivefive.CanDis.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.theory.LeanVerifiedRewrite;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.core.QuantiVar.Cardinality;
import is.fivefivefive.CanDis.core.QuantiVar.Quantifier;

import java.util.HashMap;

/**
 * This class encodes the normal form of a formula or function, which consists of a flat prenex binding list and a matrix e-graph representation of the formula. 
 * The normal form can be used for distance calculation, as well as for other analyses and transformations on the formula.
 * It is the locus of control for the visitor that generates the normal form from the original formula. 
 */
public class NormalForm {
    private static final AtomicLong NEXT_TEMPORAL_REFERENCE_AUTHORITY_ID =
            new AtomicLong(1L);
    @FunctionalInterface
    public interface NormalizationObserver {
        void onStage(String stage, NormalForm normalForm);
    }

    private static final NormalizationObserver NO_OBSERVER = (stage, normalForm) -> { };

    // matrix e-graph representation of the formula, where each node is a subformula, and edges represent the structure of the formula.
    private EGraphNode matrixEGraphRoot;
    // Post-prenex/NNF source tree retained before ACI folding for certified law replay.
    private EGraphNode certificationMatrixEGraphRoot;
    private List<QuantiVar> params; // the parameters of the formula or function, in the order they appear in the original formula or function declaration.
    private List<QuantiVar> matrixQuantiVars; // the quantified variables in the matrix, in the order they appear in the formula.
    private List<QuantiVar> inheritedQuantiVars; // bindings owned by ancestor temporal phases and visible in this matrix.
    private List<PhaseLocalBindingImport> phaseLocalBindingImports;
    private final Map<NormalForm, List<PhaseLocalBindingImport>> temporalChildBindingImports;
    private final Map<String, LocalBindingSeed> localBindingSeeds;
    // Only prenexRelDecl can create these witnesses. Publicly assembled binding
    // lists cannot manufacture a grouped ONE owner by relabelling SOME slots.
    private final List<GroupedOneOrigin> groupedOneOrigins = new ArrayList<>();
    private final Map<QuantiVar,List<GuardedProjection>> guardedProjections = new java.util.IdentityHashMap<>();
    private final Map<QuantiVar,List<GuardEditSource>> guardEditSources = new java.util.IdentityHashMap<>();
    private final Map<QuantiVar,List<BranchRouteSource>> branchRouteSources = new java.util.IdentityHashMap<>();
    private final Map<EGraphNode,StructuralDeclarationOrigin> structuralDeclarationOrigins = new java.util.IdentityHashMap<>();
    record RelocatedDeclaration(EGraphNode declaration,List<EGraphNode> slots,List<DeclarationFrame> frames) { }
    private final Map<Long,RelocatedDeclaration> relocatedDeclarations = new HashMap<>();
    private final List<UnusedBindingReduction> unusedBindingReductions = new ArrayList<>();
    /** Pre-prenex source coordinates, including removed slots, retained for audit. */
    public record UnusedBindingReduction(String scope, Opcode quantifier,
            long declarationLineage, List<String> sourceNames, List<String> alphaNames,
            List<String> sourceIdentities, List<Long> sourceLineages,
            List<String> exactTypes, List<Integer> retainedSourceSlots, String residual) {
        public UnusedBindingReduction {
            sourceNames = List.copyOf(sourceNames);
            alphaNames = List.copyOf(alphaNames);
            sourceIdentities = List.copyOf(sourceIdentities);
            sourceLineages = List.copyOf(sourceLineages);
            exactTypes = List.copyOf(exactTypes);
            retainedSourceSlots = List.copyOf(retainedSourceSlots);
        }
    }
    public List<UnusedBindingReduction> getUnusedBindingReductions() {
        synchronized (lifecycleLock) { return List.copyOf(unusedBindingReductions); }
    }
    public record UnusedBindingProjection(String sourceOwner, int sourceSlot,
            String normalizedSlot, String sourceIdentity, String exactType) { }
    private final List<UnusedBindingProjection> unusedBindingProjections = new ArrayList<>();
    public List<UnusedBindingProjection> getUnusedBindingProjections() {
        synchronized (lifecycleLock) { return List.copyOf(unusedBindingProjections); }
    }
    private record GroupedOneOrigin(long declaration, List<QuantiVar> bindings,
            List<String> keys, String owner) { }
    private final List<DeclarationFrame> issuedDeclarationFrames = new ArrayList<>();
    private final Map<DeclarationFrame, SourceDeclarationOccurrence> issuedDeclarationOrigins = new java.util.IdentityHashMap<>();
    private final Map<DeclarationFrame, Boolean> issuedDomainLowerings = new java.util.IdentityHashMap<>();
    private final Map<DeclarationFrame, DomainGuardOrigin> issuedDomainGuards = new java.util.IdentityHashMap<>();
    private StructuralKey issuedDomainContext;

    /** Private issuance authority, never reconstructed from a matching body atom. */
    static final class DomainGuardOrigin {
        private final DeclarationFrame frame;
        private final int index;
        private DomainGuardOrigin(DeclarationFrame frame, int index) {
            this.frame = frame;
            this.index = index;
        }
    }
    static final class DomainContextOrigin {
        private final long occurrence;
        private final Opcode sourceOpcode, currentOpcode;
        private DomainContextOrigin(EGraphNode node) {
            occurrence = node.getSourceOccurrenceLineage();
            sourceOpcode = currentOpcode = node.getOpcode();
        }
        private DomainContextOrigin(DomainContextOrigin source, Opcode currentOpcode) {
            occurrence = source.occurrence;
            sourceOpcode = source.sourceOpcode;
            this.currentOpcode = currentOpcode;
        }
    }
    private final Map<EGraphNode, Long> issuedMatrixReads = new java.util.IdentityHashMap<>();
    private record SourceDeclarationOccurrence(long declaration, long variable, int tupleSlot,
            String bindingPath, String coordinate, BindingSignature payload, StructuralKey domain,
            List<QuantiVar> dependencies) {
        private SourceDeclarationOccurrence { dependencies = List.copyOf(dependencies); }
    }
    /** Issued only while consuming an actual declaration, before its domain is lowered. */
    public static final class DeclarationFrame {
        private final NormalForm owner;
        private final QuantiVar variable;
        private final long declarationOccurrence, variableOccurrence;
        private final int tupleSlot;
        private final StructuralKey domain;
        private final List<QuantiVar> dependencies;
        private final is.fivefivefive.ACGN.alloy.SigSymbol signature;
        private final GuardEditDomain guardEditDomain;
        private final DomainNormalization domainNormalization;
        private final String phasePath, bindingPath, coordinate, name, typeName, carrierType;
        private final TemporalOp phaseRole;
        private final int vectorIndex, variableId, disjointness;
        private final Quantifier quantifier;
        private final Cardinality cardinality;
        private final is.fivefivefive.ACGN.alloy.ExactAlloyType exactType;
        private final java.util.Set<String> sourceAliases;
        private final SourceDeclarationOccurrence sourceOrigin;

        private DeclarationFrame(NormalForm owner, QuantiVar variable, QuantiVar sourceCandidate, EGraphNode declaration,
                EGraphNode sourceVariable, int tupleSlot, StructuralKey domain, List<QuantiVar> dependencies,
                is.fivefivefive.ACGN.alloy.SigSymbol signature,GuardEditDomain guardEditDomain,
                DomainNormalization domainNormalization) {
            this.owner = owner;
            this.variable = variable;
            this.declarationOccurrence = declaration.getSourceOccurrenceLineage();
            this.variableOccurrence = sourceVariable.getSourceOccurrenceLineage();
            this.tupleSlot = tupleSlot;
            this.domain = domain;
            this.dependencies = List.copyOf(dependencies);
            this.signature = signature;
            this.guardEditDomain = guardEditDomain;
            this.domainNormalization = domainNormalization;
            phasePath = owner.phasePath;
            phaseRole = owner.temporalOp;
            vectorIndex = identityIndex(owner.matrixQuantiVars, variable);
            variableId = variable.getId();
            bindingPath = variable.getBindingPath();
            coordinate = variable.getDeBruijnKey();
            name = variable.getName();
            typeName = variable.getTypeName();
            carrierType = variable.getCarrierTypeName();
            quantifier = variable.getQuantifier();
            cardinality = variable.getCardinality();
            disjointness = variable.getDisjointnessClass();
            exactType = bindingTypeEvidence(sourceVariable, declaration.getChildren().get(0));
            var aliases = new java.util.HashSet<String>();
            for (String alias : new String[]{sourceVariable.getSourceName(), sourceVariable.getSemanticIdentity()})
                if (alias != null && !alias.isEmpty()) aliases.add(alias);
            sourceAliases = java.util.Set.copyOf(aliases);
            sourceOrigin = new SourceDeclarationOccurrence(declarationOccurrence, variableOccurrence, tupleSlot,
                    sourceCandidate.getBindingPath(), sourceCandidate.getDeBruijnKey(),
                    new BindingSignature(sourceCandidate), domain, dependencies);
            if (!sourceOrigin.payload.equals(new BindingSignature(variable)))
                throw new IllegalStateException("Declaration representative changed source payload");
        }
        public NormalForm owner() { return owner; }
        public QuantiVar variable() { return variable; }
        public long declarationOccurrence() { return declarationOccurrence; }
        public long variableOccurrence() { return variableOccurrence; }
        public int tupleSlot() { return tupleSlot; }
        public StructuralKey domain() { return domain; }
        public List<QuantiVar> dependencies() { return dependencies; }

        private boolean matchesIssuedBinding(NormalForm original, QuantiVar current, int index) {
            return owner == original && variable == current && vectorIndex == index
                    && original.issuedDeclarationOrigins.get(this) == sourceOrigin
                    && declarationOccurrence == sourceOrigin.declaration && variableOccurrence == sourceOrigin.variable
                    && tupleSlot == sourceOrigin.tupleSlot && domain != null && domain.equals(sourceOrigin.domain)
                    && sameIdentities(dependencies, sourceOrigin.dependencies)
                    && sourceOrigin.payload.equals(new BindingSignature(current))
                    && sourceOrigin.coordinate.equals(sourceOrigin.bindingPath + "#" + tupleSlot
                        + ":" + cardinality + ":" + normalizeType(typeName))
                    && phasePath.equals(original.phasePath) && phaseRole == original.temporalOp
                    && variableId == current.getId() && name.equals(current.getName())
                    && bindingPath.equals(current.getBindingPath()) && coordinate.equals(current.getDeBruijnKey())
                    && typeName.equals(current.getTypeName()) && carrierType.equals(current.getCarrierTypeName())
                    && quantifier == current.getQuantifier() && cardinality == current.getCardinality()
                    && disjointness == current.getDisjointnessClass()
                    && exactType != null && exactType.hasParserAuthenticatedAncestry()
                    && exactType.sameOccurrenceEvidenceAs(current.getExactAlloyType())
                    && current.isFrozenForCertification() && domain != null && tupleSlot >= 0;
        }
    }

    private record ProjectionMatrix(String kind, is.fivefivefive.ACGN.alloy.SigSymbol signature,
            List<ProjectionMatrix> children) {
        private ProjectionMatrix { children = List.copyOf(children); }
        boolean same(ProjectionMatrix other) {
            if (other == null || !kind.equals(other.kind) || children.size() != other.children.size()
                    || (signature == null) != (other.signature == null)
                    || signature != null && !signature.isSameParserSignatureAs(other.signature)) return false;
            for (int i=0;i<children.size();i++) if (!children.get(i).same(other.children.get(i))) return false;
            return true;
        }
        org.json.JSONObject evidence() {
            var cs=new org.json.JSONArray();for(var child:children)cs.put(child.evidence());
            return new org.json.JSONObject().put("kind",kind).put("signature",signature==null?org.json.JSONObject.NULL:signature.getSemanticIdentity()).put("children",cs);
        }
    }

    /** Private pre-lowering authority. Neither an endpoint nor a receipt can issue it. */
    private static final class GuardedProjection {
        final NormalForm owner;
        final DeclarationFrame frame;
        final Quantifier quantifier;
        final is.fivefivefive.ACGN.alloy.SigSymbol domain,carrier;
        final ProjectionMatrix sourceBody,loweredBody;
        private GuardedProjection(NormalForm owner,DeclarationFrame frame,ProjectionMatrix sourceBody) {
            this.owner=owner;this.frame=frame;this.quantifier=frame.variable.getQuantifier();
            this.domain=frame.signature;this.carrier=domain.parserUnaryCarrier();this.sourceBody=sourceBody;
            var guard=new ProjectionMatrix("member",domain,List.of());
            this.loweredBody=domain.isSameParserSignatureAs(carrier)?sourceBody:
                new ProjectionMatrix(quantifier==Quantifier.ALL?"implies":"conj",null,List.of(guard,sourceBody));
        }
    }

    private record ProjectionRead(NormalForm owner,EGraphNode variable,EGraphNode signature) { }

    private record BranchMember(Opcode predicate,is.fivefivefive.ACGN.alloy.SigSymbol signature) {
        boolean same(BranchMember other){return other!=null&&predicate==other.predicate&&signature.isSameParserSignatureAs(other.signature);}
    }
    private static final class BranchRouteSource {
        final NormalForm owner;
        final DeclarationFrame frame;
        final TemporalReferenceAuthority reference;
        final List<BranchMember> branches;
        private BranchRouteSource(NormalForm owner,DeclarationFrame frame,TemporalReferenceAuthority reference,List<BranchMember> branches){
            this.owner=owner;this.frame=frame;this.reference=reference;this.branches=List.copyOf(branches);
        }
    }
    private BranchMember branchMember(QuantiVar variable,boolean original){
        var root=matrixEGraphRoot;
        if(!guardEditPhaseRoot(root))return null;
        var member=root.getChildren().get(0);var cs=member.getChildren();
        if((member.getOpcode()!=Opcode.IN&&member.getOpcode()!=Opcode.NOT_IN)||!EGraphNode.hasBooleanFormulaType(member)
                ||cs.size()!=2||cs.get(0).getOpcode()!=Opcode.VARIABLE||cs.get(1).authenticatedSignature()==null
                ||cs.get(0).getExactAlloyType()==null||!cs.get(0).getExactAlloyType().sameOccurrenceEvidenceAs(variable.getExactAlloyType()))return null;
        if(original?!variable.getOriginalNames().contains(cs.get(0).getSemanticIdentity()):!boundGuardVariable(cs.get(0),variable))return null;
        return new BranchMember(member.getOpcode(),cs.get(1).authenticatedSignature());
    }
    private void issueBranchRouteSource(QuantiVar variable,EGraphNode body){
        if(body==null||body.getOpcode()!=Opcode.REF||variable.getCardinality()!=Cardinality.ONE||variable.isDisj()
                ||variable.getQuantifier()!=Quantifier.ALL&&variable.getQuantifier()!=Quantifier.SOME)return;
        int[] address=temporalReferenceTarget(body);if(address==null)return;
        var authority=temporalReferenceAuthorities.get(body.temporalReferenceAuthorityId());
        if(authority==null||!authority.matches(body,address[0],address[1]))return;
        var members=new ArrayList<BranchMember>();
        for(var branch:authority.children){var member=branch.branchMember(variable,true);if(member==null)return;members.add(member);}
        for(var frame:variable.getDeclarationFrames())if(frame.owner==this&&frame.signature!=null
                &&frame.signature.parserUnaryCarrier()!=null&&frame.signature.isSameParserSignatureAs(frame.signature.parserUnaryCarrier()))
            branchRouteSources.computeIfAbsent(variable,k->new ArrayList<>()).add(new BranchRouteSource(this,frame,authority,members));
    }

    static final class BranchRouteEndpoint {
        private final NormalForm owner;
        private final QuantiVar binding;
        private final BranchRouteSource source;
        private final EGraphNode root,reference;
        private final List<NormalForm> branches;
        private final List<List<EGraphNode>> nodes;
        private BranchRouteEndpoint(NormalForm owner,QuantiVar binding,BranchRouteSource source,EGraphNode root,EGraphNode reference,
                List<NormalForm> branches,List<List<EGraphNode>> nodes){
            this.owner=owner;this.binding=binding;this.source=source;this.root=root;this.reference=reference;
            this.branches=List.copyOf(branches);this.nodes=List.copyOf(nodes);
        }
        NormalForm owner(){return owner;}
        NormalForm branch(int i){return branches.get(i);}
        int arity(){return branches.size();}
        EGraphNode root(){return root;}
        EGraphNode reference(){return reference;}
        List<EGraphNode> nodes(int i){return nodes.get(i);}
        boolean valid(){
            var next=owner.branchRouteEndpoint(binding);
            if(next==null||next.source!=source||next.root!=root||next.reference!=reference||next.nodes.size()!=nodes.size())return false;
            for(int i=0;i<nodes.size();i++)for(int j=0;j<4;j++)if(next.nodes.get(i).get(j)!=nodes.get(i).get(j))return false;
            return true;
        }
        boolean sameBody(int index,BranchRouteEndpoint other,int otherIndex){
            return source.branches.get(index).same(other.source.branches.get(otherIndex));
        }
        boolean oppositeBody(int i,int j){
            var a=source.branches.get(i);var b=source.branches.get(j);
            return a.predicate!=b.predicate&&a.signature.isSameParserSignatureAs(b.signature);
        }
        org.json.JSONObject evidence(){
            var out=new org.json.JSONObject().put("owner",owner.phasePath).put("binding",binding.getDeBruijnKey())
                .put("ownerOperator",owner.temporalOp.name()).put("quantifier",binding.getQuantifier().name())
                .put("declarationOccurrence",source.frame.declarationOccurrence).put("domain",source.frame.domain.stableString())
                .put("referenceOccurrence",reference.getSourceOccurrenceLineage()).put("referenceAuthority",source.reference.authorityId)
                .put("parserReferenceOccurrence",source.reference.parserOccurrenceId).put("arity",arity())
                .put("dependencies",CanonicalDistance.dependencyOccurrenceEvidence(source.frame.dependencies));
            var rows=new org.json.JSONArray();
            for(int i=0;i<nodes.size();i++){
                var ns=new org.json.JSONArray();for(var n:nodes.get(i))ns.put(new org.json.JSONObject().put("occurrence",n.getSourceOccurrenceLineage())
                    .put("opcode",n.getOpcode().name()).put("type",n.getExactAlloyType()==null?"BOOL/owner-issued-phase-root":n.getExactAlloyType().stableString())
                    .put("children",new org.json.JSONArray(n.getChildren().stream().map(EGraphNode::getSourceOccurrenceLineage).toList())));
                rows.put(new org.json.JSONObject().put("role",i).put("phase",branches.get(i).phasePath).put("operator",branches.get(i).temporalOp.name()).put("nodes",ns));
            }
            return out.put("branches",rows);
        }
    }
    BranchRouteEndpoint branchRouteEndpoint(QuantiVar variable){
        if(!frozenForCertification||!variable.isFrozenForCertification()||variable.getCardinality()!=Cardinality.ONE||variable.isDisj())return null;
        var seeds=comparisonSource().branchRouteSources.get(variable);
        if(seeds==null||seeds.size()!=1||variable.getDeclarationFrames().size()!=1)return null;
        var seed=seeds.get(0);
        if(seed.owner!=comparisonSource()||seed.frame.variable!=variable||!ownsDeclarationFrame(seed.frame)||!guardEditPhaseRoot(matrixEGraphRoot))return null;
        var ref=matrixEGraphRoot.getChildren().get(0);int[] address=temporalReferenceTarget(ref);if(address==null)return null;
        var actual=temporalReferenceAuthorities.get(ref.temporalReferenceAuthorityId());
        if(actual==null||!actual.matches(ref,address[0],address[1])||actual.sourceClaim!=seed.reference.sourceClaim
                ||actual.children.size()!=seed.branches.size())return null;
        var branches=new ArrayList<NormalForm>();var nodes=new ArrayList<List<EGraphNode>>();
        var unique=Collections.newSetFromMap(new java.util.IdentityHashMap<EGraphNode,Boolean>());
        for(int i=0;i<actual.children.size();i++){
            if(actual.children.get(i)!=seed.reference.children.get(i))return null;
            if(!seed.branches.get(i).same(actual.children.get(i).branchMember(variable,false)))return null;
            NormalForm selected=null;for(var b:comparisonTemporalChildren())if(b.comparisonSource()==actual.children.get(i)){if(selected!=null)return null;selected=b;}
            if(selected==null||!seed.branches.get(i).same(selected.branchMember(variable,false)))return null;
            var root=selected.matrixEGraphRoot;var expr=root.getChildren().get(0);
            var ns=List.of(root,expr,expr.getChildren().get(0),expr.getChildren().get(1));
            for(var n:ns)if(!unique.add(n))return null;
            branches.add(selected);nodes.add(ns);
        }
        return new BranchRouteEndpoint(this,variable,seed,matrixEGraphRoot,ref,branches,nodes);
    }

    static boolean branchZeroDecision(is.fivefivefive.CanDis.metric.MatrixDecision<EGraphNode> decision,EGraphNode a,EGraphNode b){
        return PaidGuardEditMatch.zeroTree(decision,a,b);
    }

    private record GuardEditDomain(is.fivefivefive.ACGN.alloy.SigSymbol carrier,
            is.fivefivefive.ACGN.alloy.SigSymbol subset) {
        private static GuardEditDomain from(EGraphNode domain) {
            if(domain.getExactAlloyType()==null||domain.getExactAlloyType().kind()!=is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                    ||!domain.getExactAlloyType().hasParserAuthenticatedAncestry()
                    ||domain.getExactAlloyType().relationArity()!=1)return null;
            var direct=domain.authenticatedSignature();
            if(direct!=null) {
                var carrier=direct.parserUnaryCarrier();
                return carrier!=null&&direct.isSameParserSignatureAs(carrier)?new GuardEditDomain(carrier,null):null;
            }
            if(domain.getOpcode()!=Opcode.INTERSECT||domain.getChildren().size()!=2)return null;
            var x=domain.getChildren().get(0).authenticatedSignature();var y=domain.getChildren().get(1).authenticatedSignature();
            if(x==null||y==null)return null;
            for(var pair:List.of(List.of(x,y),List.of(y,x))) {
                var carrier=pair.get(0).parserUnaryCarrier();var subset=pair.get(1);
                if(carrier!=null&&pair.get(0).isSameParserSignatureAs(carrier)
                        &&!subset.isSameParserSignatureAs(carrier)&&subset.isParserCertifiedSubsignatureOf(carrier)
                        &&domain.getExactAlloyType().sameOccurrenceEvidenceAs(carrier.parserExactType())
                        &&domain.getChildren().stream().allMatch(c->c.getSemanticProfile().equals(domain.getSemanticProfile())))return new GuardEditDomain(carrier,subset);
            }
            return null;
        }
    }

    private static final class GuardEditSource {
        final NormalForm owner,future;
        final DeclarationFrame frame;
        final TemporalReferenceAuthority reference;
        final ProjectionMatrix futureBody;
        final Quantifier quantifier;
        private GuardEditSource(NormalForm owner,DeclarationFrame frame,TemporalReferenceAuthority reference,ProjectionMatrix futureBody) {
            this.owner=owner;this.frame=frame;this.reference=reference;this.future=reference.children.get(0);
            this.futureBody=futureBody;this.quantifier=frame.variable.getQuantifier();
        }
    }

    private void issueGuardEditSource(QuantiVar variable,EGraphNode body) {
        if(variable.getCardinality()!=Cardinality.ONE||variable.isDisj()||body==null||body.getOpcode()!=Opcode.REF)return;
        int[] address=temporalReferenceTarget(body);
        if(address==null||address[1]!=1)return;
        var authority=temporalReferenceAuthorities.get(body.temporalReferenceAuthorityId());
        if(authority==null||!authority.matches(body,address[0],1)||authority.children.get(0).temporalOp!=TemporalOp.ALWAYS)return;
        var child=authority.children.get(0);
        var model=child.projectionMatrix(child.matrixEGraphRoot,variable,true,new ArrayList<>(),Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if(model==null||!model.kind.equals("member"))return;
        for(var frame:variable.getDeclarationFrames())if(frame.owner==this&&frame.guardEditDomain!=null) {
            boolean guarded=frame.guardEditDomain.subset!=null;
            if(variable.getQuantifier()!=(guarded?Quantifier.ALL:Quantifier.SOME))continue;
            if(guarded&&!model.signature.isSameParserSignatureAs(frame.guardEditDomain.subset))continue;
            guardEditSources.computeIfAbsent(variable,k->new ArrayList<>()).add(new GuardEditSource(this,frame,authority,model));
        }
    }

    private boolean boundGuardVariable(EGraphNode node,QuantiVar binding) {
        if(node.getOpcode()!=Opcode.VARIABLE||node.getExactAlloyType()==null
                ||!node.getExactAlloyType().sameOccurrenceEvidenceAs(binding.getExactAlloyType()))return false;
        for(var bindings:List.of(params,matrixQuantiVars,inheritedQuantiVars,getLocalQuantiVars()))
            for(var candidate:bindings)if(candidate==binding&&binding.getName().equals(node.getAlphaName()))return true;
        return false;
    }

    private record GuardEditShape(List<EGraphNode> nodes,NormalForm future,EGraphNode futureRoot,
            List<ProjectionRead> futureReads) { }

    private GuardEditShape guardEditShape(GuardEditSource seed,QuantiVar binding) {
        if(!ownsDeclarationFrame(seed.frame)||seed.owner!=comparisonSource()||seed.frame.variable!=binding
                ||binding.getCardinality()!=Cardinality.ONE||binding.isDisj()||binding.getQuantifier()!=seed.quantifier)return null;
        var domain=seed.frame.guardEditDomain;
        if(domain==null||!domain.carrier.isParserCertifiedSubsignatureOf(domain.carrier)
                ||domain.subset!=null&&!domain.subset.isParserCertifiedSubsignatureOf(domain.carrier))return null;
        var root=matrixEGraphRoot;
        if(!guardEditPhaseRoot(root))return null;
        var nodes=new ArrayList<EGraphNode>();nodes.add(root);var body=root.getChildren().get(0);nodes.add(body);
        EGraphNode reference=body;
        if(domain.subset!=null) {
            if(body.getOpcode()!=Opcode.OR||body.getChildren().size()!=2||!EGraphNode.hasBooleanRewriteAuthority(body))return null;
            var guard=body.getChildren().get(0);reference=body.getChildren().get(1);
            if(guard.getOpcode()!=Opcode.NOT_IN||guard.getChildren().size()!=2||!EGraphNode.hasBooleanFormulaType(guard))return null;
            var value=guard.getChildren().get(0);var signature=guard.getChildren().get(1);
            if(!boundGuardVariable(value,binding)||signature.authenticatedSignature()==null
                    ||!signature.authenticatedSignature().isSameParserSignatureAs(domain.subset))return null;
            nodes.add(guard);nodes.add(value);nodes.add(signature);nodes.add(reference);
        }
        int[] address=temporalReferenceTarget(reference);
        if(address==null||address[1]!=1)return null;
        var actual=temporalReferenceAuthorities.get(reference.temporalReferenceAuthorityId());
        if(actual==null||!actual.matches(reference,address[0],1)||actual.sourceClaim!=seed.reference.sourceClaim
                ||actual.children.get(0)!=seed.future)return null;
        NormalForm future=null;
        for(var child:comparisonTemporalChildren())if(child.comparisonSource()==seed.future){if(future!=null)return null;future=child;}
        if(future==null||future.temporalOp!=TemporalOp.ALWAYS||!guardEditPhaseRoot(future.matrixEGraphRoot))return null;
        var reads=new ArrayList<ProjectionRead>();
        var model=future.projectionMatrix(future.matrixEGraphRoot,binding,false,reads,Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if(!seed.futureBody.same(model)||reads.size()!=1)return null;
        return new GuardEditShape(List.copyOf(nodes),future,future.matrixEGraphRoot,List.copyOf(reads));
    }

    private static boolean guardEditPhaseRoot(EGraphNode root) {
        return root.getOpcode()==Opcode.TEMPORALROOT&&root.getMetatype()==Metatype.BOOLEAN&&root.getChildren().size()==1
            &&(root.getExactAlloyType()==null||root.getExactAlloyType().kind()==is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL);
    }

    static final class PaidGuardEditMatch {
        private final NormalForm a,b;
        private final QuantiVar source,target;
        private final GuardEditSource expectedSource,expectedTarget;
        private final GuardEditShape left,right;
        private is.fivefivefive.CanDis.metric.MatrixDecision<EGraphNode> selectedOwner,selectedFuture;
        private PaidGuardEditMatch(NormalForm a,NormalForm b,QuantiVar source,QuantiVar target,GuardEditSource ea,GuardEditSource eb,GuardEditShape left,GuardEditShape right) {
            this.a=a;this.b=b;this.source=source;this.target=target;this.expectedSource=ea;this.expectedTarget=eb;this.left=left;this.right=right;
        }
        NormalForm sourceOwner(){return a;}
        NormalForm targetOwner(){return b;}
        NormalForm sourceFuture(){return left.future;}
        NormalForm targetFuture(){return right.future;}
        boolean valid() {
            var next=a.paidGuardEditMatch(b,source,target);
            if(next==null||next.expectedSource!=expectedSource||next.expectedTarget!=expectedTarget
                    ||next.left.futureRoot!=left.futureRoot||next.right.futureRoot!=right.futureRoot)return false;
            for(int i=0;i<left.nodes.size();i++)if(next.left.nodes.get(i)!=left.nodes.get(i))return false;
            for(int i=0;i<right.nodes.size();i++)if(next.right.nodes.get(i)!=right.nodes.get(i))return false;
            return true;
        }
        private static boolean zeroTree(is.fivefivefive.CanDis.metric.MatrixDecision<EGraphNode> decision,EGraphNode a,EGraphNode b) {
            if(decision.source()!=a||decision.target()!=b||!decision.kind().equals("node")||decision.cost()!=0||decision.headCost()!=0
                    ||a.getOpcode()!=b.getOpcode()||!a.getSemanticProfile().equals(b.getSemanticProfile())
                    ||!sameSelectedType(a,b)
                    ||a.getChildren().size()!=b.getChildren().size()||decision.children().size()!=a.getChildren().size())return false;
            for(int i=0;i<decision.children().size();i++){var edge=decision.children().get(i);
                if(edge.leftIndex()!=i||edge.rightIndex()!=i||!zeroTree(edge.decision(),a.getChildren().get(i),b.getChildren().get(i)))return false;}
            return true;
        }
        private static boolean sameSelectedType(EGraphNode a,EGraphNode b) {
            if(a.getOpcode()==Opcode.TEMPORALROOT||b.getOpcode()==Opcode.TEMPORALROOT) {
                if(!guardEditPhaseRoot(a)||!guardEditPhaseRoot(b))return false;
            }
            if(a.getExactAlloyType()!=null)return a.getExactAlloyType().sameOccurrenceEvidenceAs(b.getExactAlloyType());
            // NormalForm's owner-issued transparent phase roots have constructor Boolean type, not parser leaf metadata.
            return b.getExactAlloyType()==null&&a.getOpcode()==Opcode.TEMPORALROOT&&b.getOpcode()==Opcode.TEMPORALROOT
                &&a.getMetatype()==Metatype.BOOLEAN&&b.getMetatype()==Metatype.BOOLEAN
                &&a.getChildren().size()==1&&b.getChildren().size()==1;
        }
        boolean bindSelected(is.fivefivefive.CanDis.metric.MatrixDecision<EGraphNode> owner,is.fivefivefive.CanDis.metric.MatrixDecision<EGraphNode> future) {
            if(!valid()||owner.source()!=left.nodes.get(0)||owner.target()!=right.nodes.get(0)||!owner.kind().equals("node")
                    ||owner.cost()!=5||owner.headCost()!=0||owner.children().size()!=1)return false;
            var rootEdge=owner.children().get(0);var body=rootEdge.decision();
            if(rootEdge.leftIndex()!=0||rootEdge.rightIndex()!=0||!body.kind().equals("node")||body.source()!=left.nodes.get(1)
                    ||body.target()!=right.nodes.get(1)||body.cost()!=5||body.headCost()!=1||body.children().size()!=2)return false;
            boolean remove=left.nodes.size()==6;var guarded=remove?left:right;
            for(int i=0;i<2;i++){var edge=body.children().get(i);var d=edge.decision();var node=guarded.nodes.get(i==0?2:5);
                if(edge.leftIndex()!=(remove?i:-1)||edge.rightIndex()!=(remove?-1:i)||!d.kind().equals(remove?"delete":"insert")
                        ||d.source()!=(remove?node:null)||d.target()!=(remove?null:node)||d.cost()!=(i==0?3:1))return false;}
            if(!zeroTree(future,left.futureRoot,right.futureRoot))return false;
            selectedOwner=owner;selectedFuture=future;return true;
        }
        boolean selectedValid(){return selectedOwner!=null&&selectedFuture!=null&&bindSelected(selectedOwner,selectedFuture);}
        org.json.JSONObject evidence() {
            var out=new org.json.JSONObject().put("direction",left.nodes.size()==6?"remove":"insert")
                .put("sourceOwner",a.phasePath).put("targetOwner",b.phasePath).put("sourceBinding",source.getDeBruijnKey()).put("targetBinding",target.getDeBruijnKey())
                .put("sourceDeclaration",expectedSource.frame.declarationOccurrence).put("targetDeclaration",expectedTarget.frame.declarationOccurrence);
            var guarded=left.nodes.size()==6?left:right;
            out.put("paidGuardOccurrence",guarded.nodes.get(3).getSourceOccurrenceLineage());
            for(var side:List.of("source","target")){var shape=side.equals("source")?left:right;var seed=side.equals("source")?expectedSource:expectedTarget;
                var snapshots=new org.json.JSONArray();for(var n:shape.nodes)snapshots.put(new org.json.JSONObject().put("opcode",n.getOpcode().name())
                    .put("occurrence",n.getSourceOccurrenceLineage()).put("type",n.getExactAlloyType()==null&&n.getOpcode()==Opcode.TEMPORALROOT
                        &&n.getMetatype()==Metatype.BOOLEAN?"BOOL/owner-issued-phase-root":n.getExactAlloyType().stableString())
                    .put("children",new org.json.JSONArray(n.getChildren().stream().map(EGraphNode::getSourceOccurrenceLineage).toList())));
                out.put(side+"Snapshot",snapshots).put(side+"Domain",seed.frame.domain.stableString())
                    .put(side+"Dependencies",CanonicalDistance.dependencyOccurrenceEvidence(seed.frame.dependencies))
                    .put(side+"RetainedRead",new org.json.JSONObject().put("owner",shape.future.phasePath).put("operator",shape.future.temporalOp.name())
                        .put("occurrence",shape.futureReads.get(0).variable.getSourceOccurrenceLineage()).put("futureRoot",shape.futureRoot.getSourceOccurrenceLineage()));
            }return out;
        }
    }

    PaidGuardEditMatch paidGuardEditMatch(NormalForm other,QuantiVar source,QuantiVar target) {
        if(!frozenForCertification||!other.frozenForCertification||!phasePath.equals(other.phasePath)
                ||!matrixEGraphRoot.getSemanticProfile().equals(other.matrixEGraphRoot.getSemanticProfile()))return null;
        var a=comparisonSource().guardEditSources.get(source);var b=other.comparisonSource().guardEditSources.get(target);
        if(a==null||b==null||a.size()!=1||b.size()!=1||source.getDeclarationFrames().size()!=1||target.getDeclarationFrames().size()!=1)return null;
        var x=a.get(0);var y=b.get(0);var dx=x.frame.guardEditDomain;var dy=y.frame.guardEditDomain;
        if(dx==null||dy==null||(dx.subset==null)==(dy.subset==null)||!dx.carrier.isSameParserSignatureAs(dy.carrier)
                ||!x.futureBody.same(y.futureBody))return null;
        var left=guardEditShape(x,source);var right=other.guardEditShape(y,target);
        if(left==null||right==null)return null;
        var guarded=left.nodes.size()==6?left:right;
        if(guarded.nodes.get(3)==guarded.futureReads.get(0).variable
                ||guarded.nodes.get(3).getSourceOccurrenceLineage()==guarded.futureReads.get(0).variable.getSourceOccurrenceLineage())return null;
        return new PaidGuardEditMatch(this,other,source,target,x,y,left,right);
    }

    private ProjectionMatrix projectionMatrix(EGraphNode node,QuantiVar variable,boolean source,
            List<ProjectionRead> reads,java.util.Set<NormalForm> active) {
        if (node == null || !node.getSemanticProfile().equals(matrixEGraphRoot.getSemanticProfile())) return null;
        var cs=node.getChildren();
        switch(node.getOpcode()) {
            case TEMPORALROOT, PREDICATE -> {
                return cs.size()==1?projectionMatrix(cs.get(0),variable,source,reads,active):null;
            }
            case CONSTANT -> {
                return "true".equals(node.getSourceName())&&EGraphNode.hasBooleanFormulaType(node)
                    ?new ProjectionMatrix("truth",null,List.of()):null;
            }
            case IN -> {
                if (cs.size()!=2 || cs.get(0).getOpcode()!=Opcode.VARIABLE || cs.get(1).authenticatedSignature()==null
                        || !EGraphNode.hasBooleanFormulaType(node)) return null;
                var value=cs.get(0);var exact=value.getExactAlloyType();
                if (exact==null || variable.getExactAlloyType()==null
                        || !exact.sameOccurrenceEvidenceAs(variable.getExactAlloyType())) return null;
                boolean bound=false;
                if (source) bound=variable.getOriginalNames().contains(value.getSemanticIdentity());
                else for(var bindings:List.of(params,matrixQuantiVars,inheritedQuantiVars,getLocalQuantiVars()))
                    for(var q:bindings)if(q==variable&&q.getName().equals(value.getAlphaName()))bound=true;
                if(!bound)return null;
                reads.add(new ProjectionRead(this,value,cs.get(1)));
                return new ProjectionMatrix("member",cs.get(1).authenticatedSignature(),List.of());
            }
            case IMPLIES, AND, OR -> {
                if(cs.size()!=2 || !EGraphNode.hasBooleanRewriteAuthority(node) || !EGraphNode.hasBooleanOperands(node))return null;
                EGraphNode left=cs.get(0),right=cs.get(1);
                String kind=node.getOpcode()==Opcode.AND?"conj":"implies";
                ProjectionMatrix a;
                if(node.getOpcode()==Opcode.OR) {
                    if(left.getOpcode()!=Opcode.NOT_IN) {var swap=left;left=right;right=swap;}
                    if(left.getOpcode()!=Opcode.NOT_IN || left.getChildren().size()!=2
                            || !EGraphNode.hasBooleanFormulaType(left))return null;
                    // Interpret the actual NOT_IN operands without manufacturing an IN node.
                    var operands=left.getChildren();var value=operands.get(0);var sig=operands.get(1);
                    if(value.getOpcode()!=Opcode.VARIABLE || sig.authenticatedSignature()==null
                            || value.getExactAlloyType()==null || !value.getExactAlloyType().sameOccurrenceEvidenceAs(variable.getExactAlloyType()))return null;
                    boolean bound=source?variable.getOriginalNames().contains(value.getSemanticIdentity()):
                        java.util.stream.Stream.of(params,matrixQuantiVars,inheritedQuantiVars,getLocalQuantiVars())
                            .flatMap(List::stream).anyMatch(q->q==variable&&q.getName().equals(value.getAlphaName()));
                    if(!bound)return null;
                    reads.add(new ProjectionRead(this,value,sig));
                    a=new ProjectionMatrix("member",sig.authenticatedSignature(),List.of());
                } else a=projectionMatrix(left,variable,source,reads,active);
                var b=projectionMatrix(right,variable,source,reads,active);
                return a==null||b==null?null:new ProjectionMatrix(kind,null,List.of(a,b));
            }
            case REF -> {
                int[] target=temporalReferenceTarget(node);
                if(target==null || target[1]!=1)return null;
                var authority=temporalReferenceAuthorities.get(node.temporalReferenceAuthorityId());
                if(authority==null||!authority.matches(node,target[0],target[1]))return null;
                var child=authority.children.get(0);
                if(!source && comparisonSource!=null) {
                    NormalForm selected=null;
                    for(var candidate:comparisonTemporalChildren())if(candidate.comparisonSource()==child) {
                        if(selected!=null)return null;
                        selected=candidate;
                    }
                    if(selected==null)return null;
                    child=selected;
                }
                if(child.temporalOp!=TemporalOp.HISTORICALLY||!active.add(child))return null;
                var body=child.projectionMatrix(child.matrixEGraphRoot,variable,source,reads,active);active.remove(child);
                return body==null?null:new ProjectionMatrix("historically",null,List.of(body));
            }
            default -> {return null;}
        }
    }

    private void issueGuardedProjection(QuantiVar variable,EGraphNode body) {
        if(variable.getCardinality()!=Cardinality.ONE||variable.isDisj()
                || variable.getQuantifier()!=Quantifier.ALL&&variable.getQuantifier()!=Quantifier.SOME)return;
        var matrix=projectionMatrix(body,variable,true,new ArrayList<>(),Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if(matrix==null)return;
        for(var frame:variable.getDeclarationFrames())if(frame.owner==this&&frame.signature!=null&&frame.signature.parserUnaryCarrier()!=null) {
            guardedProjections.computeIfAbsent(variable,k->new ArrayList<>()).add(new GuardedProjection(this,frame,matrix));
        }
    }

    static final class GuardedProjectionMatch {
        private final NormalForm a,b;
        private final QuantiVar source,target;
        private final EGraphNode sourceRoot,targetRoot;
        private final List<ProjectionRead> sourceReads,targetReads;
        private GuardedProjectionMatch(NormalForm a,NormalForm b,QuantiVar source,QuantiVar target,
                List<ProjectionRead> sourceReads,List<ProjectionRead> targetReads) {
            this.a=a;this.b=b;this.source=source;this.target=target;
            this.sourceRoot=a.matrixEGraphRoot;this.targetRoot=b.matrixEGraphRoot;
            this.sourceReads=List.copyOf(sourceReads);this.targetReads=List.copyOf(targetReads);
        }
        boolean valid() {
            return a.matrixEGraphRoot==sourceRoot&&b.matrixEGraphRoot==targetRoot
                && a.guardedProjectionMatch(b,source,target)!=null;
        }
        org.json.JSONObject evidence() {
            var out=new org.json.JSONObject().put("sourceOwner",a.phasePath).put("targetOwner",b.phasePath)
                .put("sourceBinding",source.getDeBruijnKey()).put("targetBinding",target.getDeBruijnKey())
                .put("sourceRoot",sourceRoot.getSourceOccurrenceLineage()).put("targetRoot",targetRoot.getSourceOccurrenceLineage());
            for(var side:List.of("source","target")) {
                var reads=new org.json.JSONArray();
                for(var read:side.equals("source")?sourceReads:targetReads)reads.put(new org.json.JSONObject()
                    .put("owner",read.owner.phasePath).put("temporalOperator",read.owner.temporalOp.name())
                    .put("variableOccurrence",read.variable.getSourceOccurrenceLineage()).put("signatureOccurrence",read.signature.getSourceOccurrenceLineage()));
                out.put(side+"Reads",reads);
                var endpoint=side.equals("source")?a:b;var variable=side.equals("source")?source:target;
                var frames=new org.json.JSONArray();
                for(var seed:endpoint.comparisonSource().guardedProjections.get(variable))frames.put(new org.json.JSONObject()
                    .put("declarationOccurrence",seed.frame.declarationOccurrence).put("variableOccurrence",seed.frame.variableOccurrence)
                    .put("tupleSlot",seed.frame.tupleSlot).put("sourceDomain",seed.domain.getSemanticIdentity())
                    .put("carrier",seed.carrier.getSemanticIdentity()).put("quantifier",seed.quantifier.name())
                    .put("sourceBody",seed.sourceBody.evidence()).put("loweredBody",seed.loweredBody.evidence())
                    .put("dependencies",CanonicalDistance.dependencyOccurrenceEvidence(seed.frame.dependencies)));
                out.put(side+"Frames",frames);
            }
            return out;
        }
    }

    GuardedProjectionMatch guardedProjectionMatch(NormalForm other,QuantiVar source,QuantiVar target) {
        if(!isFrozenForCertification()||!other.isFrozenForCertification()
                ||!matrixEGraphRoot.getSemanticProfile().equals(other.matrixEGraphRoot.getSemanticProfile()))return null;
        var a=comparisonSource().guardedProjections.get(source);var b=other.comparisonSource().guardedProjections.get(target);
        if(a==null||b==null||a.size()!=source.getDeclarationFrames().size()||b.size()!=target.getDeclarationFrames().size()||a.size()!=b.size())return null;
        var ar=new ArrayList<ProjectionRead>();var br=new ArrayList<ProjectionRead>();
        var am=projectionMatrix(matrixEGraphRoot,source,false,ar,Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        var bm=other.projectionMatrix(other.matrixEGraphRoot,target,false,br,Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        for(int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);
            if(x.owner!=comparisonSource()||y.owner!=other.comparisonSource()||x.frame.variable!=source||y.frame.variable!=target
                    || !ownsDeclarationFrame(x.frame)||!other.ownsDeclarationFrame(y.frame)
                    || x.quantifier!=source.getQuantifier()||y.quantifier!=target.getQuantifier()
                    || x.frame.signature==null||y.frame.signature==null
                    || !x.domain.isSameParserSignatureAs(x.frame.signature)||!y.domain.isSameParserSignatureAs(y.frame.signature)
                    || source.getCardinality()!=Cardinality.ONE||target.getCardinality()!=Cardinality.ONE||source.isDisj()||target.isDisj()
                    || !x.domain.isParserCertifiedSubsignatureOf(x.carrier)||!y.domain.isParserCertifiedSubsignatureOf(y.carrier)
                    || !x.carrier.isSameParserSignatureAs(y.carrier)||!x.loweredBody.same(am)||!y.loweredBody.same(bm)||!am.same(bm))return null;
        }
        return new GuardedProjectionMatch(this,other,source,target,ar,br);
    }

    boolean ownsDeclarationFrame(DeclarationFrame frame) {
        NormalForm original = comparisonSource == null ? this : comparisonSource;
        if (frame.owner != original || !original.frozenForCertification) return false;
        for (var bindings : List.of(original.params,original.matrixQuantiVars,original.getLocalQuantiVars()))
            for (var binding : bindings) if (binding == frame.variable) return true;
        return false;
    }

    boolean authenticDeclarationFrame(DeclarationFrame frame) {
        var original = comparisonSource();
        int index = identityIndex(original.matrixQuantiVars, frame.variable);
        return frozenForCertification && original.frozenForCertification && index >= 0
                && original.issuedDeclarationFrames.contains(frame)
                && frame.matchesIssuedBinding(original, frame.variable, index);
    }

    /** A differing domain must still be represented, not just named in a receipt. */
    boolean retainedDeclarationDomain(DeclarationFrame frame) {
        return retainedDeclarationDomainEvidence(frame) != null;
    }

    org.json.JSONObject retainedDeclarationDomainEvidence(DeclarationFrame frame) {
        if (!authenticDeclarationFrame(frame)) return null;
        var original = comparisonSource();
        Boolean required = original.issuedDomainLowerings.get(frame);
        if (required == null) return null;
        var evidence = new org.json.JSONObject().put("phase", phasePath).put("binding", frame.variable.getName())
                .put("domain", frame.domain.stableString()).put("type", frame.exactType.stableString())
                .put("dependencies", frame.dependencies.stream().map(QuantiVar::getName).toList());
        if (!required) return evidence.put("kind", "declaration-carrier");
        var origin = original.issuedDomainGuards.get(frame);
        if (origin == null || origin.frame != frame || !phasePath.equals(original.phasePath)
                || original.issuedDomainContext == null || matrixEGraphRoot == null
                || !original.issuedDomainContext.equals(domainContextKey(matrixEGraphRoot))) return null;
        var env = declarationGuardEnvironment();
        record Occurrence(EGraphNode node, String path) { }
        var pending = new java.util.ArrayDeque<Occurrence>();
        if (matrixEGraphRoot != null) pending.add(new Occurrence(matrixEGraphRoot, "root"));
        Opcode expected = frame.quantifier == Quantifier.ALL ? Opcode.NOT_IN : Opcode.IN;
        org.json.JSONObject found = null;
        while (!pending.isEmpty()) {
            var occurrence = pending.removeFirst();
            var node = occurrence.node();
            if (node.domainGuardOrigin == origin && matchesDeclarationGuard(node, frame, env)) {
                if (found != null) return null;
                found = evidence.put("kind", "matrix-membership").put("path", occurrence.path())
                        .put("opcode", expected.name()).put("occurrence", node.getSourceOccurrenceLineage())
                        .put("issuer", origin.index).put("context", original.issuedDomainContext.stableString());
                if (frame.domainNormalization != null)
                    found.put("normalization", new org.json.JSONObject().put("rule", "full-carrier-intersection")
                            .put("source", frame.domain.stableString())
                            .put("target", frame.domainNormalization.key.stableString()));
            }
            for (int i = 0; i < node.getChildren().size(); i++)
                pending.add(new Occurrence(node.getChildren().get(i), occurrence.path() + "/child/" + i));
        }
        return found;
    }

    private Map<String, QuantiVar> declarationGuardEnvironment() {
        var env = new HashMap<String, QuantiVar>();
        for (var q : comparisonSource().matrixQuantiVars) {
            env.put(q.getName(), q);
            for (var alias : q.getOriginalNames()) env.put(alias, q);
        }
        return env;
    }

    private boolean matchesDeclarationGuard(EGraphNode node, DeclarationFrame frame,
            Map<String, QuantiVar> env) {
        Opcode expected = frame.quantifier == Quantifier.ALL ? Opcode.NOT_IN : Opcode.IN;
        if (node.getOpcode() != expected || node.getChildren().size() != 2) return false;
        var read = node.getChildren().get(0);
        var dependencies = new ArrayList<QuantiVar>();
        var expectedDomain = retainedDomainKey(frame.domain, frame.dependencies);
        if (expectedDomain == null || read.getOpcode() != Opcode.VARIABLE
                || !frame.variable.getName().equals(read.getAlphaName())
                || read.getExactAlloyType() == null
                || frame.exactType == null
                || !frame.exactType.sameOccurrenceEvidenceAs(read.getExactAlloyType())) return false;
        var actualDomain = retainedDomainKey(
                declarationDomainKey(node.getChildren().get(1), env, dependencies), dependencies);
        if (expectedDomain.equals(actualDomain)) return true;
        var normalized = frame.domainNormalization;
        if (normalized == null) return false;
        var normalizedDomain = retainedDomainKey(normalized.key, normalized.dependencies);
        return normalizedDomain != null && normalizedDomain.equals(actualDomain);
    }

    /** Issue aliases only while the real guard is still present in its Boolean island. */
    private void retainIdempotentGuardOrigins(EGraphNode node, Map<String, QuantiVar> env) {
        boolean association = (node.getOpcode() == Opcode.AND || node.getOpcode() == Opcode.OR)
                && EGraphNode.hasBooleanOperands(node);
        var operands = new ArrayList<EGraphNode>();
        if (association) collectGuardIsland(node, node, operands);
        else operands.addAll(node.getChildren());
        for (var operand : operands) retainIdempotentGuardOrigins(operand, env);
        if (!association) return;
        for (var guard : operands) {
            var origin = guard.domainGuardOrigin;
            if (origin == null || issuedDomainGuards.get(origin.frame) != origin
                    || !matchesDeclarationGuard(guard, origin.frame, env)) continue;
            for (var alias : operands) {
                if (alias.domainGuardOrigin == null && alias.getExactAlloyType() != null
                        && guard.getExactAlloyType() != null
                        && guard.getExactAlloyType().sameOccurrenceEvidenceAs(alias.getExactAlloyType())
                        && guard.getSemanticProfile().equals(alias.getSemanticProfile())
                        && matchesDeclarationGuard(alias, origin.frame, env)) alias.domainGuardOrigin = origin;
            }
        }
    }

    private static void collectGuardIsland(EGraphNode owner, EGraphNode node, List<EGraphNode> operands) {
        if (owner.sameFlatOperatorInstance(node))
            for (var child : node.getChildren()) collectGuardIsland(owner, child, operands);
        else operands.add(node);
    }

    private record DomainContext(StructuralKey key, boolean containsGuard) { }

    /** Retain the whole Boolean context. Other subexpressions keep their typed
     * source occurrence identity; their internal normalization is not re-proved here. */
    private static StructuralKey domainContextKey(EGraphNode node) {
        return domainContext(node, false).key();
    }

    private static DomainContext domainContext(EGraphNode node, boolean issue) {
        var origin = node.domainGuardOrigin;
        var scalars = List.of(String.valueOf(node.getExactAlloyType()), node.getSemanticProfile().fingerprint());
        if (origin != null) return new DomainContext(StructuralKey.of("issued-guard",
                List.of(Integer.toString(origin.index),node.getOpcode().name(),scalars.get(0),scalars.get(1)),List.of()),true);
        var children = new ArrayList<StructuralKey>();
        boolean containsGuard = false;
        boolean booleanAssociation = (node.getOpcode() == Opcode.AND || node.getOpcode() == Opcode.OR)
                && EGraphNode.hasBooleanOperands(node);
        for (var child : node.getChildren()) {
            var context = domainContext(child, issue);
            containsGuard |= context.containsGuard();
            var key = context.key();
            if (booleanAssociation && key.tag().equals(node.getOpcode().name()) && key.scalars().equals(scalars))
                children.addAll(key.children());
            else children.add(key);
        }
        if (!containsGuard && !booleanAssociation) {
            if (issue && node.domainContextOrigin==null) node.domainContextOrigin=new DomainContextOrigin(node);
            var context=node.domainContextOrigin;
            if (context != null && context.currentOpcode != node.getOpcode()) context = null;
            return new DomainContext(StructuralKey.of("source-expression",
                    List.of(context==null?"unissued":Long.toString(context.occurrence),
                            (context==null?node.getOpcode():context.sourceOpcode).name(),scalars.get(0),scalars.get(1)),List.of()),false);
        }
        if (booleanAssociation) {
            Collections.sort(children);
            for (int i = children.size()-1; i > 0; i--)
                if (children.get(i).tag().equals("issued-guard") && children.get(i).equals(children.get(i-1)))
                    children.remove(i);
            if (children.size() == 1) return new DomainContext(children.get(0), containsGuard);
        }
        return new DomainContext(StructuralKey.of(node.getOpcode().name(), scalars, children),containsGuard);
    }

    private StructuralKey retainedDomainKey(StructuralKey key, List<QuantiVar> dependencies) {
        var coordinates = new HashMap<String, String>();
        for (int i = 0; i < dependencies.size(); i++) {
            int index = identityIndex(comparisonSource().matrixQuantiVars, dependencies.get(i));
            if (index < 0) return null;
            coordinates.put("dependency/" + i, "owner-slot/" + index);
        }
        return retainedDomainKey(key, coordinates);
    }

    private static StructuralKey retainedDomainKey(StructuralKey key, Map<String, String> coordinates) {
        var scalars = new ArrayList<>(key.scalars());
        if (key.tag().equals("VARIABLE") && !scalars.isEmpty() && coordinates.containsKey(scalars.get(0)))
            scalars.set(0, coordinates.get(scalars.get(0)));
        var children = new ArrayList<StructuralKey>();
        for (var child : key.children()) children.add(retainedDomainKey(child, coordinates));
        // Only INTERSECT is commuted here. Dependency identities are substituted
        // before sorting, so different owners/slots cannot collapse by position.
        if (key.tag().equals("INTERSECT")) Collections.sort(children);
        return StructuralKey.of(key.tag(), scalars, children);
    }

    /** The original declaration inventory, independent of whether its slots are read. */
    boolean hasIssuedGroupedDeclarations() {
        var declarations = new java.util.HashMap<Long, java.util.Set<Integer>>();
        for (var frame : comparisonSource().issuedDeclarationFrames) {
            if (frame.vectorIndex < 0) continue;
            var origin = comparisonSource().issuedDeclarationOrigins.get(frame);
            if (origin == null) return true;
            var slots = declarations.computeIfAbsent(origin.declaration, ignored -> new java.util.HashSet<>());
            slots.add(origin.tupleSlot);
            if (slots.size() > 1) return true;
        }
        return false;
    }

    boolean matrixDeclarationsMatchSource() {
        NormalForm original = comparisonSource();
        if (!frozenForCertification || !original.frozenForCertification
                || !phasePath.equals(original.phasePath) || temporalOp != original.temporalOp
                || !sameIdentities(matrixQuantiVars, original.matrixQuantiVars)) return false;
        for (int index = 0; index < matrixQuantiVars.size(); index++) {
            var q = matrixQuantiVars.get(index);
            var frames = original.issuedDeclarationFrames.stream().filter(f -> f.variable == q).toList();
            if (frames.isEmpty() || !frames.equals(q.getDeclarationFrames())) return false;
            var aliases = new java.util.HashSet<String>();
            for (var frame : frames) {
                if (!frame.matchesIssuedBinding(original, q, index)) return false;
                aliases.addAll(frame.sourceAliases);
                for (var dependency : frame.dependencies) {
                    int position = identityIndex(original.matrixQuantiVars, dependency);
                    if (position < 0 || position >= index || !dependency.isFrozenForCertification()) return false;
                }
            }
            if (!aliases.equals(q.getOriginalNames())) return false;
        }
        return true;
    }

    private static int identityIndex(List<QuantiVar> bindings, QuantiVar target) {
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i) == target) return i;
        return -1;
    }

    private static boolean sameIdentities(List<QuantiVar> left, List<QuantiVar> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    private void captureMatrixReadOccurrences() {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<EGraphNode, Boolean>());
        var pending = new java.util.ArrayDeque<EGraphNode>();
        if (certificationMatrixEGraphRoot != null) pending.add(certificationMatrixEGraphRoot);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node.getOpcode() == Opcode.VARIABLE) issuedMatrixReads.put(node, node.getSourceOccurrenceLineage());
            pending.addAll(node.getChildren());
        }
    }

    boolean sourceReadInventoryMatches(java.util.Set<EGraphNode> reads) {
        var issued = comparisonSource().issuedMatrixReads;
        if (reads.size() != issued.size()) return false;
        for (var read : reads) {
            var occurrence = issued.get(read);
            if (occurrence == null || occurrence.longValue() != read.getSourceOccurrenceLineage()) return false;
        }
        return true;
    }

    boolean matchesOriginalDeclarationDomain(DeclarationFrame frame,EGraphNode declaration){
        if(frame.owner!=this)return false;
        var relocated=relocatedDeclarations.get(declaration.getSourceOccurrenceLineage());
        if(frame.declarationOccurrence!=declaration.getSourceOccurrenceLineage()
                &&(relocated==null||relocated.frames.stream().noneMatch(f->f==frame)))return false;
        var env=new HashMap<String,QuantiVar>();
        var bindings=new ArrayList<QuantiVar>();bindings.addAll(params);bindings.addAll(matrixQuantiVars);bindings.addAll(inheritedQuantiVars);bindings.addAll(getLocalQuantiVars());
        for(var q:bindings){env.put(q.getName(),q);for(var alias:q.getOriginalNames())env.put(alias,q);}
        var actual=sourceDeclarationDomain(declaration,env);
        if(actual==null||!frame.domain.equals(actual.key)||frame.dependencies.size()!=actual.dependencies.size())return false;
        for(int i=0;i<frame.dependencies.size();i++)if(frame.dependencies.get(i)!=actual.dependencies.get(i))return false;
        return true;
    }

    RelocatedDeclaration originalDeclarationTransport(EGraphNode declaration){return relocatedDeclarations.get(declaration.getSourceOccurrenceLineage());}

    static final class CurrentDeclarationSlot {
        private final NormalForm owner;
        private final EGraphNode slot;
        private final DeclarationFrame frame;
        private final String phasePath,bindingPath,coordinate;
        private final TemporalOp phaseRole;
        private final is.fivefivefive.ACGN.alloy.ExactAlloyType type;
        private final QuantiVar.Quantifier quantifier;
        private final QuantiVar.Cardinality cardinality;
        private final int disjointness;
        private CurrentDeclarationSlot(NormalForm owner,EGraphNode slot,DeclarationFrame frame){
            this.owner=owner;this.slot=slot;this.frame=frame;phasePath=owner.phasePath;phaseRole=owner.temporalOp;
            var q=frame.variable;bindingPath=q.getBindingPath();coordinate=q.getDeBruijnKey();type=q.getExactAlloyType();
            quantifier=q.getQuantifier();cardinality=q.getCardinality();disjointness=q.getDisjointnessClass();
        }
        private boolean matches(EGraphNode current){
            var q=frame.variable;
            return current==slot&&frame.owner==owner&&phasePath.equals(owner.phasePath)&&phaseRole==owner.temporalOp
                &&current.getOpcode()==Opcode.VARIABLE&&current.getSourceOccurrenceLineage()==frame.variableOccurrence
                &&q.getName().equals(current.getAlphaName())&&bindingPath.equals(q.getBindingPath())&&coordinate.equals(q.getDeBruijnKey())
                &&type!=null&&type.sameOccurrenceEvidenceAs(q.getExactAlloyType())&&type.sameOccurrenceEvidenceAs(current.getExactAlloyType())
                &&quantifier==q.getQuantifier()&&cardinality==q.getCardinality()&&disjointness==q.getDisjointnessClass();
        }
    }
    private static void copyCurrentDeclarationSlot(EGraphNode source,EGraphNode copy){
        var issued=source.currentDeclarationSlot;
        if(issued!=null&&issued.matches(source)&&source.getOpcode()==copy.getOpcode())
            copy.currentDeclarationSlot=new CurrentDeclarationSlot(issued.owner,copy,issued.frame);
    }
    private void captureDeclarationTransport(EGraphNode node,Map<EGraphNode,RelocatedDeclaration> captured){
        var issued=new java.util.IdentityHashMap<QuantiVar,List<DeclarationFrame>>();
        for(var q:getLocalQuantiVars())issued.put(q,q.getDeclarationFrames());
        captureDeclarationTransport(node,captured,issued);
    }
    private void captureDeclarationTransport(EGraphNode node,Map<EGraphNode,RelocatedDeclaration> captured,
            Map<QuantiVar,List<DeclarationFrame>> issued){
        if(StructuralDeclarationOrigin.structural(node)){
            var cs=node.getChildren();var frames=new ArrayList<DeclarationFrame>();
            for(int i=1;i<cs.size();i++){
                final int tuple=i-1;var slot=cs.get(i);
                var association=slot.currentDeclarationSlot;
                if(association==null||association.owner!=this||!association.matches(slot)){frames.clear();break;}
                var current=association.frame.variable;
                var found=issued.getOrDefault(current,List.of()).stream().filter(f->f.owner==this&&f.variable==current
                    &&f.declarationOccurrence==node.getSourceOccurrenceLineage()&&f.variableOccurrence==slot.getSourceOccurrenceLineage()&&f.tupleSlot==tuple).toList();
                if(found.size()!=1||found.get(0)!=association.frame||!matchesOriginalDeclarationDomain(found.get(0),node)){frames.clear();break;}frames.add(found.get(0));
            }
            if(!frames.isEmpty()&&frames.size()==cs.size()-1)captured.put(node,new RelocatedDeclaration(node,List.copyOf(cs.subList(1,cs.size())),List.copyOf(frames)));
        }
        for(var child:node.getChildren())captureDeclarationTransport(child,captured,issued);
    }

    public StructuralDeclarationOrigin structuralDeclarationOrigin(EGraphNode original){
        synchronized(lifecycleLock){var origin=structuralDeclarationOrigins.get(original);return origin!=null&&origin.valid()?origin:null;}
    }

    private static StructuralKey declarationDomainKey(EGraphNode node, Map<String, QuantiVar> env,
            List<QuantiVar> dependencies) {
        QuantiVar dependency = node.getOpcode() == Opcode.VARIABLE ? env.get(bindingKey(node)) : null;
        String label;
        if (dependency != null) {
            // Keep every reference in traversal order, including repeats. The
            // selected declaration path must supply each corresponding owner.
            int coordinate = dependencies.size();
            dependencies.add(dependency);
            label = "dependency/" + coordinate;
        } else if (node.getOpcode() == Opcode.CALL) {
            label = CallMetadata.semanticKey(node);
        } else {
            label = firstNonEmpty(node.getSemanticIdentity(), node.getSourceName(), node.getSourceType(), "");
            if (label == null) label = "";
        }
        List<StructuralKey> children = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) children.add(declarationDomainKey(child, env, dependencies));
        return StructuralKey.of(node.getOpcode().name(), List.of(label,
                node.getExactAlloyType() == null ? "" : node.getExactAlloyType().stableString(),
                node.getSemanticProfile().fingerprint()), children);
    }

    private record DomainNormalization(StructuralKey key, List<QuantiVar> dependencies) {
        private DomainNormalization { dependencies = List.copyOf(dependencies); }
    }

    private static void collectDomainIntersection(EGraphNode owner, EGraphNode node, List<EGraphNode> operands) {
        if (owner.sameFlatOperatorInstance(node))
            for (var child : node.getChildren()) collectDomainIntersection(owner, child, operands);
        else operands.add(node);
    }

    /** Issue from source parser authority, before the real guard is normalized. */
    private static DomainNormalization declarationDomainNormalization(EGraphNode source, Map<String, QuantiVar> env) {
        var dependencies=new ArrayList<QuantiVar>();
        var key=normalizedDeclarationDomainKey(source,env,dependencies);
        return key.equals(declarationDomainKey(source,env,new ArrayList<>()))?null:
                new DomainNormalization(key,dependencies);
    }

    private static StructuralKey normalizedDeclarationDomainKey(EGraphNode source,Map<String,QuantiVar> env,
            List<QuantiVar> dependencies) {
        if(source.getOpcode()==Opcode.MINUS && source.getChildren().size()==2
                && source.getExactAlloyType()!=null && source.getExactAlloyType().hasParserAuthenticatedAncestry()
                && source.getChildren().stream().allMatch(c->c.getExactAlloyType()!=null
                    && source.getExactAlloyType().sameOccurrenceEvidenceAs(c.getExactAlloyType())
                    && source.getSemanticProfile().equals(c.getSemanticProfile()))) {
            var head=declarationDomainKey(source,env,new ArrayList<>());
            var children=new ArrayList<StructuralKey>();
            for(var child:source.getChildren())children.add(normalizedDeclarationDomainKey(child,env,dependencies));
            return StructuralKey.of(head.tag(),head.scalars(),children);
        }
        if (source.getOpcode() != Opcode.INTERSECT || source.getExactAlloyType() == null
                || !source.getExactAlloyType().hasParserAuthenticatedAncestry())
            return declarationDomainKey(source,env,dependencies);
        var operands = new ArrayList<EGraphNode>();
        collectDomainIntersection(source, source, operands);
        if (operands.size() < 2) return declarationDomainKey(source,env,dependencies);
        var retained = removeFullCarriersContainingSubrelations(operands);
        if (retained.size() != 1) return declarationDomainKey(source,env,dependencies);
        var result = retained.get(0);
        if (result.getExactAlloyType() == null
                || !source.getExactAlloyType().sameOccurrenceEvidenceAs(result.getExactAlloyType())
                || !source.getSemanticProfile().equals(result.getSemanticProfile()))
            return declarationDomainKey(source,env,dependencies);
        return normalizedDeclarationDomainKey(result,env,dependencies);
    }

    private record SourceDeclarationDomain(StructuralKey key, List<QuantiVar> dependencies,
            is.fivefivefive.ACGN.alloy.SigSymbol signature,GuardEditDomain guardEditDomain,
            DomainNormalization normalization) { }

    private static SourceDeclarationDomain sourceDeclarationDomain(EGraphNode declaration, Map<String, QuantiVar> env) {
        if (declaration.getChildren().isEmpty()) return null;
        EGraphNode sourceDomain = domainDescriptor(declaration.getChildren().get(0)).domain;
        if (sourceDomain == null) return null;
        var dependencies = new ArrayList<QuantiVar>();
        var key = declarationDomainKey(sourceDomain, env, dependencies);
        return new SourceDeclarationDomain(key, List.copyOf(dependencies), sourceDomain.authenticatedSignature(),
                GuardEditDomain.from(sourceDomain), declarationDomainNormalization(sourceDomain, env));
    }

    private DeclarationFrame retainDeclarationFrame(QuantiVar variable, QuantiVar sourceCandidate, EGraphNode declaration,
            EGraphNode sourceVariable, int tupleSlot, SourceDeclarationDomain domain) {
        if(domain==null)return null;
        var frame=new DeclarationFrame(this,variable,sourceCandidate,declaration,sourceVariable,tupleSlot,domain.key,domain.dependencies,domain.signature,domain.guardEditDomain,domain.normalization);
        issuedDeclarationFrames.add(frame);
        issuedDeclarationOrigins.put(frame, frame.sourceOrigin);
        variable.retainDeclarationFrame(frame);return frame;
    }
    private String phasePath;
    private List<NormalForm> temporalChildren;
    private List<NormalForm> sourceTemporalChildren;
    private final List<TemporalReferenceAuthority> retiredTemporalAuthorities = new ArrayList<>();
    private final List<TemporalReferenceProjection> temporalReferenceProjections = new ArrayList<>();
    private List<NormalForm> comparisonTemporalChildren;
    private NormalForm comparisonSource;
    private final Map<Long, TemporalReferenceAuthority> temporalReferenceAuthorities;
    private final Object lifecycleLock;
    private TemporalOp temporalOp; // the temporal operator of the formula, if any, e.g., "before", "historically", "once", "always", "eventually", "until", "releases", "since", "triggered". If none, then it is a non-temporal formula.
    private int nextDisjointnessClass;
    private int prenexReductionsThisPass;
    private volatile boolean frozenForCertification;
    public enum TemporalOp {
        NONE,
        BEFORE,
        HISTORICALLY,
        ONCE,
        ALWAYS,
        EVENTUALLY,
        AFTER,
        UNTILL,
        UNTILR,
        RELEASESL,
        RELEASESR,
        SINCEL,
        SINCER,
        TRIGGEREDL,
        TRIGGEREDR
    }

    /**
     * Owner-bound provenance for one lexical binder imported into another
     * temporal phase. The source lineage authenticates the declaration; the
     * phase paths and owner node prevent same-spelled binders in another scope
     * from being substituted for it.
     */
    public static final class PhaseLocalBindingImport {
        private final QuantiVar variable;
        private final NormalForm ownerPhase;
        private final EGraphNode ownerBinder;
        private final long sourceBinderLineage;
        private final String ownerPhasePath;
        private final String targetPhasePath;
        private final String binderContext;
        private final QuantiVar issuedVariable;
        private final NormalForm issuedOwnerPhase;
        private final EGraphNode issuedOwnerBinder;

        private PhaseLocalBindingImport(
                QuantiVar variable,
                NormalForm ownerPhase,
                EGraphNode ownerBinder,
                long sourceBinderLineage,
                String ownerPhasePath,
                String targetPhasePath,
                String binderContext) {
            this.variable = java.util.Objects.requireNonNull(variable, "phase-local variable");
            this.ownerPhase = java.util.Objects.requireNonNull(ownerPhase, "phase-local owner");
            this.ownerBinder = java.util.Objects.requireNonNull(ownerBinder, "phase-local binder");
            if (sourceBinderLineage <= 0L) {
                throw new IllegalArgumentException(
                        "A phase-local import requires positive source binder lineage");
            }
            this.sourceBinderLineage = sourceBinderLineage;
            this.ownerPhasePath = requirePhasePath(ownerPhasePath);
            this.targetPhasePath = requirePhasePath(targetPhasePath);
            if (binderContext == null || binderContext.isBlank()) {
                throw new IllegalArgumentException(
                        "A phase-local import requires its lexical binder context");
            }
            this.binderContext = binderContext;
            this.issuedVariable = variable;
            this.issuedOwnerPhase = ownerPhase;
            this.issuedOwnerBinder = ownerBinder;
        }

        public QuantiVar variable() {
            return variable;
        }

        public NormalForm ownerPhase() {
            return ownerPhase;
        }

        public EGraphNode ownerBinder() {
            return ownerBinder;
        }

        public long sourceBinderLineage() {
            return sourceBinderLineage;
        }

        public String ownerPhasePath() {
            return ownerPhasePath;
        }

        public String targetPhasePath() {
            return targetPhasePath;
        }

        public String binderContext() {
            return binderContext;
        }

        private PhaseLocalBindingImport retarget(String targetPath) {
            return new PhaseLocalBindingImport(
                    variable,
                    ownerPhase,
                    ownerBinder,
                    sourceBinderLineage,
                    ownerPhasePath,
                    targetPath,
                    binderContext);
        }

        private static String requirePhasePath(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A temporal phase path must be nonempty");
            }
            return path;
        }
    }

    private static final class LocalBindingSeed {
        private final QuantiVar variable;
        private final long sourceBinderLineage;

        private LocalBindingSeed(QuantiVar variable, long sourceBinderLineage) {
            this.variable = java.util.Objects.requireNonNull(variable, "local variable");
            this.sourceBinderLineage = sourceBinderLineage;
        }
    }

    private static final class ScopedLocalBinding {
        private final QuantiVar variable;
        private final NormalForm ownerPhase;
        private final EGraphNode ownerBinder;
        private final long sourceBinderLineage;
        private final String ownerPhasePath;
        private final String binderContext;

        private ScopedLocalBinding(
                QuantiVar variable,
                NormalForm ownerPhase,
                EGraphNode ownerBinder,
                long sourceBinderLineage,
                String ownerPhasePath,
                String binderContext) {
            this.variable = variable;
            this.ownerPhase = ownerPhase;
            this.ownerBinder = ownerBinder;
            this.sourceBinderLineage = sourceBinderLineage;
            this.ownerPhasePath = ownerPhasePath;
            this.binderContext = binderContext;
        }

        private static ScopedLocalBinding from(PhaseLocalBindingImport imported) {
            return new ScopedLocalBinding(
                    imported.variable(),
                    imported.ownerPhase(),
                    imported.ownerBinder(),
                    imported.sourceBinderLineage(),
                    imported.ownerPhasePath(),
                    imported.binderContext());
        }

        private PhaseLocalBindingImport importInto(String targetPath) {
            return new PhaseLocalBindingImport(
                    variable,
                    ownerPhase,
                    ownerBinder,
                    sourceBinderLineage,
                    ownerPhasePath,
                    targetPath,
                    binderContext);
        }
    }

    public NormalForm() {
        // initialize the normal form with empty quantification tree and matrix e-graph, and empty parameter list and quantified variable list.
        this.matrixEGraphRoot = null;
        this.certificationMatrixEGraphRoot = null;
        this.params = new ArrayList<>();
        this.matrixQuantiVars = new ArrayList<>();
        this.inheritedQuantiVars = new ArrayList<>();
        this.phaseLocalBindingImports = new ArrayList<>();
        this.temporalChildBindingImports = new java.util.IdentityHashMap<>();
        this.localBindingSeeds = new HashMap<>();
        this.phasePath = "phase[0]";
        this.temporalChildren = new ArrayList<>();
        this.sourceTemporalChildren = this.temporalChildren;
        this.temporalReferenceAuthorities = new java.util.LinkedHashMap<>();
        this.lifecycleLock = new Object();
        this.temporalOp = TemporalOp.NONE;
        this.nextDisjointnessClass = 1;
    }

    private java.util.Set<String> comparisonBindingNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (List<QuantiVar> bindings : List.of(params, matrixQuantiVars, inheritedQuantiVars, getLocalQuantiVars()))
            for (QuantiVar binding : bindings) names.add(binding.getName());
        return names;
    }

    /** Read-only metric projection; source/certificate ownership stays on this form. */
    NormalForm fastComparisonView() {
        synchronized (lifecycleLock) {
            if (!frozenForCertification) {
                throw new IllegalStateException("Fast comparison requires a frozen normal form");
            }
            NormalForm copy = new NormalForm();
            copy.comparisonSource = this;
            copy.matrixEGraphRoot = EGraphNode.fastComparisonMatrix(matrixEGraphRoot,
                    Collections.emptyMap(), Collections.emptyMap(), comparisonBindingNames());
            copy.certificationMatrixEGraphRoot = certificationMatrixEGraphRoot;
            copy.params = params;
            copy.matrixQuantiVars = matrixQuantiVars;
            copy.inheritedQuantiVars = inheritedQuantiVars;
            copy.unusedBindingReductions.addAll(unusedBindingReductions);
            copy.unusedBindingProjections.addAll(unusedBindingProjections);
            copy.phaseLocalBindingImports = phaseLocalBindingImports;
            copy.temporalChildBindingImports.putAll(temporalChildBindingImports);
            copy.localBindingSeeds.putAll(localBindingSeeds);
            copy.groupedOneOrigins.addAll(groupedOneOrigins);
            copy.guardedProjections.putAll(guardedProjections);
            copy.guardEditSources.putAll(guardEditSources);
            copy.branchRouteSources.putAll(branchRouteSources);
            copy.phasePath = phasePath;
            copy.temporalChildren = temporalChildren;
            copy.sourceTemporalChildren = sourceTemporalChildren;
            copy.retiredTemporalAuthorities.addAll(retiredTemporalAuthorities);
            copy.temporalReferenceProjections.addAll(temporalReferenceProjections);
            copy.temporalReferenceAuthorities.putAll(temporalReferenceAuthorities);
            copy.temporalOp = temporalOp;
            copy.frozenForCertification = true;
            return copy;
        }
    }

    /** Metric-only forest. Original temporal authorities and child indices are
     * never rewritten; the pruned child list is used only for metric traversal. */
    static List<NormalForm> fastComparisonViews(List<NormalForm> sources) {
        Map<NormalForm, NormalForm> copies = new java.util.IdentityHashMap<>();
        Map<NormalForm, Integer> incoming = new java.util.IdentityHashMap<>();
        for (NormalForm source : sources) copies.put(source, source.fastComparisonView());
        for (NormalForm source : sources) for (NormalForm child : source.temporalChildren) {
            if (!copies.containsKey(child)) throw new IllegalArgumentException("Foreign source phase child");
            incoming.merge(child, 1, Math::addExact);
        }
        java.util.Set<NormalForm> dropped = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Map<NormalForm, Map<Long, String>> keysByOwner = new java.util.IdentityHashMap<>();
        for (NormalForm source : sources) {
            NormalForm copy = copies.get(source);
            Map<Long, String> keys = new HashMap<>();
            int keyBytes = 0;
            java.util.Set<Long> before = comparisonReferenceIds(source.matrixEGraphRoot);
            if (before == null) continue;
            for (TemporalReferenceAuthority authority : source.temporalReferenceAuthorities.values()) {
                if (authority.owner != source || authority.children.size() != 1
                        || !before.contains(authority.authorityId)) continue;
                NormalForm child = authority.children.get(0);
                if (incoming.getOrDefault(child, 0) != 1) continue;
                String body = inheritedComparisonPhaseKey(source, child, copies.get(child).matrixEGraphRoot);
                if (body != null) {
                    keyBytes = Math.addExact(keyBytes, body.length());
                    if (keyBytes > 1_000_000) { keys.clear(); break; }
                    keys.put(authority.authorityId, child.temporalOp.name()+":"+body);
                }
            }
            if (keys.size() < 2) continue;
            keysByOwner.put(source, keys);
            copy.matrixEGraphRoot = EGraphNode.fastComparisonMatrix(source.matrixEGraphRoot, keys,
                    Collections.emptyMap(), source.comparisonBindingNames());
            java.util.Set<Long> after = comparisonReferenceIds(copy.matrixEGraphRoot);
            if (after == null) throw new IllegalStateException("Fast phase projection increased reference traversal budget");
            for (TemporalReferenceAuthority authority : source.temporalReferenceAuthorities.values()) {
                if (keys.containsKey(authority.authorityId) && !after.contains(authority.authorityId))
                    dropped.add(authority.children.get(0));
            }
        }
        for (NormalForm source : sources) {
            NormalForm copy = copies.get(source);
            copy.comparisonTemporalChildren = source.temporalChildren.stream()
                    .filter(child -> !dropped.contains(child)).map(copies::get).toList();
            Map<Long, String> keys = keysByOwner.get(source);
            if (keys == null) continue;
            int[] positions = new int[source.temporalChildren.size()];
            int next = 0;
            for (int i=0; i<positions.length; i++)
                positions[i] = dropped.contains(source.temporalChildren.get(i)) ? -1 : next++;
            Map<Long, String> coordinates = new HashMap<>();
            java.util.Set<Long> surviving = comparisonReferenceIds(copy.matrixEGraphRoot);
            for (TemporalReferenceAuthority authority : source.temporalReferenceAuthorities.values()) {
                if (!surviving.contains(authority.authorityId)) continue;
                int start = positions[authority.childIndex];
                if (start < 0) throw new IllegalStateException("Fast phase pruning removed a surviving reference");
                for (int offset=0; offset<authority.children.size(); offset++)
                    if (positions[authority.childIndex+offset] != start+offset)
                        throw new IllegalStateException("Fast phase pruning split a temporal occurrence");
                coordinates.put(authority.authorityId, temporalReferenceName(start, authority.children.size()));
            }
            copy.matrixEGraphRoot = EGraphNode.fastComparisonMatrix(source.matrixEGraphRoot, keys, coordinates,
                    source.comparisonBindingNames());
        }
        return new ComparisonPhaseOrder(sources,copies,dropped,keysByOwner).ordered();
    }

    /** Original occurrence authority for a detached metric comparison view. */
    public NormalForm comparisonSource() { return comparisonSource==null?this:comparisonSource; }

    /** Roots of the complete frozen source forest, not of a pruned view. */
    public static List<NormalForm> originalPhaseRoots(List<NormalForm> sources) {
        if (sources.isEmpty()) throw new IllegalArgumentException("Missing source phase roots");
        var incoming = new java.util.IdentityHashMap<NormalForm,Integer>();
        for (var source : sources) {
            if (source == null || !source.isFrozenForCertification() || incoming.put(source, 0) != null)
                throw new IllegalArgumentException("Missing, mutable or duplicate source phase");
        }
        for (var source : sources) for (var child : source.temporalChildren) {
            Integer count = incoming.get(child);
            if (count == null) throw new IllegalArgumentException("Foreign source phase child");
            incoming.put(child, Math.addExact(count, 1));
        }
        var roots = sources.stream().filter(source -> incoming.get(source) == 0).toList();
        var pending = new java.util.ArrayDeque<>(roots);
        int visited = 0;
        while (!pending.isEmpty()) {
            var source = pending.removeFirst(); visited++;
            for (var child : source.temporalChildren)
                if (incoming.merge(child, -1, Integer::sum) == 0) pending.addLast(child);
        }
        if (visited != sources.size()) throw new IllegalArgumentException("Cyclic source phase graph");
        return roots;
    }

    /**
     * Metric-only replacement of the detached comparison matrix root after
     * source-order SET deduplication. Source forms and certification roots are
     * never replaced; the comparison root keeps sharing all retained children.
     */
    void replaceComparisonMatrixRoot(EGraphNode root) {
        if (comparisonSource == null) {
            throw new IllegalStateException(
                    "Only a detached comparison form may replace its metric root");
        }
        java.util.Objects.requireNonNull(root, "root");
        this.matrixEGraphRoot = root;
    }

    /** Reindex whole owner-issued reference groups, never their ordered roles. */
    private static final class ComparisonPhaseOrder {
        final List<NormalForm> sources;
        final Map<NormalForm,NormalForm> copies;
        final java.util.Set<NormalForm> dropped;
        final Map<NormalForm,Map<Long,String>> closedKeys;
        final Map<NormalForm,StructuralKey> phaseKeys=new java.util.IdentityHashMap<>();
        final Map<NormalForm,Map<EGraphNode,StructuralKey>> nodeKeys=new java.util.IdentityHashMap<>();
        final java.util.Set<NormalForm> active=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.Set<NormalForm> visited=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<NormalForm> result=new ArrayList<>();
        final is.fivefivefive.CanDis.metric.TraceResources budget=new is.fivefivefive.CanDis.metric.TraceResources();
        ComparisonPhaseOrder(List<NormalForm> sources,Map<NormalForm,NormalForm> copies,
                java.util.Set<NormalForm> dropped,Map<NormalForm,Map<Long,String>> closedKeys) {
            this.sources=sources;this.copies=copies;this.dropped=dropped;this.closedKeys=closedKeys;
        }
        List<NormalForm> ordered() {
            for(var source:originalPhaseRoots(sources)) {
                if(dropped.contains(source))throw new IllegalStateException("Removed original phase root");
                reserve(source);visit(source);
            }
            return List.copyOf(result);
        }
        void reserve(NormalForm source) {if(visited.add(source))result.add(copies.get(source));}
        void visit(NormalForm source) {
            var references=new java.util.LinkedHashMap<Long,TemporalReferenceAuthority>();
            collect(source,copies.get(source).matrixEGraphRoot,references);
            var children=new ArrayList<NormalForm>();var coordinates=new HashMap<Long,String>();
            for(var reference:references.values()) {
                if(reference.children.stream().anyMatch(dropped::contains))throw new IllegalStateException("Reindex of removed temporal reference");
                coordinates.put(reference.authorityId,temporalReferenceName(children.size(),reference.children.size()));
                for(var child:reference.children){children.add(copies.get(child));reserve(child);}
                for(var child:reference.children)visit(child);
            }
            var copy=copies.get(source);
            copy.comparisonTemporalChildren=List.copyOf(children);
            copy.matrixEGraphRoot=EGraphNode.fastComparisonMatrix(source.matrixEGraphRoot,
                    closedKeys.getOrDefault(source,Collections.emptyMap()),coordinates,source.comparisonBindingNames());
        }
        TemporalReferenceAuthority reference(NormalForm owner,EGraphNode node) {
            if(node.getOpcode()!=Opcode.REF||node.temporalReferenceAuthorityId()<=0)return null;
            var authority=owner.temporalReferenceAuthorities.get(node.temporalReferenceAuthorityId());
            if(authority==null||!authority.matches(node,authority.childIndex,authority.children.size()))
                throw new IllegalStateException("Phase reindex lacks original temporal authority");
            return authority;
        }
        void collect(NormalForm owner,EGraphNode node,Map<Long,TemporalReferenceAuthority> out) {
            if(node==null)return;var reference=reference(owner,node);
            if(reference!=null){out.putIfAbsent(reference.authorityId,reference);return;}
            var children=new ArrayList<>(node.getChildren());
            if(node.isOrderInsensitive())children.sort(Comparator.comparing(n->key(owner,n)));
            for(var child:children)collect(owner,child,out);
        }
        StructuralKey phaseKey(NormalForm phase) {
            var cached=phaseKeys.get(phase);if(cached!=null)return cached;
            if(!active.add(phase))throw new IllegalStateException("Cyclic temporal source graph");
            var children=new ArrayList<StructuralKey>();
            for(var q:phase.matrixQuantiVars)children.add(StructuralKey.of("declaration",
                    List.of(q.getQuantifier().name(),q.getCardinality().name(),String.valueOf(q.getExactAlloyType()),Integer.toString(q.getDisjointnessClass())),List.of()));
            children.add(key(phase,copies.get(phase).matrixEGraphRoot));
            var result=StructuralKey.branch("phase",children);active.remove(phase);phaseKeys.put(phase,result);return result;
        }
        StructuralKey key(NormalForm owner,EGraphNode node) {
            if(node==null)return StructuralKey.leaf("null");
            var cache=nodeKeys.computeIfAbsent(owner,k->new java.util.IdentityHashMap<>());
            var remembered=cache.get(node);if(remembered!=null)return remembered;
            budget.spend(128);var reference=reference(owner,node);StructuralKey result;
            if(reference!=null) {
                String operator=reference.children.get(0).temporalOp.name();
                if(reference.children.size()==2)operator=operator.substring(0,operator.length()-1);
                result=StructuralKey.of("temporal",List.of(operator),reference.children.stream().map(this::phaseKey).toList());
            } else {
                var children=new ArrayList<StructuralKey>();for(var child:node.getChildren())children.add(key(owner,child));
                if(node.isOrderInsensitive())children.sort(StructuralKey::compareTo);
                String identity=String.valueOf(node.getSemanticIdentity());
                if(node.getOpcode()==Opcode.VARIABLE) {
                    String name=node.getAlphaName()==null?node.getSourceName():node.getAlphaName();
                    var names=new ArrayList<String>();for(var list:List.of(owner.params,owner.inheritedQuantiVars,owner.matrixQuantiVars,owner.getLocalQuantiVars()))
                        for(var q:list)if(!names.contains(q.getName()))names.add(q.getName());
                    int index=names.indexOf(name);identity=index<0?String.valueOf(name):Integer.toString(index);
                } else if(node.getSemanticIdentity()==null)identity=String.valueOf(node.getSourceName());
                result=StructuralKey.of("matrix",List.of(node.getOpcode().name(),String.valueOf(node.getExactAlloyType()),identity),children);
            }
            cache.put(node,result);return result;
        }
    }

    List<NormalForm> comparisonTemporalChildren() {
        return comparisonTemporalChildren == null ? getTemporalChildren() : comparisonTemporalChildren;
    }

    private static boolean closedComparisonPhase(NormalForm child) {
        if (!child.params.isEmpty() || !child.matrixQuantiVars.isEmpty() || !child.inheritedQuantiVars.isEmpty()
                || !child.phaseLocalBindingImports.isEmpty() || !child.localBindingSeeds.isEmpty()
                || !child.temporalChildren.isEmpty()) return false;
        return switch (child.temporalOp) {
            case ALWAYS, EVENTUALLY, AFTER, BEFORE, HISTORICALLY, ONCE -> true;
            default -> false;
        };
    }

    /** Equality within one immutable owner's inherited environment, not alpha
     * equivalence across owners or permission to move declarations in time. */
    static String inheritedComparisonPhaseKey(NormalForm owner, NormalForm child, EGraphNode matrix) {
        if (!owner.frozenForCertification || !child.frozenForCertification
                || !owner.temporalChildren.contains(child)
                || !child.params.isEmpty() || !child.matrixQuantiVars.isEmpty()
                || !child.phaseLocalBindingImports.isEmpty() || !child.localBindingSeeds.isEmpty()
                || !child.temporalChildren.isEmpty()) return null;
        switch (child.temporalOp) {
            case ALWAYS, EVENTUALLY, AFTER, BEFORE, HISTORICALLY, ONCE: break;
            default: return null;
        }
        if (closedComparisonPhase(child)) return EGraphNode.closedFastMatrixKey(matrix);
        var visible = new ArrayList<QuantiVar>();
        visible.addAll(owner.params); visible.addAll(owner.inheritedQuantiVars); visible.addAll(owner.matrixQuantiVars);
        var inherited = new HashMap<String, QuantiVar>();
        for (var binding : child.inheritedQuantiVars) {
            if (identityIndex(visible, binding) < 0 || binding.getExactAlloyType() == null
                    || inherited.put(binding.getName(), binding) != null) return null;
        }
        var originals = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<EGraphNode, Boolean>());
        var pending = new java.util.ArrayDeque<EGraphNode>(); pending.add(child.matrixEGraphRoot);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!originals.add(node)) continue;
            if (originals.size() > 4096) return null;
            pending.addAll(node.getChildren());
        }
        return EGraphNode.inheritedFastMatrixKey(matrix, inherited.keySet(), read -> {
            var binding = inherited.get(read.getAlphaName());
            var original = read.comparisonVariableSource();
            if (binding == null || original == null || !originals.contains(original)
                    || original.getOpcode() != Opcode.VARIABLE
                    || !binding.getName().equals(original.getAlphaName())
                    || original.getSourceOccurrenceLineage() != read.getSourceOccurrenceLineage()
                    || !binding.getExactAlloyType().sameOccurrenceEvidenceAs(read.getExactAlloyType())
                    || !binding.getExactAlloyType().sameOccurrenceEvidenceAs(original.getExactAlloyType())) return null;
            return Integer.toString(identityIndex(visible, binding));
        });
    }

    private static java.util.Set<Long> comparisonReferenceIds(EGraphNode root) {
        java.util.Set<Long> result = new java.util.HashSet<>();
        java.util.Set<EGraphNode> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<EGraphNode> pending = new java.util.ArrayDeque<>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (seen.size() > 4096) return null;
            if (node.getOpcode() == Opcode.REF) result.add(node.temporalReferenceAuthorityId());
            pending.addAll(node.getChildren());
        }
        return result;
    }

    public NormalForm(NormalForm parent, TemporalOp temporalOp, int egid) {
        this(parent, temporalOp, egid,
                childSemanticProfile(parent));
    }

    public NormalForm(
            NormalForm parent,
            TemporalOp temporalOp,
            int egid,
            SemanticProfile semanticProfile) {
        NormalForm checkedParent = java.util.Objects.requireNonNull(
                parent, "temporal parent");
        this.lifecycleLock = checkedParent.lifecycleLock;
        this.certificationMatrixEGraphRoot = null;
        this.params = new ArrayList<>();
        this.matrixQuantiVars = new ArrayList<>();
        this.inheritedQuantiVars = new ArrayList<>();
        this.phaseLocalBindingImports = new ArrayList<>();
        this.temporalChildBindingImports = new java.util.IdentityHashMap<>();
        this.localBindingSeeds = new HashMap<>();
        this.phasePath = "unassigned";
        this.temporalChildren = new ArrayList<>();
        this.sourceTemporalChildren = this.temporalChildren;
        this.temporalReferenceAuthorities = new java.util.LinkedHashMap<>();
        this.temporalOp = temporalOp;
        this.nextDisjointnessClass = 1;
        synchronized (lifecycleLock) {
            checkedParent.requireMutable();
            EGraphNode parentRoot = checkedParent.matrixEGraphRoot;
            this.matrixEGraphRoot = parentRoot == null
                    ? new EGraphNode(
                            egid, Opcode.TEMPORALROOT, new ArrayList<>(), false, 1,
                            false, Metatype.BOOLEAN, semanticProfile)
                    : EGraphNode.inOwningArena(
                            parentRoot,
                            egid,
                            Opcode.TEMPORALROOT,
                            new ArrayList<>(),
                            false,
                            1,
                            false,
                            Metatype.BOOLEAN,
                            semanticProfile);
            this.params.addAll(checkedParent.params);
        }
    }

    private static SemanticProfile childSemanticProfile(NormalForm parent) {
        NormalForm checkedParent = java.util.Objects.requireNonNull(
                parent, "temporal parent");
        synchronized (checkedParent.lifecycleLock) {
            checkedParent.requireMutable();
            return checkedParent.matrixEGraphRoot == null
                    ? SemanticProfile.alloyOverflowForbidding()
                    : checkedParent.matrixEGraphRoot.getSemanticProfile();
        }
    }

    public EGraphNode getMatrixEGraph() {
        synchronized (lifecycleLock) {
            return this.matrixEGraphRoot;
        }
    }
    public EGraphNode getCertificationMatrixEGraph() {
        synchronized (lifecycleLock) {
            return certificationMatrixEGraphRoot == null
                    ? matrixEGraphRoot : certificationMatrixEGraphRoot;
        }
    }
    public List<QuantiVar> getParams() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.params));
        }
    }
    public List<QuantiVar> getMatrixQuantiVars() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.matrixQuantiVars));
        }
    }

    /** Ordered original slots, not permission to exchange or split uniqueness. */
    boolean hasOriginalGroupedOne(List<QuantiVar> group) {
        if (!frozenForCertification || group.size() < 2 || group.size() > 64) return false;
        for (var origin : groupedOneOrigins) {
            if (origin.bindings().size() != group.size()) continue;
            boolean matches = true;
            for (int i = 0; i < group.size(); i++) {
                var q = group.get(i);
                matches &= q == origin.bindings().get(i)
                        && q.getQuantifier() == Quantifier.ONE
                        && q.getCardinality() == Cardinality.ONE && q.getDisjointnessClass() == 0
                        && q.getBindingPath().equals(origin.owner())
                        && q.getDeBruijnKey().equals(origin.keys().get(i));
            }
            if (matches) return true;
        }
        return false;
    }

    boolean originalGroupedOneOriginsIntact() {
        if (groupedOneOrigins.isEmpty()) return true;
        if (matrixQuantiVars.size() > 64 || groupedOneOrigins.size() > 32) return false;
        var positions = new java.util.IdentityHashMap<QuantiVar, Integer>();
        for (int i = 0; i < matrixQuantiVars.size(); i++)
            if (positions.put(matrixQuantiVars.get(i), i) != null) return false;
        for (var origin : groupedOneOrigins) {
            var group = origin.bindings();
            if (!hasOriginalGroupedOne(group)) return false;
            Integer start = positions.get(group.get(0));
            if (start == null) return false;
            for (int i = 0; i < group.size(); i++)
                if (!java.util.Objects.equals(positions.get(group.get(i)), start + i)) return false;
        }
        return true;
    }
    public List<QuantiVar> getInheritedQuantiVars() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.inheritedQuantiVars));
        }
    }
    public List<QuantiVar> getLocalQuantiVars() {
        synchronized (lifecycleLock) {
            java.util.Set<QuantiVar> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            List<QuantiVar> result = new ArrayList<>();
            for (LocalBindingSeed seed : localBindingSeeds.values()) {
                if (seen.add(seed.variable)) {
                    result.add(seed.variable);
                }
            }
            result.sort(java.util.Comparator.comparingInt(QuantiVar::getId));
            return Collections.unmodifiableList(result);
        }
    }
    public List<PhaseLocalBindingImport> getPhaseLocalBindingImports() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(phaseLocalBindingImports));
        }
    }

    /** Imports expose existing lexical owners; they are not declarations in this phase. */
    public List<PhaseLocalBindingImport> checkedPhaseLocalBindingImports() {
        NormalForm source = comparisonSource();
        List<PhaseLocalBindingImport> imports = source.getPhaseLocalBindingImports();
        var names = new java.util.HashSet<String>();
        for (var group : List.of(source.params, source.matrixQuantiVars,
                source.inheritedQuantiVars, source.getLocalQuantiVars()))
            for (QuantiVar variable : group) names.add(variable.getName());
        for (PhaseLocalBindingImport imported : imports) {
            QuantiVar variable = imported.variable;
            if (!names.add(variable.getName()))
                throw new IllegalArgumentException("Imported local binding collides with another phase binding");
            NormalForm owner = imported.ownerPhase;
            LocalBindingSeed seed = owner.localBindingSeeds.get(variable.getName());
            boolean declared = false;
            for (EGraphNode declaration : imported.ownerBinder.getChildren()) {
                if (!isRelDecl(declaration.getOpcode())) continue;
                for (int i = 1; i < declaration.getChildren().size(); i++) {
                    EGraphNode slot = declaration.getChildren().get(i);
                    if (slot.getOpcode() == Opcode.VARIABLE
                            && variable.getName().equals(slot.getAlphaName())
                            && variable.getExactAlloyType() != null
                            && variable.getExactAlloyType().sameOccurrenceEvidenceAs(slot.getExactAlloyType())) {
                        // Normalization reseeds node occurrences. The seed/frame
                        // retains the parser slot; it is not the reseeded ID.
                        for (DeclarationFrame frame : variable.getDeclarationFrames())
                            if (frame.owner == owner && frame.variable == variable
                                    && frame.variableOccurrence == imported.sourceBinderLineage
                                    && owner.issuedDeclarationOrigins.get(frame) == frame.sourceOrigin
                                    && frame.sourceOrigin.payload.equals(new BindingSignature(variable))) declared = true;
                    }
                }
            }
            if (owner == source || owner != imported.issuedOwnerPhase
                    || variable != imported.issuedVariable || imported.ownerBinder != imported.issuedOwnerBinder
                    || seed == null || seed.variable != variable
                    || seed.sourceBinderLineage != imported.sourceBinderLineage || !declared
                    || !source.phasePath.equals(imported.targetPhasePath)
                    || !owner.phasePath.equals(imported.ownerPhasePath)
                    || !variable.getBindingPath().equals(imported.binderContext))
                throw new IllegalArgumentException("Foreign phase-local import owner, slot or target: "
                        + variable.getName() + ", owner=" + (owner != source)
                        + ", seed=" + (seed != null && seed.variable == variable)
                        + ", lineage=" + (seed != null && seed.sourceBinderLineage == imported.sourceBinderLineage)
                        + ", declared=" + declared + ", target=" + source.phasePath.equals(imported.targetPhasePath)
                        + ", ownerPath=" + owner.phasePath.equals(imported.ownerPhasePath)
                        + ", context=" + variable.getBindingPath().equals(imported.binderContext));
        }
        return imports;
    }
    public String getPhasePath() {
        synchronized (lifecycleLock) {
            return phasePath;
        }
    }

    /** Assigns the deterministic structural path before tree normalization. */
    public void assignPhasePath(String path) {
        synchronized (lifecycleLock) {
            requireMutable();
            String checked = PhaseLocalBindingImport.requirePhasePath(path);
            if (!"unassigned".equals(phasePath) && !phasePath.equals(checked)) {
                throw new IllegalStateException("A temporal phase received two structural paths");
            }
            phasePath = checked;
        }
    }

    /** Installs only imports issued by the exact structural parent reference. */
    public void installPhaseLocalBindingImports(
            List<PhaseLocalBindingImport> imports) {
        synchronized (lifecycleLock) {
            requireMutable();
            List<PhaseLocalBindingImport> checked = new ArrayList<>();
            java.util.Set<QuantiVar> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (PhaseLocalBindingImport imported
                    : imports == null ? Collections.<PhaseLocalBindingImport>emptyList() : imports) {
                PhaseLocalBindingImport value = java.util.Objects.requireNonNull(
                        imported, "phase-local import");
                if (!phasePath.equals(value.targetPhasePath())) {
                    throw new IllegalArgumentException(
                            "A phase-local import targets another temporal phase");
                }
                if (!seen.add(value.variable())) {
                    throw new IllegalArgumentException(
                            "A temporal phase received the same local binder twice");
                }
                checked.add(value);
            }
            phaseLocalBindingImports = checked;
        }
    }

    public List<PhaseLocalBindingImport> phaseLocalBindingImportsFor(
            NormalForm child) {
        synchronized (lifecycleLock) {
            NormalForm checked = java.util.Objects.requireNonNull(child, "temporal child");
            if (!temporalChildren.contains(checked)) {
                throw new IllegalArgumentException(
                        "Phase-local imports can be requested only for a structural child");
            }
            List<PhaseLocalBindingImport> result = temporalChildBindingImports.get(checked);
            return result == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(result));
        }
    }
    public void addEClass(EGraphNode node) {
        synchronized (lifecycleLock) {
            requireMutable();
            if (this.matrixEGraphRoot == null) {
                this.matrixEGraphRoot = node;
            } else {
                this.matrixEGraphRoot.addChild(node);
            }
        }
    }
    public void addParam(QuantiVar param) {
        synchronized (lifecycleLock) {
            requireMutable();
            this.params.add(param);
        }
    }
    public void addMatrixQuantiVar(QuantiVar quantiVar) {
        synchronized (lifecycleLock) {
            requireMutable();
            this.matrixQuantiVars.add(quantiVar);
        }
    }
    public TemporalOp getTemporalOp() {
        synchronized (lifecycleLock) {
            return this.temporalOp;
        }
    }
    public List<NormalForm> getTemporalChildren() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.temporalChildren));
        }
    }
    /** Original parser child coordinates, retained after normalized reachability projection. */
    public List<NormalForm> getSourceTemporalChildren() {
        synchronized (lifecycleLock) {
            return List.copyOf(sourceTemporalChildren);
        }
    }

    public record TemporalReferenceProjection(long authorityId, long parserOccurrence,
            long sourceOccurrence, int sourceSlot, int normalizedSlot,
            List<NormalForm> branches) {
        public TemporalReferenceProjection { branches = List.copyOf(branches); }
    }

    public List<TemporalReferenceProjection> getTemporalReferenceProjections() {
        synchronized (lifecycleLock) { return List.copyOf(temporalReferenceProjections); }
    }

    /** Only a whole admitted occurrence absent from both matrices can be retired. */
    public void projectReferencedTemporalChildren() {
        synchronized (lifecycleLock) {
            requireMutable();
            requireAdmittedTemporalTree();
            java.util.Set<Long> live = new java.util.LinkedHashSet<>();
            for (EGraphNode root : new EGraphNode[] {matrixEGraphRoot, certificationMatrixEGraphRoot}) {
                if (root == null) continue;
                root.forEachAdmittedReachableNode(node -> {
                    if (node.temporalReferenceAuthorityId() <= 0L) return;
                    int[] target = temporalReferenceTarget(node);
                    TemporalReferenceAuthority authority = temporalReferenceAuthorities.get(
                            node.temporalReferenceAuthorityId());
                    if (target == null || authority == null
                            || !authority.matches(node, target[0], target[1])) {
                        throw new IllegalStateException("Temporal projection lacks exact owner authority");
                    }
                    live.add(authority.authorityId);
                });
            }
            if (live.size() == temporalReferenceAuthorities.size()) return;
            List<NormalForm> children = new ArrayList<>();
            Map<Long, TemporalReferenceAuthority> retained = new java.util.LinkedHashMap<>();
            List<TemporalReferenceAuthority> retired = new ArrayList<>();
            List<TemporalReferenceProjection> projections = new ArrayList<>();
            for (TemporalReferenceAuthority authority : temporalReferenceAuthorities.values()) {
                int target = -1;
                if (live.contains(authority.authorityId)) {
                    target = children.size();
                    children.addAll(authority.children);
                    retained.put(authority.authorityId, new TemporalReferenceAuthority(
                            authority.authorityId, this, authority.sourceOpcode, authority.sourceClaim,
                            authority.sourceOccurrenceLineage, authority.parserOccurrenceId,
                            target, authority.children));
                } else retired.add(authority);
                projections.add(new TemporalReferenceProjection(authority.authorityId,
                        authority.parserOccurrenceId, authority.sourceOccurrenceLineage,
                        authority.sourceClaim.childIndex(), target, authority.children));
            }
            EGraphNode matrix = relocateTemporalReferences(matrixEGraphRoot, retained);
            EGraphNode certificate = relocateTemporalReferences(certificationMatrixEGraphRoot, retained);
            sourceTemporalChildren = List.copyOf(sourceTemporalChildren);
            temporalChildren = children;
            temporalReferenceAuthorities.clear();
            temporalReferenceAuthorities.putAll(retained);
            retiredTemporalAuthorities.addAll(retired);
            temporalReferenceProjections.addAll(projections);
            matrixEGraphRoot = matrix;
            certificationMatrixEGraphRoot = certificate;
            requireAdmittedTemporalTree();
        }
    }

    private EGraphNode relocateTemporalReferences(EGraphNode node,
            Map<Long, TemporalReferenceAuthority> retained) {
        if (node == null) return null;
        EGraphNode copy = copyShallow(node, node.getOpcode());
        if (node.temporalReferenceAuthorityId() > 0L) {
            TemporalReferenceAuthority authority = retained.get(node.temporalReferenceAuthorityId());
            if (authority == null) throw new IllegalStateException("A live temporal occurrence was retired");
            copy.setSourceName(temporalReferenceName(authority.childIndex, authority.children.size()));
            copy.preserveTemporalReferenceAuthorityFrom(node);
        }
        for (EGraphNode child : node.getChildren()) copy.addNormalizedChild(relocateTemporalReferences(child, retained));
        return copy;
    }
    public void addTemporalChild(NormalForm child) {
        synchronized (lifecycleLock) {
            requireMutable();
            if (sourceTemporalChildren != temporalChildren) {
                throw new IllegalStateException("A projected temporal source cannot acquire new parser children");
            }
            NormalForm checked = java.util.Objects.requireNonNull(
                    child, "temporal child");
            if (checked.lifecycleLock != lifecycleLock) {
                throw new IllegalArgumentException(
                        "A temporal child must share its parent's lifecycle lock");
            }
            this.temporalChildren.add(checked);
        }
    }

    /** Lowers one parser occurrence after consuming IRAgent-owned evidence. */
    public EGraphNode createTemporalReference(
            IRAgent.TemporalReferenceEvidence evidence) {
        synchronized (lifecycleLock) {
        requireMutable();
        IRAgent.TemporalReferenceClaim claim =
                java.util.Objects.requireNonNull(evidence, "temporal evidence")
                        .consumeFor(this);
        EGraphNode source = claim.source();
        int childIndex = claim.childIndex();
        int arity = claim.arity();
        if (source == null || source.getMetatype() != Metatype.BOOLEAN
                || source.getExactAlloyType() == null
                || source.getExactAlloyType().kind()
                        != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL) {
            throw new IllegalArgumentException(
                    "A temporal reference requires an exact Boolean source operator");
        }
        List<TemporalOp> expected = temporalOpsForSource(source.getOpcode());
        if (expected.size() != arity || childIndex < 0
                || childIndex + arity > temporalChildren.size()) {
            throw new IllegalArgumentException(
                    "Temporal reference arity or child range disagrees with its source operator");
        }
        List<NormalForm> children = new ArrayList<>(arity);
        for (int offset = 0; offset < arity; offset++) {
            NormalForm child = temporalChildren.get(childIndex + offset);
            if (child.temporalOp != expected.get(offset)) {
                throw new IllegalArgumentException(
                        "Temporal reference child operation disagrees with its source operator");
            }
            children.add(child);
        }
        long authorityId = NEXT_TEMPORAL_REFERENCE_AUTHORITY_ID.getAndIncrement();
        if (authorityId <= 0L) {
            throw new IllegalStateException("Temporal reference authority space exhausted");
        }
        EGraphNode reference = EGraphNode.inOwningArena(
                source,
                source.getId(),
                Opcode.REF,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.BOOLEAN,
                source.getSemanticProfile());
        reference.setSourceName(temporalReferenceName(childIndex, arity));
        reference.setSourceType("Bool");
        reference.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        reference.attachTemporalReferenceAuthority(authorityId);
        TemporalReferenceAuthority prior = temporalReferenceAuthorities.put(
                authorityId,
                new TemporalReferenceAuthority(
                        authorityId,
                        this,
                        source.getOpcode(),
                        claim,
                        claim.sourceOccurrenceLineage(),
                        claim.parserOccurrenceId(),
                        childIndex,
                        children));
        if (prior != null) {
            throw new IllegalStateException("Duplicate temporal reference authority id");
        }
        return reference;
        }
    }

    /** Read-only provenance from the existing sealed parser authority. */
    public static final class TemporalReferenceOrigin {
        private final EGraphNode reference;
        private final TemporalReferenceAuthority authority;
        private TemporalReferenceOrigin(EGraphNode reference, TemporalReferenceAuthority authority) {
            this.reference = reference;
            this.authority = authority;
        }
        public EGraphNode reference() { return reference; }
        public NormalForm owner() { return authority.owner; }
        public List<NormalForm> branches() { return authority.children; }
        public long authorityId() { return authority.authorityId; }
        public long parserOccurrenceId() { return authority.parserOccurrenceId; }
        public long sourceOccurrenceLineage() { return authority.sourceOccurrenceLineage; }
        public int sourceSlot() { return authority.childIndex; }
    }

    public TemporalReferenceOrigin temporalReferenceOrigin(EGraphNode reference) {
        synchronized (lifecycleLock) {
            if (!frozenForCertification || reference == null)
                throw new IllegalArgumentException("Temporal provenance requires a frozen source");
            var authority = temporalReferenceAuthorities.get(reference.temporalReferenceAuthorityId());
            int[] target = temporalReferenceTarget(reference);
            if (authority == null || target == null
                    || !authority.matches(reference, target[0], target[1]))
                throw new IllegalArgumentException("Temporal provenance lost sealed source authority");
            return new TemporalReferenceOrigin(reference, authority);
        }
    }

    /** Freezes the complete normalized source consumed by certification. */
    public void freezeForCertification() {
        synchronized (lifecycleLock) {
        if (frozenForCertification) {
            return;
        }
        requireAdmittedTemporalTree();
        for (QuantiVar parameter : params) {
            parameter.freezeForCertification();
        }
        for (QuantiVar variable : matrixQuantiVars) {
            variable.freezeForCertification();
        }
        for (QuantiVar variable : inheritedQuantiVars) {
            variable.freezeForCertification();
        }
        for (LocalBindingSeed seed : localBindingSeeds.values()) {
            seed.variable.freezeForCertification();
        }
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            imported.variable().freezeForCertification();
        }
        for (NormalForm child : sourceTemporalChildren) {
            child.freezeForCertification();
        }
        if (matrixEGraphRoot != null) {
            admitAndFreezeTemporalReferences(matrixEGraphRoot, "live matrix");
        }
        if (certificationMatrixEGraphRoot != null) {
            admitAndFreezeTemporalReferences(
                    certificationMatrixEGraphRoot, "certification matrix");
        }
        captureMatrixReadOccurrences();
        sealTemporalReferenceEvidence();
        if(!localBindingSeeds.isEmpty())
            structuralDeclarationOrigins.putAll(StructuralDeclarationOrigin.seal(this));
        frozenForCertification = true;
        }
    }

    public boolean isFrozenForCertification() {
        return frozenForCertification;
    }

    /** Drops parser graph retention after one final owner-bound provenance check. */
    private void sealTemporalReferenceEvidence() {
        for (TemporalReferenceAuthority authority
                : temporalReferenceAuthorities.values()) {
            authority.seal();
        }
        for (TemporalReferenceAuthority authority : retiredTemporalAuthorities) authority.seal();
    }

    private void requireMutable() {
        if (frozenForCertification) {
            throw new IllegalStateException(
                    "A certified normalized form is immutable");
        }
    }

    /** Admits every matrix occurrence before any temporal phase can be rewritten. */
    public void requireAdmittedTemporalTree() {
        synchronized (lifecycleLock) {
        java.util.Set<NormalForm> admitted = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        java.util.Set<NormalForm> active = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        requireAdmittedTemporalTree(this, admitted, active);
        }
    }

    private static void requireAdmittedTemporalTree(
            NormalForm current,
            java.util.Set<NormalForm> admitted,
            java.util.Set<NormalForm> active) {
        if (admitted.contains(current)) {
            return;
        }
        if (!active.add(current)) {
            throw new IllegalStateException("Temporal normal forms must be acyclic");
        }
        if (current.matrixEGraphRoot != null) {
            current.matrixEGraphRoot.requireAdmittedGraph();
        }
        if (current.certificationMatrixEGraphRoot != null
                && current.certificationMatrixEGraphRoot
                        != current.matrixEGraphRoot) {
            current.certificationMatrixEGraphRoot.requireAdmittedGraph();
            current.requireAdmittedTemporalReferences(
                    current.certificationMatrixEGraphRoot,
                    "certification matrix");
        }
        if (current.matrixEGraphRoot != null) {
            current.requireAdmittedTemporalReferences(
                    current.matrixEGraphRoot, "live matrix");
        }
        current.requireCompleteTemporalChildCoverage();
        List<NormalForm> children = new ArrayList<>(current.sourceTemporalChildren);
        for (NormalForm child : children) {
            requireAdmittedTemporalTree(child, admitted, active);
        }
        active.remove(current);
        admitted.add(current);
    }

    private void requireAdmittedTemporalReferences(EGraphNode root, String label) {
        root.forEachAdmittedReachableNode(
                node -> admitTemporalReference(node, label));
    }

    private void admitAndFreezeTemporalReferences(EGraphNode root, String label) {
        root.admitAndFreezeForCertification(
                node -> admitTemporalReference(node, label));
    }

    /**
     * Validates every temporal reference that survives certified matrix
     * normalization. An issued authority need not survive: Boolean quotient
     * laws may prove its containing branch unreachable before certification.
     */
    private void admitTemporalReference(
            EGraphNode node,
            String label) {
        String sourceName = node.getSourceName();
        if (sourceName == null || !sourceName.startsWith("temporal[")) {
            return;
        }
        int[] target = temporalReferenceTarget(node);
        if (target == null) {
            throw new IllegalStateException(
                    "Malformed or non-REF temporal reference in " + label);
        }
        long authorityId = node.temporalReferenceAuthorityId();
        TemporalReferenceAuthority authority =
                temporalReferenceAuthorities.get(authorityId);
        if (authority == null || !authority.matches(node, target[0], target[1])) {
            throw new IllegalStateException(
                    "Temporal reference lacks owner-bound source authority: "
                            + sourceName);
        }
    }

    private void requireCompleteTemporalChildCoverage() {
        boolean[] sourceCovered = new boolean[sourceTemporalChildren.size()];
        List<TemporalReferenceAuthority> sourceAuthorities = new ArrayList<>(temporalReferenceAuthorities.values());
        sourceAuthorities.addAll(retiredTemporalAuthorities);
        for (TemporalReferenceAuthority authority : sourceAuthorities) {
            int start = authority.sourceClaim.childIndex();
            if (authority.owner != this || !authority.sourceClaim.remainsValidFor(this)
                    || start < 0 || start + authority.children.size() > sourceTemporalChildren.size()) {
                throw new IllegalStateException("Temporal projection lost original parser authority");
            }
            for (int offset = 0; offset < authority.children.size(); offset++) {
                int index = start + offset;
                if (sourceCovered[index] || sourceTemporalChildren.get(index) != authority.children.get(offset)) {
                    throw new IllegalStateException("Temporal projection changed original ordered branches");
                }
                sourceCovered[index] = true;
            }
        }
        for (boolean present : sourceCovered) if (!present) {
            throw new IllegalStateException("An original temporal child lacks parser authority");
        }
        if (temporalChildren.isEmpty()) {
            if (!temporalReferenceAuthorities.isEmpty()) {
                throw new IllegalStateException(
                        "Temporal reference authority has no child phase");
            }
            return;
        }
        if (matrixEGraphRoot == null || temporalReferenceAuthorities.isEmpty()) {
            throw new IllegalStateException(
                    "A temporal child phase lacks an owner-issued matrix reference");
        }
        boolean[] covered = new boolean[temporalChildren.size()];
        for (TemporalReferenceAuthority authority
                : temporalReferenceAuthorities.values()) {
            if (authority.owner != this || authority.childIndex < 0
                    || authority.childIndex + authority.children.size()
                            > temporalChildren.size()) {
                throw new IllegalStateException(
                        "Temporal reference authority has an invalid child range");
            }
            for (int offset = 0; offset < authority.children.size(); offset++) {
                int index = authority.childIndex + offset;
                if (covered[index]
                        || temporalChildren.get(index) != authority.children.get(offset)) {
                    throw new IllegalStateException(
                            "Temporal child phases must have one exact owner reference");
                }
                covered[index] = true;
            }
        }
        for (boolean present : covered) {
            if (!present) {
                throw new IllegalStateException(
                        "A temporal child phase lacks an owner-issued matrix reference");
            }
        }
    }

    @LeanVerifiedRewrite("R0-CORE-010")
    public void pushTemporalNegations() {
        synchronized (lifecycleLock) {
        requireMutable();
        requireAdmittedTemporalTree();
        if (matrixEGraphRoot == null || temporalChildren.isEmpty()) {
            return;
        }
        boolean[] changed = new boolean[1];
        java.util.Set<String> dualizedReferences = new java.util.LinkedHashSet<>();
        List<TemporalDualization> stagedDualizations = new ArrayList<>();
        EGraphNode rewrittenMatrix = pushTemporalNegations(
                matrixEGraphRoot,
                changed,
                true,
                dualizedReferences,
                stagedDualizations);
        EGraphNode rewrittenCertification = certificationMatrixEGraphRoot;
        if (certificationMatrixEGraphRoot != null) {
            rewrittenCertification = pushTemporalNegations(
                    certificationMatrixEGraphRoot,
                    new boolean[1],
                    false,
                    dualizedReferences,
                    stagedDualizations);
        }
        if (changed[0] && rewrittenMatrix != null) {
            rewrittenMatrix.saturate();
        }
        for (TemporalDualization staged : stagedDualizations) {
            staged.commit();
        }
        matrixEGraphRoot = rewrittenMatrix;
        certificationMatrixEGraphRoot = rewrittenCertification;
        }
    }

    private EGraphNode pushTemporalNegations(
            EGraphNode node,
            boolean[] changed,
            boolean applyTemporalDualization,
            java.util.Set<String> dualizedReferences,
            List<TemporalDualization> stagedDualizations) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.NOT && node.getChildren().size() == 1) {
            EGraphNode reference = node.getChildren().get(0);
            int[] target = temporalReferenceTarget(reference);
            boolean dualized = target != null
                    && (applyTemporalDualization
                            ? (dualizedReferences.contains(reference.getSourceName())
                                    || stageTemporalChildren(
                                            target[0], target[1], stagedDualizations))
                            : dualizedReferences.contains(reference.getSourceName()));
            if (dualized) {
                if (applyTemporalDualization) {
                    dualizedReferences.add(reference.getSourceName());
                }
                changed[0] = true;
                var rewritten = cloneEGraph(reference);
                // The staged temporal dual denotes this whole NOT, not its old REF child.
                if (node.domainContextOrigin != null && node.domainContextOrigin.currentOpcode == Opcode.NOT)
                    rewritten.domainContextOrigin = new DomainContextOrigin(node.domainContextOrigin, Opcode.REF);
                return rewritten;
            }
        }
        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = pushTemporalNegations(
                    child,
                    changed,
                    applyTemporalDualization,
                    dualizedReferences,
                    stagedDualizations);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    private boolean stageTemporalChildren(
            int index,
            int arity,
            List<TemporalDualization> stagedDualizations) {
        if (index < 0 || arity < 1 || index + arity > temporalChildren.size()) {
            return false;
        }
        List<TemporalDualization> additions = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) {
            NormalForm child = temporalChildren.get(index + i);
            child.requireMutable();
            TemporalOp dual = temporalNegationDual(child.temporalOp);
            if (dual == null) {
                return false;
            }
            for (TemporalDualization staged : stagedDualizations) {
                if (staged.child == child) {
                    if (staged.temporalOp != dual) {
                        throw new IllegalStateException(
                                "A temporal child has contradictory staged duals");
                    }
                    dual = null;
                    break;
                }
            }
            if (dual != null) {
                additions.add(new TemporalDualization(
                        child,
                        dual,
                        negatedMatrixBeforeNormalization(child.matrixEGraphRoot)));
            }
        }
        stagedDualizations.addAll(additions);
        return true;
    }

    private static EGraphNode negatedMatrixBeforeNormalization(
            EGraphNode matrixEGraphRoot) {
        if (matrixEGraphRoot == null) {
            return null;
        }
        if (matrixEGraphRoot.getOpcode() != Opcode.TEMPORALROOT) {
            return syntheticUnary(
                    matrixEGraphRoot, Opcode.NOT, matrixEGraphRoot, -11);
        }
        EGraphNode root = copyShallow(matrixEGraphRoot, Opcode.TEMPORALROOT);
        List<EGraphNode> body = new ArrayList<>();
        for (EGraphNode child : matrixEGraphRoot.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                root.addNormalizedChild(child);
            } else {
                body.add(child);
            }
        }
        if (!body.isEmpty()) {
            EGraphNode matrix = body.size() == 1 ? body.get(0) : conjoin(null, body);
            root.addNormalizedChild(syntheticUnary(matrix, Opcode.NOT, matrix, -12));
        }
        return root;
    }

    private static final class TemporalDualization {
        private final NormalForm child;
        private final TemporalOp temporalOp;
        private final EGraphNode matrix;

        private TemporalDualization(
                NormalForm child,
                TemporalOp temporalOp,
                EGraphNode matrix) {
            this.child = child;
            this.temporalOp = temporalOp;
            this.matrix = matrix;
        }

        private void commit() {
            child.temporalOp = temporalOp;
            child.matrixEGraphRoot = matrix;
        }
    }

    private static int[] temporalReferenceTarget(EGraphNode node) {
        if (node.getOpcode() != Opcode.REF || node.getSourceName() == null) {
            return null;
        }
        String source = node.getSourceName();
        if (!source.startsWith("temporal[") || !source.endsWith("]")) {
            return null;
        }
        int colon = source.indexOf(':', 9);
        if (colon < 0) {
            return null;
        }
        try {
            int index = Integer.parseInt(source.substring(9, colon));
            int arity = Integer.parseInt(source.substring(colon + 1, source.length() - 1));
            return new int[] { index, arity };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String temporalReferenceName(int index, int arity) {
        return "temporal[" + index + ":" + arity + "]";
    }

    private static List<TemporalOp> temporalOpsForSource(Opcode opcode) {
        switch (opcode) {
            case BEFORE:
                return Collections.singletonList(TemporalOp.BEFORE);
            case HISTORICALLY:
                return Collections.singletonList(TemporalOp.HISTORICALLY);
            case ONCE:
                return Collections.singletonList(TemporalOp.ONCE);
            case ALWAYS:
                return Collections.singletonList(TemporalOp.ALWAYS);
            case EVENTUALLY:
                return Collections.singletonList(TemporalOp.EVENTUALLY);
            case AFTER:
                return Collections.singletonList(TemporalOp.AFTER);
            case UNTIL:
                return List.of(TemporalOp.UNTILL, TemporalOp.UNTILR);
            case RELEASES:
                return List.of(TemporalOp.RELEASESL, TemporalOp.RELEASESR);
            case SINCE:
                return List.of(TemporalOp.SINCEL, TemporalOp.SINCER);
            case TRIGGERED:
                return List.of(TemporalOp.TRIGGEREDL, TemporalOp.TRIGGEREDR);
            default:
                throw new IllegalArgumentException(
                        "Not a temporal source operator: " + opcode);
        }
    }

    private static final class TemporalReferenceAuthority {
        private final long authorityId;
        private final NormalForm owner;
        private final Opcode sourceOpcode;
        private final IRAgent.TemporalReferenceClaim sourceClaim;
        private final long sourceOccurrenceLineage;
        private final long parserOccurrenceId;
        private final int childIndex;
        private final List<NormalForm> children;

        private TemporalReferenceAuthority(
                long authorityId,
                NormalForm owner,
                Opcode sourceOpcode,
                IRAgent.TemporalReferenceClaim sourceClaim,
                long sourceOccurrenceLineage,
                long parserOccurrenceId,
                int childIndex,
                List<NormalForm> children) {
            this.authorityId = authorityId;
            this.owner = owner;
            this.sourceOpcode = sourceOpcode;
            this.sourceClaim = java.util.Objects.requireNonNull(
                    sourceClaim, "temporal source claim");
            this.sourceOccurrenceLineage = sourceOccurrenceLineage;
            this.parserOccurrenceId = parserOccurrenceId;
            this.childIndex = childIndex;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        private boolean matches(EGraphNode reference, int index, int arity) {
            if (authorityId <= 0L || sourceOccurrenceLineage <= 0L
                    || parserOccurrenceId <= 0L || owner == null
                    || !sourceClaim.remainsValidFor(owner)
                    || reference.temporalReferenceAuthorityId() != authorityId
                    || reference.getOpcode() != Opcode.REF
                    || !reference.getChildren().isEmpty()
                    || reference.getMetatype() != Metatype.BOOLEAN
                    || reference.getExactAlloyType() == null
                    || reference.getExactAlloyType().kind()
                            != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL
                    || !"Bool".equals(reference.getSourceType())
                    || !temporalReferenceName(childIndex, children.size())
                            .equals(reference.getSourceName())
                    || index != childIndex || arity != children.size()
                    || childIndex < 0
                    || childIndex + children.size() > owner.temporalChildren.size()) {
                return false;
            }
            List<TemporalOp> expected = temporalOpsForSource(sourceOpcode);
            boolean original = true;
            boolean dual = true;
            for (int offset = 0; offset < children.size(); offset++) {
                NormalForm child = owner.temporalChildren.get(childIndex + offset);
                if (child != children.get(offset)) {
                    return false;
                }
                TemporalOp operation = child.temporalOp;
                TemporalOp expectedOperation = expected.get(offset);
                original &= operation == expectedOperation;
                dual &= operation == temporalNegationDual(expectedOperation);
            }
            return original || dual;
        }

        private void seal() {
            sourceClaim.sealFor(owner);
        }
    }

    private static TemporalOp temporalNegationDual(TemporalOp op) {
        switch (op) {
            case ALWAYS:
                return TemporalOp.EVENTUALLY;
            case EVENTUALLY:
                return TemporalOp.ALWAYS;
            case HISTORICALLY:
                return TemporalOp.ONCE;
            case ONCE:
                return TemporalOp.HISTORICALLY;
            case UNTILL:
                return TemporalOp.RELEASESL;
            case UNTILR:
                return TemporalOp.RELEASESR;
            case RELEASESL:
                return TemporalOp.UNTILL;
            case RELEASESR:
                return TemporalOp.UNTILR;
            case SINCEL:
                return TemporalOp.TRIGGEREDL;
            case SINCER:
                return TemporalOp.TRIGGEREDR;
            case TRIGGEREDL:
                return TemporalOp.SINCEL;
            case TRIGGEREDR:
                return TemporalOp.SINCER;
            default:
                return null;
        }
    }
    public void prenex() {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize();
        }
    }
    public void normalize() {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize(new HashMap<>(), new int[] { 0 });
        }
    }
    public void normalize(Map<String, QuantiVar> inheritedBindings, int[] nextVarId) {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize(inheritedBindings, nextVarId, NO_OBSERVER);
        }
    }
    public void normalize(
            Map<String, QuantiVar> inheritedBindings,
            int[] nextVarId,
            NormalizationObserver observer) {
        synchronized (lifecycleLock) {
        requireMutable();
        requireAdmittedTemporalTree();
        if (matrixEGraphRoot == null) {
            return;
        }
        NormalizationObserver stages = observer == null ? NO_OBSERVER : observer;
        matrixQuantiVars.clear();
        localBindingSeeds.clear();
        relocatedDeclarations.clear();
        temporalChildBindingImports.clear();
        java.util.Set<QuantiVar> localImports = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            localImports.add(imported.variable());
        }
        inheritedQuantiVars = new ArrayList<>();
        for (QuantiVar inherited
                : new java.util.LinkedHashSet<>(inheritedBindings.values())) {
            if (!localImports.contains(inherited)) {
                inheritedQuantiVars.add(inherited);
            }
        }
        inheritedQuantiVars.sort(java.util.Comparator.comparingInt(QuantiVar::getId));
        nextDisjointnessClass = 1;
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        Map<String, String> inheritedAlphaNames = new HashMap<>();
        for (Map.Entry<String, QuantiVar> entry : inheritedBindings.entrySet()) {
            inheritedAlphaNames.put(entry.getKey(), entry.getValue().getName());
        }
        stages.onStage("begin-alpha-beta-branch", this);
        matrixEGraphRoot = alphaRenameBoundVariables(
                matrixEGraphRoot, inheritedAlphaNames, new int[] { 0 });
        matrixEGraphRoot = betaRewriteLet(matrixEGraphRoot, new HashMap<>());
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        unusedBindingReductions.clear();
        unusedBindingProjections.clear();
        var snapshotNames = new java.util.HashSet<String>();
        for (QuantiVar inherited : inheritedBindings.values()) {
            if (isTemporalSnapshotBinding(inherited)) snapshotNames.add(inherited.getName());
        }
        markTemporalSnapshotReads(matrixEGraphRoot, snapshotNames);
        // Expose checked source constants before liveness and NNF dualization;
        // ACI still runs after the certification snapshot.
        matrixEGraphRoot = normalizePrePrenexSourceRules(matrixEGraphRoot);
        matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(matrixEGraphRoot));
        stages.onStage("begin-nnf", this);
        matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
        matrixEGraphRoot = flattenBooleanAssociationForPrenex(matrixEGraphRoot);
        stages.onStage("begin-unused-binding-descope", this);
        matrixEGraphRoot = descopeUnusedBindings(matrixEGraphRoot, "root", false);
        matrixEGraphRoot = normalizePrePrenexSourceRules(matrixEGraphRoot);
        stages.onStage("alpha-beta-branch", this);
        stages.onStage("nnf", this);
        stages.onStage("begin-prenex", this);
        Map<String, QuantiVar> prenexBindings = new HashMap<>(inheritedBindings);
        for (QuantiVar inherited : inheritedBindings.values()) {
            prenexBindings.put(inherited.getName(), inherited);
        }
        int remainingQuantifiers = countFormulaQuantifierNodes(matrixEGraphRoot);
        int maximumPrenexPasses = Math.max(
                1, Math.addExact(remainingQuantifiers, 1));
        // One pass is mandatory even without formula quantifiers because the
        // root's declaration children establish predicate/function parameters.
        boolean reachedPrenexFixedPoint = false;
        for (int pass = 0; pass < maximumPrenexPasses && !reachedPrenexFixedPoint; pass++) {
            prenexReductionsThisPass = 0;
            List<EGraphNode> constraints = new ArrayList<>();
            matrixEGraphRoot = prenex(
                    matrixEGraphRoot, prenexBindings, nextVarId, false, constraints,
                    "root", true, true, true, new PrenexSlotAllocator());
            matrixEGraphRoot = removeEndNodes(conjoin(matrixEGraphRoot, constraints));
            int nextRemaining = countFormulaQuantifierNodes(matrixEGraphRoot);
            if (prenexReductionsThisPass == 0 && nextRemaining != remainingQuantifiers) {
                throw new IllegalStateException(
                        "Prenex residual changed without a recorded reduction");
            }
            if (prenexReductionsThisPass > 0 && nextRemaining >= remainingQuantifiers) {
                throw new IllegalStateException(
                        "Recorded prenex reductions did not strictly decrease the residual");
            }
            reachedPrenexFixedPoint = nextRemaining == 0
                    || prenexReductionsThisPass == 0;
            if (!reachedPrenexFixedPoint) {
                // A local quantifier may have introduced a domain implication.
                // Eliminate that connective before another pass interprets
                // child polarity; otherwise its antecedent can be negated a
                // second time while an enclosing quantifier is lifted.
                matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(
                        matrixEGraphRoot));
                matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
                matrixEGraphRoot = flattenBooleanAssociationForPrenex(
                        matrixEGraphRoot);
                int normalizedRemaining = countFormulaQuantifierNodes(
                        matrixEGraphRoot);
                if (normalizedRemaining != nextRemaining) {
                    throw new IllegalStateException(
                            "Inter-pass connective elimination changed the quantifier residual");
                }
            }
            remainingQuantifiers = nextRemaining;
        }
        if (!reachedPrenexFixedPoint) {
            throw new IllegalStateException(
                    "Prenexing did not reach a bounded structural fixed point");
        }
        minimizeOperationalCarriersFromPrefixWitnesses();
        stages.onStage("prenex", this);
        stages.onStage("begin-post-prenex-nnf", this);
        matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(matrixEGraphRoot));
        matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
        if (!issuedDomainGuards.isEmpty()) {
            retainIdempotentGuardOrigins(matrixEGraphRoot, declarationGuardEnvironment());
            issuedDomainContext = domainContext(matrixEGraphRoot, true).key();
        }
        matrixEGraphRoot = removeEndNodes(normalizeGuardedSourceRules(
                matrixEGraphRoot, guardedBindingFacts()));
        stages.onStage("post-prenex-nnf", this);
        var declarationTransport=new java.util.IdentityHashMap<EGraphNode,RelocatedDeclaration>();
        if(!localBindingSeeds.isEmpty())captureDeclarationTransport(matrixEGraphRoot,declarationTransport);
        matrixEGraphRoot.reseedSourceOccurrenceLineages();
        // Keys change, but these are the exact objects visited by the reseed, not matched metadata.
        for(var entry:declarationTransport.entrySet())relocatedDeclarations.put(entry.getKey().getSourceOccurrenceLineage(),entry.getValue());
        certificationMatrixEGraphRoot = cloneEGraph(matrixEGraphRoot);
        captureTemporalPhaseLocalBindings();
        stages.onStage("begin-aci", this);
        matrixEGraphRoot = normalizeAssociativeCommutative(matrixEGraphRoot);
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        stages.onStage("aci", this);
        if (matrixEGraphRoot == null) {
            return;
        }
        stages.onStage("begin-saturation", this);
        matrixEGraphRoot.saturate();
        registerQuantifierSymmetries();
        stages.onStage("saturation", this);
        }
    }

    private void minimizeOperationalCarriersFromPrefixWitnesses() {
        java.util.Set<String> inhabited = new java.util.HashSet<>();
        recordDirectCarrierWitnesses(params, inhabited);
        recordDirectCarrierWitnesses(inheritedQuantiVars, inhabited);
        recordDirectCarrierWitnesses(phaseLocalVariables(), inhabited);
        for (QuantiVar variable : matrixQuantiVars) {
            String primitive = normalizeType(variable.getTypeName());
            String carrier = normalizeType(variable.getCarrierTypeName());
            if ("univ".equalsIgnoreCase(carrier) && inhabited.contains(primitive)) {
                variable.setCarrierTypeName(variable.getTypeName());
                carrier = primitive;
            }
            if (!primitive.isEmpty()
                    && primitive.equals(carrier)
                    && cardinalityGuaranteesNonempty(variable.getCardinality())) {
                inhabited.add(primitive);
            }
        }
    }

    private static void recordDirectCarrierWitnesses(
            List<QuantiVar> variables,
            java.util.Set<String> inhabited) {
        for (QuantiVar variable : variables) {
            String primitive = normalizeType(variable.getTypeName());
            if (!primitive.isEmpty()
                    && primitive.equals(normalizeType(variable.getCarrierTypeName()))
                    && cardinalityGuaranteesNonempty(variable.getCardinality())) {
                inhabited.add(primitive);
            }
        }
    }

    @LeanVerifiedRewrite("R0-STRUCT-001")
    private static EGraphNode alphaRenameBoundVariables(
            EGraphNode node,
            Map<String, String> scope,
            int[] nextBinderId) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.VARIABLE
                || (node.getOpcode() == Opcode.LET && node.getChildren().isEmpty())) {
            EGraphNode renamed = copyShallow(node, node.getOpcode());
            String alphaName = scope.get(bindingKey(node));
            if (alphaName == null) {
                alphaName = scope.get(node.getSourceName());
            }
            if (alphaName != null) {
                renamed.setAlphaName(alphaName);
            }
            return renamed;
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().size() >= 2) {
            EGraphNode renamed = copyShallow(node, Opcode.LET);
            renamed.addNormalizedChild(alphaRenameBoundVariables(node.getChildren().get(0), scope, nextBinderId));
            String alphaName = "@let:" + nextBinderId[0]++;
            renamed.setAlphaName(alphaName);
            Map<String, String> bodyScope = new HashMap<>(scope);
            if (node.getSourceName() != null) {
                bodyScope.put(node.getSourceName(), alphaName);
            }
            for (int i = 1; i < node.getChildren().size(); i++) {
                renamed.addNormalizedChild(alphaRenameBoundVariables(node.getChildren().get(i), bodyScope, nextBinderId));
            }
            return renamed;
        }
        if (bindsRelDeclarations(node)) {
            EGraphNode renamed = copyShallow(node, node.getOpcode());
            Map<String, String> bodyScope = new HashMap<>(scope);
            Map<Integer, EGraphNode> declarations = new HashMap<>();
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode child = node.getChildren().get(i);
                if (isRelDecl(child.getOpcode())) {
                    declarations.put(i, alphaRenameRelDecl(child, bodyScope, nextBinderId));
                }
            }
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode declaration = declarations.get(i);
                renamed.addNormalizedChild(declaration == null
                        ? alphaRenameBoundVariables(node.getChildren().get(i), bodyScope, nextBinderId)
                        : declaration);
            }
            return renamed;
        }
        EGraphNode renamed = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            renamed.addNormalizedChild(alphaRenameBoundVariables(child, scope, nextBinderId));
        }
        return renamed;
    }

    @LeanVerifiedRewrite("R0-STRUCT-001")
    private static EGraphNode alphaRenameRelDecl(
            EGraphNode declaration,
            Map<String, String> bodyScope,
            int[] nextBinderId) {
        EGraphNode renamed = copyShallow(declaration, declaration.getOpcode());
        List<EGraphNode> children = declaration.getChildren();
        if (!children.isEmpty()) {
            renamed.addNormalizedChild(alphaRenameBoundVariables(children.get(0), bodyScope, nextBinderId));
        }
        for (int i = 1; i < children.size(); i++) {
            EGraphNode child = children.get(i);
            if (child.getOpcode() != Opcode.VARIABLE) {
                renamed.addNormalizedChild(alphaRenameBoundVariables(child, bodyScope, nextBinderId));
                continue;
            }
            EGraphNode variable = copyShallow(child, Opcode.VARIABLE);
            String alphaName = "@bind:" + nextBinderId[0]++;
            variable.setAlphaName(alphaName);
            renamed.addNormalizedChild(variable);
            String lexicalKey = bindingKey(child);
            if (lexicalKey != null) {
                bodyScope.put(lexicalKey, alphaName);
            }
            if (child.getSourceName() != null && lexicalKey == null) {
                bodyScope.put(child.getSourceName(), alphaName);
            }
        }
        return renamed;
    }

    private static boolean bindsRelDeclarations(EGraphNode node) {
        if (node.getOpcode() != Opcode.PREDICATE && node.getOpcode() != Opcode.FUNCTION
                && node.getOpcode() != Opcode.TEMPORALROOT && !isQuantifierNode(node)) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private static String bindingKey(EGraphNode node) {
        return firstNonEmpty(node.getAlphaName(), node.getSourceName());
    }

    // Correspondence: UnusedGroupedBindings.{all_drop_unused,some_drop_unused,
    // no_drop_unused,all_vacuous,some_vacuous}. Run before both canonical paths.
    @LeanVerifiedRewrite("R0-STRUCT-002")
    private EGraphNode descopeUnusedBindings(EGraphNode node, String path, boolean inBindingScope) {
        boolean bodyScope = inBindingScope || node.getOpcode() == Opcode.FORALL
                || node.getOpcode() == Opcode.EXISTS || node.getOpcode() == Opcode.NO;
        List<EGraphNode> children = new ArrayList<>();
        for (int i = 0; i < node.getChildren().size(); i++) {
            children.add(descopeUnusedBindings(node.getChildren().get(i), path + "/" + i, bodyScope));
        }
        if (!children.equals(node.getChildren())) node.setNormalizedChildren(children);
        if (inBindingScope) node.simplifyBooleanAfterChildren();
        children = node.getChildren();
        Opcode kind = node.getOpcode();
        // Counting, disjoint, output and interface binders have implicit uses.
        if (kind != Opcode.FORALL && kind != Opcode.EXISTS && kind != Opcode.NO) return node;
        if (!EGraphNode.hasBooleanFormulaType(node)) return node;
        // REF carries uses in another phase; a matrix-only scan cannot erase them.
        if (containsOpcode(node, Opcode.REF)) return node;
        java.util.Set<String> uses = new java.util.HashSet<>();
        for (EGraphNode child : children) {
            if (!isRelDecl(child.getOpcode())) collectBindingUses(child, uses);
        }
        List<EGraphNode> retainedChildren = new ArrayList<>();
        List<EGraphNode> guards = new ArrayList<>();
        // Later domains may read earlier bindings. Only surviving declarations
        // and residual guards keep those reads live; removed SET domains do not.
        for (int declIndex = children.size() - 1; declIndex >= 0; declIndex--) {
            EGraphNode decl = children.get(declIndex);
            if (decl.getOpcode() != Opcode.GENERICRELDECL || decl.getChildren().size() < 2) {
                if (isRelDecl(decl.getOpcode())) collectBindingUses(decl, uses);
                retainedChildren.add(decl); continue;
            }
            DomainDescriptor domain = domainDescriptor(decl.getChildren().get(0));
            if (domain.domain == null || domain.cardinality == Cardinality.EXACTLY) {
                collectBindingUses(decl, uses);
                retainedChildren.add(decl); continue;
            }
            List<EGraphNode> slots = decl.getChildren().subList(1, decl.getChildren().size());
            var domainType = domain.domain.getExactAlloyType();
            boolean authenticated = domainType != null && domainType.hasParserAuthenticatedAncestry();
            java.util.Set<String> keys = new java.util.HashSet<>();
            java.util.Set<String> aliases = new java.util.HashSet<>();
            java.util.Set<Long> lineages = new java.util.HashSet<>();
            for (var slot : slots) {
                var type = slot.getExactAlloyType();
                String key = bindingKey(slot);
                authenticated &= slot.getOpcode() == Opcode.VARIABLE && slot.getChildren().isEmpty()
                        && key != null && key.startsWith("@bind:") && keys.add(key)
                        && slot.getSourceName() != null && slot.getSemanticIdentity() != null
                        && type != null && type.hasParserAuthenticatedAncestry()
                        && domainType != null && type.sameOccurrenceEvidenceAs(domainType);
                // Display names may be shadowed, but lexical identities may not.
                authenticated &= aliases.add(slot.getSemanticIdentity())
                        && slot.getSourceOccurrenceLineage() > 0
                        && lineages.add(slot.getSourceOccurrenceLineage());
            }
            if (!authenticated) { collectBindingUses(decl, uses); retainedChildren.add(decl); continue; }
            List<Integer> kept = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) if (uses.contains(bindingKey(slots.get(i)))) kept.add(i);
            if (kept.size() == slots.size()) { collectBindingUses(decl, uses); retainedChildren.add(decl); continue; }
            String residual = "same-domain-retained-witness";
            if (kept.isEmpty()) {
                residual = "admissible-domain-existence";
                // SET/LONE admit the empty relation even if their carrier is empty.
                if (domain.cardinality != Cardinality.SET && domain.cardinality != Cardinality.LONE) {
                    collectBindingUses(domain.domain, uses);
                    guards.add(EGraphNode.buildDerivedRelationCardinality(
                            node, Opcode.SOME, cloneEGraph(domain.domain).getEClassRef()));
                }
            } else {
                collectBindingUses(decl, uses);
                EGraphNode reduced = copyShallow(decl, decl.getOpcode());
                reduced.addNormalizedChild(decl.getChildren().get(0));
                for (int i : kept) reduced.addNormalizedChild(slots.get(i));
                retainedChildren.add(reduced);
            }
            var reduction = new UnusedBindingReduction(path + "/decl[" + declIndex + "]", kind,
                    decl.getSourceOccurrenceLineage(), slots.stream().map(EGraphNode::getSourceName).toList(),
                    slots.stream().map(NormalForm::bindingKey).toList(),
                    slots.stream().map(EGraphNode::getSemanticIdentity).toList(),
                    slots.stream().map(EGraphNode::getSourceOccurrenceLineage).toList(),
                    slots.stream().map(s -> s.getExactAlloyType().stableString()).toList(), kept,
                    residual);
            unusedBindingReductions.add(reduction);
            if (!kept.isEmpty()) retainedChildren.get(retainedChildren.size() - 1)
                    .attachUnusedBindingReduction(reduction);
        }
        Collections.reverse(retainedChildren);
        Collections.reverse(guards);
        if (retainedChildren.equals(children)) return node;
        List<EGraphNode> bodies = new ArrayList<>();
        List<EGraphNode> declarations = new ArrayList<>();
        for (var child : retainedChildren) {
            if (isRelDecl(child.getOpcode())) declarations.add(child); else bodies.add(child);
        }
        EGraphNode body = conjoin(null, bodies);
        if (body == null) throw new IllegalStateException("Unused-slot reduction lost its body");
        if (!guards.isEmpty()) {
            EGraphNode guard = conjoin(null, guards);
            EGraphNode combined = syntheticNode(node, kind == Opcode.FORALL ? Opcode.IMPLIES : Opcode.AND, -10);
            combined.addNormalizedChild(guard); combined.addNormalizedChild(body); body = combined;
            body.simplifyBooleanAfterChildren();
        }
        if (declarations.isEmpty()) {
            EGraphNode result = kind == Opcode.NO ? syntheticUnary(node, Opcode.NOT, body, -11) : body;
            result.simplifyBooleanAfterChildren(); return result;
        }
        declarations.add(body); node.setNormalizedChildren(declarations); return node;
    }

    @LeanVerifiedRewrite("R0-STRUCT-002")
    private void checkUnusedBindingProjection(EGraphNode declaration, EGraphNode slot, int index, QuantiVar q) {
        var reduction = declaration.unusedBindingReduction();
        if (reduction != null) {
            if (unusedBindingReductions.stream().noneMatch(r -> r == reduction)
                    || reduction.declarationLineage() != declaration.getSourceOccurrenceLineage())
                throw new IllegalStateException("Unused binding projection changed declaration owner");
            if (declaration.getChildren().size() != reduction.retainedSourceSlots().size() + 1
                    || index < 0 || index >= reduction.retainedSourceSlots().size())
                throw new IllegalStateException("Unused binding projection changed declaration width");
            int original = reduction.retainedSourceSlots().get(index);
            if (!reduction.sourceNames().get(original).equals(slot.getSourceName())
                    || !reduction.sourceIdentities().get(original).equals(slot.getSemanticIdentity())
                    || reduction.sourceLineages().get(original) != slot.getSourceOccurrenceLineage()
                    || !reduction.exactTypes().get(original).equals(slot.getExactAlloyType().stableString())
                    || !q.getOriginalNames().contains(slot.getSemanticIdentity())
                    || !q.getExactAlloyType().sameOccurrenceEvidenceAs(slot.getExactAlloyType()))
                throw new IllegalStateException("Unused binding projection changed source slot authority");
            unusedBindingProjections.add(new UnusedBindingProjection(reduction.scope(), original,
                    q.getDeBruijnKey(), slot.getSemanticIdentity(), slot.getExactAlloyType().stableString()));
        }
    }

    private static boolean containsOpcode(EGraphNode node, Opcode opcode) {
        if (node.getOpcode() == opcode) return true;
        for (var child : node.getChildren()) if (containsOpcode(child, opcode)) return true;
        return false;
    }

    private static void collectBindingUses(EGraphNode node, java.util.Set<String> uses) {
        if (node.getOpcode() == Opcode.VARIABLE) uses.add(bindingKey(node));
        if (isRelDecl(node.getOpcode())) {
            if (!node.getChildren().isEmpty()) collectBindingUses(node.getChildren().get(0), uses);
        } else for (var child : node.getChildren()) collectBindingUses(child, uses);
    }

    private void registerQuantifierSymmetries() {
        if (matrixEGraphRoot == null) {
            return;
        }
        for (int rightIndex = 1; rightIndex < matrixQuantiVars.size(); rightIndex++) {
            QuantiVar right = matrixQuantiVars.get(rightIndex);
            if (!isSymmetricBooleanQuantifier(right.getQuantifier())) {
                continue;
            }
            for (int leftIndex = rightIndex - 1; leftIndex >= 0; leftIndex--) {
                QuantiVar left = matrixQuantiVars.get(leftIndex);
                if (left.getQuantifier() != right.getQuantifier()) {
                    break;
                }
                if (samePermutationPayload(left, right)) {
                    matrixEGraphRoot.getEClass().addSlotSwap(left.getName(), right.getName());
                    break;
                }
            }
        }
    }

    private static boolean samePermutationPayload(QuantiVar left, QuantiVar right) {
        boolean exchangeableBlock = (left.getQuantifier() == Quantifier.ALL
                || left.getQuantifier() == Quantifier.SOME)
                || left.getBindingPath().equals(right.getBindingPath());
        return exchangeableBlock
                && left.getQuantifier() == right.getQuantifier()
                && left.getCardinality() == right.getCardinality()
                && left.getDisjointnessClass() == right.getDisjointnessClass()
                && normalizeType(left.getTypeName()).equals(normalizeType(right.getTypeName()))
                && normalizeType(left.getCarrierTypeName()).equals(
                        normalizeType(right.getCarrierTypeName()));
    }

    private static boolean isSymmetricBooleanQuantifier(Quantifier quantifier) {
        return quantifier == Quantifier.ALL || quantifier == Quantifier.SOME
                || quantifier == Quantifier.NO || quantifier == Quantifier.ONE
                || quantifier == Quantifier.LONE || quantifier == Quantifier.NOTONE
                || quantifier == Quantifier.NOTLONE;
    }

    @LeanVerifiedRewrite({"R0-CORE-004", "R0-CORE-005", "R0-CORE-006"})
    private static EGraphNode rewriteBranchConnectives(EGraphNode node) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.IMPLIES
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode right = rewriteBranchConnectives(node.getChildren().get(1));
            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -1);
            disjunction.addNormalizedChild(syntheticUnary(node, Opcode.NOT, left, -2));
            disjunction.addNormalizedChild(right);
            return disjunction;
        }
        if (node.getOpcode() == Opcode.IFF
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode right = rewriteBranchConnectives(node.getChildren().get(1));

            EGraphNode leftImpliesRight = syntheticNode(node, Opcode.OR, -1);
            leftImpliesRight.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(left), -2));
            leftImpliesRight.addNormalizedChild(cloneEGraph(right));

            EGraphNode rightImpliesLeft = syntheticNode(node, Opcode.OR, -3);
            rightImpliesLeft.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(right), -4));
            rightImpliesLeft.addNormalizedChild(cloneEGraph(left));

            EGraphNode conjunction = syntheticNode(node, Opcode.AND, -5);
            conjunction.addNormalizedChild(leftImpliesRight);
            conjunction.addNormalizedChild(rightImpliesLeft);
            return conjunction;
        }
        if (node.getOpcode() == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            EGraphNode condition = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode thenBranch = rewriteBranchConnectives(node.getChildren().get(1));
            EGraphNode elseBranch = rewriteBranchConnectives(node.getChildren().get(2));

            EGraphNode thenCase = syntheticNode(node, Opcode.AND, -1);
            thenCase.addNormalizedChild(cloneEGraph(condition));
            thenCase.addNormalizedChild(thenBranch);

            EGraphNode elseCase = syntheticNode(node, Opcode.AND, -2);
            elseCase.addNormalizedChild(syntheticUnary(node, Opcode.NOT, condition, -3));
            elseCase.addNormalizedChild(elseBranch);

            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -4);
            disjunction.addNormalizedChild(thenCase);
            disjunction.addNormalizedChild(elseCase);
            return disjunction;
        }
        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = rewriteBranchConnectives(child);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    @LeanVerifiedRewrite("R0-CORE-003")
    private static EGraphNode betaRewriteLet(EGraphNode node, Map<String, EGraphNode> bindings) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.VARIABLE) {
            EGraphNode replacement = bindings.get(bindingKey(node));
            return replacement == null ? node : cloneEGraph(replacement);
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().isEmpty()) {
            EGraphNode replacement = bindings.get(bindingKey(node));
            return replacement == null ? node : cloneEGraph(replacement);
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().size() >= 2) {
            EGraphNode bound = betaRewriteLet(node.getChildren().get(0), bindings);
            Map<String, EGraphNode> scopedBindings = new HashMap<>(bindings);
            String key = bindingKey(node);
            if (key != null) {
                scopedBindings.put(key, bound);
            }
            return betaRewriteLet(node.getChildren().get(1), scopedBindings);
        }
        if (isQuantifierNode(node)) {
            Map<String, EGraphNode> scopedBindings = new HashMap<>(bindings);
            for (EGraphNode child : node.getChildren()) {
                if (isRelDecl(child.getOpcode())) {
                    removeDeclaredVariables(child, scopedBindings);
                }
            }
            EGraphNode rewritten = copyShallow(node, node.getOpcode());
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = betaRewriteLet(child, isRelDecl(child.getOpcode()) ? bindings : scopedBindings);
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }

        Map<String, EGraphNode> scopedBindings = shadowsLetBindings(node) ? new HashMap<>(bindings) : bindings;
        if (shadowsLetBindings(node)) {
            for (EGraphNode child : node.getChildren()) {
                if (isRelDecl(child.getOpcode())) {
                    removeDeclaredVariables(child, scopedBindings);
                }
            }
        }

        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = betaRewriteLet(child, scopedBindings);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    private static boolean shadowsLetBindings(EGraphNode node) {
        return isQuantifierNode(node) || node.getOpcode() == Opcode.PREDICATE
                || node.getOpcode() == Opcode.FUNCTION || node.getOpcode() == Opcode.TEMPORALROOT;
    }

    private static void removeDeclaredVariables(EGraphNode relDecl, Map<String, EGraphNode> bindings) {
        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            if (candidate.getOpcode() == Opcode.VARIABLE && bindingKey(candidate) != null) {
                bindings.remove(bindingKey(candidate));
            }
        }
    }

    private EGraphNode prenex(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots) {
        return prenexWithBoundaryAllowance(
                node, env, nextVarId, negated, constraints, bindingPath,
                canLiftSome, canLiftAll, globalLift, slots, null);
    }

    @LeanVerifiedRewrite({
            "R0-CORE-011", "R0-CORE-012", "R0-CORE-013", "R0-CORE-015",
            "R0-CORE-016"
    })
    private EGraphNode prenexWithBoundaryAllowance(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots,
            Quantifier boundaryAllowance) {
        if (node == null) {
            return null;
        }
        boolean booleanIte = node.getOpcode() == Opcode.ITE;
        if ((isBooleanBranchConnective(node.getOpcode()) || booleanIte)
                && (!EGraphNode.hasBooleanRewriteAuthority(node)
                        || !EGraphNode.hasBooleanOperands(node))) {
            slots.enterStructure(null);
            List<EGraphNode> retainedChildren = new ArrayList<>();
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode retainedChild = prenex(
                        node.getChildren().get(i), env, nextVarId, false,
                        constraints, bindingPath + "/opaque[" + i + "]",
                        false, false, false, slots);
                if (retainedChild != null && retainedChild.getOpcode() != Opcode.END) {
                    retainedChildren.add(retainedChild);
                }
            }
            node.setNormalizedChildren(retainedChildren);
            return negated ? syntheticUnary(node, Opcode.NOT, node, -1) : node;
        }
        if (node.getOpcode() == Opcode.VARIABLE) {
            QuantiVar qv = env.get(bindingKey(node));
            if (qv != null) {
                node.setAlphaName(qv.getName());
                inheritBindingTypeEvidence(node, qv);
                if (isTemporalSnapshotBinding(qv)) {
                    node.markTemporalSnapshotBinding();
                }
            }
            return node;
        }
        if (node.getOpcode() == Opcode.IFF
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            return prenex(expandIff(node, negated), env, nextVarId, false, constraints, bindingPath + "/iff",
                    canLiftSome, canLiftAll, globalLift, slots);
        }
        if (node.getOpcode() == Opcode.NOT
                && EGraphNode.hasBooleanOperands(node)) {
            List<EGraphNode> rewritten = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = prenex(child, env, nextVarId, !negated, constraints, bindingPath + "/not",
                        canLiftSome, canLiftAll, globalLift, slots);
                if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                    rewritten.add(rewrittenChild);
                }
            }
            if (rewritten.isEmpty()) {
                return null;
            }
            return rewritten.size() == 1 ? rewritten.get(0) : conjoin(null, rewritten);
        }
        if (isQuantifierNode(node)) {
            if (node.getOpcode() == Opcode.COMPREHENSION || node.getOpcode() == Opcode.SUM) {
                slots.enterStructure(null);
                return localQuantifier(node, env, nextVarId, negated, bindingPath, slots);
            }
            if (!negated) {
                EGraphNode eliminated = eliminateSingleMembershipQuantifier(node);
                if (eliminated != null) {
                    prenexReductionsThisPass++;
                    return prenex(
                            eliminated,
                            env,
                            nextVarId,
                            false,
                            constraints,
                            bindingPath + "/membership-elimination",
                            canLiftSome,
                            canLiftAll,
                            globalLift,
                            slots);
                }
            }
            Quantifier quantifier = quantifierOf(node.getOpcode(), negated);
            boolean directlyLiftable = canLiftQuantifier(
                    quantifier, canLiftSome, canLiftAll, globalLift)
                    || globalLift && quantifier == boundaryAllowance;
            boolean connectiveAdmitsWitnessLift = canLiftSome || canLiftAll;
            if (!directlyLiftable
                    && (!connectiveAdmitsWitnessLift
                            || !hasScopedInhabitedCarrierWitness(
                                    node, env, quantifier, globalLift))) {
                slots.enterStructure(null);
                return localQuantifier(node, env, nextVarId, negated, bindingPath, slots);
            }
            Boolean preflightEmptyDomain = emptyTupleDomainValue(node, quantifier);
            if (preflightEmptyDomain != null) {
                prenexReductionsThisPass++;
                return booleanConstant(node, preflightEmptyDomain);
            }
            prenexReductionsThisPass++;
            slots.enterQuantifier(quantifier);
            Map<String, QuantiVar> scopedEnv = new HashMap<>(env);
            List<EGraphNode> localConstraints = new ArrayList<>();
            List<EGraphNode> bodyParts = new ArrayList<>();
            List<EGraphNode> children = node.getChildren();
            List<QuantiVar> projectionBindings = new ArrayList<>();
            boolean bodyNegated = (node.getOpcode() == Opcode.NO && !negated)
                    || (negated && !consumesMatrixNegation(node.getOpcode()));
            for (int i = 0; i < children.size(); i++) {
                EGraphNode child = children.get(i);
                if (isRelDecl(child.getOpcode())) {
                    RelDeclResult relDecl = prenexRelDecl(node.getOpcode(), child, scopedEnv, nextVarId, false, negated, localConstraints,
                            bindingPath + "/decl[" + i + "]", slots, node.getSourceOrigin());
                    if (relDecl.emptyDomainValue != null) {
                        throw new IllegalStateException(
                                "Quantifier-domain preflight disagreed with transformed domain");
                    }
                    projectionBindings.addAll(relDecl.quantiVars);
                }
            }
            List<Integer> bodyIndices = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                if (!isRelDecl(children.get(i).getOpcode())) {
                    bodyIndices.add(i);
                }
            }
            boolean bodyCanLiftSome = canLiftSome;
            boolean bodyCanLiftAll = canLiftAll;
            if (!localConstraints.isEmpty()) {
                if (quantifier == Quantifier.ALL) {
                    // The declaration guard will become an implication. An
                    // existential may cross it only when its own carrier is
                    // already known to be inhabited.
                    bodyCanLiftSome = false;
                } else {
                    // Other declaration guards are conjunctive; the dual
                    // universal movement has the same empty-carrier hazard.
                    bodyCanLiftAll = false;
                }
            }
            if (bodyIndices.size() == 1) {
                int index = bodyIndices.get(0);
                EGraphNode rewrittenChild = prenex(
                        children.get(index), scopedEnv, nextVarId, bodyNegated, constraints,
                        bindingPath + "/body[" + index + "]",
                        bodyCanLiftSome, bodyCanLiftAll, globalLift, slots);
                if (rewrittenChild != null) {
                    bodyParts.add(rewrittenChild);
                }
            } else if (!bodyIndices.isEmpty()) {
                slots.enterStructure(Quantifier.ALL);
                PrenexSlotAllocator bodyBase = slots.copy();
                for (int index : bodyIndices) {
                    EGraphNode child = children.get(index);
                    PrenexSlotAllocator childSlots = slots.branchFrom(
                            bodyBase, Quantifier.ALL);
                    EGraphNode rewrittenChild = prenex(
                            child, scopedEnv, nextVarId, bodyNegated, constraints,
                            bindingPath + "/body[" + index + "]",
                            bodyCanLiftSome, bodyCanLiftAll, globalLift, childSlots);
                    slots.mergeBranch(childSlots, Quantifier.ALL);
                    if (rewrittenChild != null) {
                        bodyParts.add(rewrittenChild);
                    }
                }
            }
            EGraphNode body = conjoin(null, bodyParts);
            for(var binding:projectionBindings){issueGuardedProjection(binding,body);issueGuardEditSource(binding,body);issueBranchRouteSource(binding,body);}
            body = applyDomainConstraints(body, localConstraints, quantifier);
            return body == null ? booleanConstant(node, true) : body;
        }
        if (negated) {
            return prenexNegatedNonQuantifier(node, env, nextVarId, constraints, bindingPath,
                    canLiftSome, canLiftAll, globalLift, slots);
        }

        List<EGraphNode> children = node.getChildren();
        List<EGraphNode> rewritten = new ArrayList<>();
        Quantifier reusable = reusableAcrossBranches(node.getOpcode());
        Quantifier completeBoundary = completeBoundaryQuantifier(node);
        slots.enterStructure(reusable);
        PrenexSlotAllocator branchBase = reusable == null ? null : slots.copy();
        Map<Integer, EGraphNode> rewrittenByIndex = new HashMap<>();
        for (int i : prenexChildOrder(node.getOpcode(), children)) {
            EGraphNode child = children.get(i);
            if (isRelDecl(child.getOpcode())) {
                prenexRelDecl(Opcode.FORALL, child, env, nextVarId, true, false, constraints,
                        bindingPath + "/param[" + i + "]", slots, null);
                continue;
            }
            boolean childNegated = childNegated(node.getOpcode(), i, negated);
            boolean childCanLiftSome = childCanLiftSome(node.getOpcode(), canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(node.getOpcode(), canLiftAll);
            PrenexSlotAllocator childSlots = reusable == null
                    ? slots
                    : slots.branchFrom(branchBase, reusable);
            EGraphNode rewrittenChild = prenexWithBoundaryAllowance(
                    child, env, nextVarId, childNegated, constraints,
                    childBindingPath(bindingPath, node.getOpcode(), i, childNegated),
                    childCanLiftSome, childCanLiftAll, globalLift, childSlots,
                    completeBoundary);
            if (reusable != null) {
                slots.mergeBranch(childSlots, reusable);
            }
            if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                rewrittenByIndex.put(i, rewrittenChild);
            }
        }
        for (int i = 0; i < children.size(); i++) {
            EGraphNode rewrittenChild = rewrittenByIndex.get(i);
            if (rewrittenChild != null) {
                rewritten.add(rewrittenChild);
            }
        }
        node.setNormalizedChildren(rewritten);
        return node;
    }

    @LeanVerifiedRewrite("R0-BIND-002")
    private static EGraphNode eliminateSingleMembershipQuantifier(
            EGraphNode quantifier) {
        Opcode quantifierOpcode = quantifier.getOpcode();
        if (quantifierOpcode != Opcode.FORALL
                && quantifierOpcode != Opcode.EXISTS
                && quantifierOpcode != Opcode.NO) {
            return null;
        }
        EGraphNode declaration = null;
        EGraphNode body = null;
        for (EGraphNode child : quantifier.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                if (declaration != null) {
                    return null;
                }
                declaration = child;
            } else {
                if (body != null) {
                    return null;
                }
                body = child;
            }
        }
        if (declaration == null || body == null
                || isDisj(declaration.getOpcode())
                || declaration.getChildren().size() != 2
                || declaration.getChildren().get(1).getOpcode()
                        != Opcode.VARIABLE
                || (body.getOpcode() != Opcode.IN
                        && body.getOpcode() != Opcode.NOT_IN)
                || body.getChildren().size() != 2) {
            return null;
        }
        String expectedComparisonName = body.getOpcode() == Opcode.IN
                ? "BOP_IN" : "BOP_NOT_IN";
        if (!expectedComparisonName.equals(body.getSourceName())
                || !("MIDDLENODE_" + expectedComparisonName).equals(
                        body.getSourceType())) {
            return null;
        }
        EGraphNode binder = declaration.getChildren().get(1);
        EGraphNode compared = body.getChildren().get(0);
        EGraphNode memberSet = body.getChildren().get(1);
        String binderKey = bindingKey(binder);
        if (binderKey == null || compared.getOpcode() != Opcode.VARIABLE
                || !binderKey.equals(bindingKey(compared))
                || containsBindingReference(
                        declaration.getChildren().get(0), binderKey)
                || containsBindingReference(memberSet, binderKey)) {
            return null;
        }
        DomainDescriptor domain = domainDescriptor(
                declaration.getChildren().get(0));
        if (domain.domain == null) {
            return null;
        }
        return EGraphNode.parserCertifiedMembershipQuantifierElimination(
                quantifier,
                quantifierOpcode,
                domain.cardinality,
                compared.getEClassRef(),
                domain.domain.getEClassRef(),
                memberSet.getEClassRef(),
                body.getOpcode() == Opcode.IN);
    }

    private static boolean containsBindingReference(
            EGraphNode node,
            String binding) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() == Opcode.VARIABLE
                && binding.equals(bindingKey(node))) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsBindingReference(child, binding)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> prenexChildOrder(
            Opcode opcode,
            List<EGraphNode> children) {
        List<Integer> order = new ArrayList<>(children.size());
        boolean[] preferred = new boolean[children.size()];
        Quantifier enabling = opcode == Opcode.AND
                ? Quantifier.SOME
                : opcode == Opcode.OR ? Quantifier.ALL : null;
        if (enabling != null) {
            for (int i = 0; i < children.size(); i++) {
                if (reachesBooleanBoundary(children.get(i), opcode, enabling)) {
                    preferred[i] = true;
                    order.add(i);
                }
            }
        }
        for (int i = 0; i < children.size(); i++) {
            if (!preferred[i]) {
                order.add(i);
            }
        }
        return order;
    }

    private static boolean reachesBooleanBoundary(
            EGraphNode node,
            Opcode boundary,
            Quantifier target) {
        if (isQuantifierNode(node)) {
            return quantifierOf(node.getOpcode(), false) == target;
        }
        Opcode opcode = node.getOpcode();
        if (opcode != boundary
                && opcode != Opcode.PREDICATE
                && opcode != Opcode.FUNCTION
                && opcode != Opcode.TEMPORALROOT) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (reachesBooleanBoundary(child, boundary, target)) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-CORE-008")
    private EGraphNode prenexNegatedNonQuantifier(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots) {
        Opcode opcode = node.getOpcode();
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            Opcode rewrittenOpcode = dualBooleanOpcode(opcode);
            boolean childCanLiftSome = childCanLiftSome(rewrittenOpcode, canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(rewrittenOpcode, canLiftAll);
            EGraphNode rewritten = copyShallow(node, dualBooleanOpcode(opcode));
            Quantifier reusable = reusableAcrossBranches(rewrittenOpcode);
            slots.enterStructure(reusable);
            PrenexSlotAllocator branchBase = slots.copy();
            for (int i = 0; i < node.getChildren().size(); i++) {
                PrenexSlotAllocator childSlots = slots.branchFrom(branchBase, reusable);
                EGraphNode child = prenex(node.getChildren().get(i), env, nextVarId, true, constraints,
                        childBindingPath(bindingPath, opcode, i, true), childCanLiftSome, childCanLiftAll, globalLift, childSlots);
                slots.mergeBranch(childSlots, reusable);
                if (child != null && child.getOpcode() != Opcode.END) {
                    rewritten.addNormalizedChild(child);
                }
            }
            return rewritten;
        }
        if (opcode == Opcode.IMPLIES && node.getChildren().size() == 2) {
            EGraphNode conjunction = syntheticNode(node, Opcode.AND, -1);
            boolean childCanLiftSome = childCanLiftSome(Opcode.AND, canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(Opcode.AND, canLiftAll);
            EGraphNode left = prenex(node.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/implies[0]", childCanLiftSome, childCanLiftAll, globalLift, slots);
            EGraphNode right = prenex(node.getChildren().get(1), env, nextVarId, true, constraints,
                    bindingPath + "/implies[1]/not", childCanLiftSome, childCanLiftAll, globalLift, slots);
            if (left != null && left.getOpcode() != Opcode.END) {
                conjunction.addNormalizedChild(left);
            }
            if (right != null && right.getOpcode() != Opcode.END) {
                conjunction.addNormalizedChild(right);
            }
            return conjunction;
        }
        if (opcode == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            return prenex(expandIte(node, true), env, nextVarId, false, constraints, bindingPath + "/ite",
                    canLiftSome, canLiftAll, globalLift, slots);
        }
        Opcode dual = dualOpcode(opcode);
        if (dual != null) {
            EGraphNode rewritten = copyShallow(node, dual);
            boolean negateChildren = dualNegatesChildren(opcode);
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode child = prenex(node.getChildren().get(i), env, nextVarId, negateChildren, constraints,
                        childBindingPath(bindingPath, opcode, i, negateChildren), canLiftSome, canLiftAll, globalLift, slots);
                if (child != null && child.getOpcode() != Opcode.END) {
                    rewritten.addNormalizedChild(child);
                }
            }
            return rewritten;
        }
        EGraphNode positive = prenex(node, env, nextVarId, false, constraints, bindingPath + "/positive",
                canLiftSome, canLiftAll, globalLift, slots);
        return positive == null ? null : syntheticUnary(node, Opcode.NOT, positive, -1);
    }

    private static boolean canLiftQuantifier(
            Quantifier quantifier,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift) {
        if (!globalLift) {
            return false;
        }
        switch (quantifier) {
            case ALL:
            case NO:
                return canLiftAll;
            case SOME:
                return canLiftSome;
            default:
                return canLiftSome && canLiftAll;
        }
    }

    private static Quantifier reusableAcrossBranches(Opcode opcode) {
        switch (opcode) {
            case AND:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return Quantifier.ALL;
            case OR:
                return Quantifier.SOME;
            default:
                return null;
        }
    }

    private static Quantifier completeBoundaryQuantifier(EGraphNode node) {
        Quantifier target;
        if (node.getOpcode() == Opcode.AND) {
            target = Quantifier.ALL;
        } else if (node.getOpcode() == Opcode.OR) {
            target = Quantifier.SOME;
        } else {
            return null;
        }
        java.util.Set<String> commonFactors = null;
        boolean sawBranch = false;
        for (EGraphNode branch : node.getChildren()) {
            if (branch.getOpcode() == Opcode.END) {
                continue;
            }
            sawBranch = true;
            if (!isQuantifierNode(branch)
                    || quantifierOf(branch.getOpcode(), false) != target) {
                return null;
            }
            java.util.Set<String> factors = new java.util.LinkedHashSet<>();
            for (EGraphNode declaration : branch.getChildren()) {
                if (!isRelDecl(declaration.getOpcode())) {
                    continue;
                }
                if (isDisj(declaration.getOpcode())
                        || declaration.getChildren().isEmpty()) {
                    return null;
                }
                factors.add(sortKey(declaration.getChildren().get(0)));
            }
            if (factors.isEmpty()) {
                return null;
            }
            if (commonFactors == null) {
                commonFactors = factors;
            } else if (!commonFactors.equals(factors)) {
                return null;
            }
        }
        return sawBranch ? target : null;
    }

    private static boolean childCanLiftSome(Opcode opcode, boolean current) {
        switch (opcode) {
            case AND:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return current;
            default:
                return false;
        }
    }

    private static boolean childCanLiftAll(Opcode opcode, boolean current) {
        switch (opcode) {
            case OR:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return current;
            default:
                return false;
        }
    }

    private EGraphNode localQuantifier(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            String bindingPath,
            PrenexSlotAllocator slots) {
        Boolean emptyDomain = emptyTupleDomainValue(
                node, quantifierOf(node.getOpcode(), negated));
        if (emptyDomain != null) {
            prenexReductionsThisPass++;
            return booleanConstant(node, emptyDomain);
        }
        EGraphNode quantifier = copyShallow(node, node.getOpcode());
        Map<String, QuantiVar> scopedEnv = new HashMap<>(env);
        List<EGraphNode> bodyParts = new ArrayList<>();
        List<EGraphNode> localConstraints = new ArrayList<>();
        int localDepth = localBinderDepth(scopedEnv);
        int[] localOrdinal = {0};
        List<EGraphNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            EGraphNode child = children.get(i);
            if (isRelDecl(child.getOpcode())) {
                quantifier.addNormalizedChild(localRelDecl(node.getOpcode(), child, scopedEnv, nextVarId, negated,
                        localConstraints, bindingPath + "/local-decl[" + i + "]", slots,
                        localDepth, localOrdinal, node.getSourceOrigin()));
            } else {
                EGraphNode rewrittenChild = prenex(child, scopedEnv, nextVarId, false, localConstraints,
                        bindingPath + "/local-body[" + i + "]", true, true, false, slots);
                if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                    bodyParts.add(rewrittenChild);
                }
            }
        }
        EGraphNode body = conjoin(null, bodyParts);
        body = applyDomainConstraints(body, localConstraints, quantifierOf(node.getOpcode(), false));
        if (body != null) {
            quantifier.addNormalizedChild(body);
        }
        return negated ? syntheticUnary(node, Opcode.NOT, quantifier, -1) : quantifier;
    }

    private EGraphNode localRelDecl(
            Opcode quantifierOpcode,
            EGraphNode relDecl,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            PrenexSlotAllocator slots,
            int localDepth,
            int[] localOrdinal,
            EGraphNode.SourceOrigin sourceOrigin) {
        SourceDeclarationDomain sourceDomain = sourceDeclarationDomain(relDecl, env);
        EGraphNode copy = copyShallow(relDecl, relDecl.getOpcode());
        EGraphNode typeEGraph = null;
        if (!relDecl.getChildren().isEmpty()) {
            PrenexSlotAllocator domainSlots = slots.domainView();
            typeEGraph = prenex(relDecl.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/type", true, true, false, domainSlots);
            copy.addNormalizedChild(typeEGraph == null ? relDecl.getChildren().get(0) : typeEGraph);
        }
        EGraphNode normalizedTypeEGraph = typeEGraph == null
                ? null : toNNF(typeEGraph, false);
        DomainDescriptor domain = domainDescriptor(normalizedTypeEGraph);
        boolean disj = isDisj(relDecl.getOpcode());
        int disjointnessClass = disj ? nextDisjointnessClass++ : 0;
        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            EGraphNode candidateCopy = cloneEGraph(candidate);
            if (candidate.getOpcode() == Opcode.VARIABLE) {
                String originalName = candidate.getSourceName();
                int ordinal = localOrdinal[0]++;
                int localId = nextVarId[0]++;
                String alphaName = "_l" + localId;
                String varType = primitiveVarType(candidate.getSourceType());
                QuantiVar qv = new QuantiVar(localId, alphaName, originalName, varType);
                qv.setSourceOrigin(sourceOrigin);
                qv.addOriginalName(candidate.getSemanticIdentity());
                qv.mergeExactAlloyType(bindingTypeEvidence(candidate, typeEGraph));
                qv.setQuantifier(quantifierOf(quantifierOpcode, negated));
                qv.setCardinality(domain.cardinality);
                qv.setDisjointnessClass(disjointnessClass);
                qv.setBindingPath(bindingPath);
                qv.setDeBruijnKey("local#" + localDepth + "#" + ordinal
                        + ":" + qv.getCardinality()
                        + ":" + normalizeType(varType)
                        + "@" + bindingPath);
                var frame=retainDeclarationFrame(qv, qv, relDecl, candidate, i - 1, sourceDomain);
                checkUnusedBindingProjection(relDecl, candidate, i - 1, qv);
                candidateCopy.setAlphaName(alphaName);
                if(frame!=null)candidateCopy.currentDeclarationSlot=new CurrentDeclarationSlot(this,candidateCopy,frame);
                LocalBindingSeed prior = localBindingSeeds.put(
                        alphaName,
                        new LocalBindingSeed(
                                qv, candidate.getSourceOccurrenceLineage()));
                if (prior != null && prior.variable != qv) {
                    throw new IllegalStateException(
                            "A local alpha identity was issued twice");
                }
                if (needsDomainConstraint(domain, varType)) {
                    constraints.add(domainConstraint(qv, candidateCopy, domain.domain));
                }
                String key = bindingKey(candidate);
                if (key != null) {
                    env.put(key, qv);
                }
            }
            copy.addNormalizedChild(candidateCopy);
        }
        return copy;
    }

    private static int localBinderDepth(Map<String, QuantiVar> env) {
        int depth = 0;
        for (QuantiVar variable : new java.util.LinkedHashSet<>(env.values())) {
            String key = variable.getDeBruijnKey();
            if (key == null || !key.startsWith("local#")) {
                continue;
            }
            int separator = key.indexOf('#', "local#".length());
            if (separator < 0) {
                throw new IllegalStateException("Malformed local De Bruijn key: " + key);
            }
            try {
                depth = Math.max(
                        depth,
                        Integer.parseInt(key.substring("local#".length(), separator)) + 1);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                        "Malformed local De Bruijn depth: " + key, exception);
            }
        }
        return depth;
    }

    private void captureTemporalPhaseLocalBindings() {
        Map<String, ScopedLocalBinding> visible = new java.util.LinkedHashMap<>();
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            putScopedBinding(visible, ScopedLocalBinding.from(imported));
        }
        captureTemporalPhaseLocalBindings(certificationMatrixEGraphRoot, visible);
    }

    private void captureTemporalPhaseLocalBindings(
            EGraphNode node,
            Map<String, ScopedLocalBinding> visible) {
        if (node == null) {
            return;
        }
        int[] target = temporalReferenceTarget(node);
        if (target != null) {
            java.util.Set<ScopedLocalBinding> unique = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            unique.addAll(visible.values());
            for (int offset = 0; offset < target[1]; offset++) {
                NormalForm child = temporalChildren.get(target[0] + offset);
                List<PhaseLocalBindingImport> imports = new ArrayList<>(unique.size());
                for (ScopedLocalBinding binding : unique) {
                    imports.add(binding.importInto(child.phasePath));
                }
                imports.sort(java.util.Comparator
                        .comparing((PhaseLocalBindingImport value) -> value.binderContext())
                        .thenComparingInt(value -> value.variable().getId()));
                List<PhaseLocalBindingImport> snapshot =
                        Collections.unmodifiableList(imports);
                List<PhaseLocalBindingImport> prior = temporalChildBindingImports.get(child);
                if (prior == null) {
                    temporalChildBindingImports.put(child, snapshot);
                } else if (!samePhaseLocalBindingSnapshot(prior, snapshot)) {
                    throw new IllegalStateException(
                            "A temporal child was referenced under conflicting local scopes");
                }
            }
            return;
        }
        if (isQuantifierNode(node)) {
            Map<String, ScopedLocalBinding> scoped = new java.util.LinkedHashMap<>(visible);
            for (EGraphNode child : node.getChildren()) {
                if (!isRelDecl(child.getOpcode())) {
                    continue;
                }
                if (!child.getChildren().isEmpty()) {
                    captureTemporalPhaseLocalBindings(child.getChildren().get(0), scoped);
                }
                for (int index = 1; index < child.getChildren().size(); index++) {
                    EGraphNode variableNode = child.getChildren().get(index);
                    if (variableNode.getOpcode() != Opcode.VARIABLE) {
                        continue;
                    }
                    String alpha = variableNode.getAlphaName() != null
                                    && !variableNode.getAlphaName().isEmpty()
                            ? variableNode.getAlphaName()
                            : variableNode.getSourceName();
                    LocalBindingSeed seed = localBindingSeeds.get(alpha);
                    if (seed == null) {
                        throw new IllegalStateException(
                                "A retained local binder lacks its normalized binding seed: "
                                        + alpha);
                    }
                    putScopedBinding(
                            scoped,
                            new ScopedLocalBinding(
                                    seed.variable,
                                    this,
                                    node,
                                    seed.sourceBinderLineage,
                                    phasePath,
                                    seed.variable.getBindingPath()));
                }
            }
            for (EGraphNode child : node.getChildren()) {
                if (!isRelDecl(child.getOpcode())) {
                    captureTemporalPhaseLocalBindings(child, scoped);
                }
            }
            return;
        }
        for (EGraphNode child : node.getChildren()) {
            captureTemporalPhaseLocalBindings(child, visible);
        }
    }

    private static boolean samePhaseLocalBindingSnapshot(
            List<PhaseLocalBindingImport> left,
            List<PhaseLocalBindingImport> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            PhaseLocalBindingImport a = left.get(index);
            PhaseLocalBindingImport b = right.get(index);
            if (a.variable() != b.variable()
                    || a.ownerPhase() != b.ownerPhase()
                    || a.ownerBinder() != b.ownerBinder()
                    || a.sourceBinderLineage() != b.sourceBinderLineage()
                    || !a.ownerPhasePath().equals(b.ownerPhasePath())
                    || !a.targetPhasePath().equals(b.targetPhasePath())
                    || !a.binderContext().equals(b.binderContext())) {
                return false;
            }
        }
        return true;
    }

    private static void putScopedBinding(
            Map<String, ScopedLocalBinding> scope,
            ScopedLocalBinding binding) {
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        aliases.add(binding.variable.getName());
        aliases.add(binding.variable.getDeBruijnKey());
        aliases.addAll(binding.variable.getOriginalNames());
        java.util.Set<ScopedLocalBinding> shadowed = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (String alias : aliases) {
            ScopedLocalBinding prior = scope.get(alias);
            if (prior != null && prior != binding) {
                shadowed.add(prior);
            }
        }
        if (!shadowed.isEmpty()) {
            scope.entrySet().removeIf(entry -> shadowed.contains(entry.getValue()));
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isEmpty()) {
                scope.put(alias, binding);
            }
        }
    }

    private boolean hasScopedInhabitedCarrierWitness(
            EGraphNode quantifierNode,
            Map<String, QuantiVar> env,
            Quantifier quantifier,
            boolean globalLift) {
        if (!globalLift
                || quantifier != Quantifier.ALL
                        && quantifier != Quantifier.NO
                        && quantifier != Quantifier.SOME) {
            return false;
        }
        boolean foundDeclaration = false;
        for (EGraphNode child : quantifierNode.getChildren()) {
            if (!isRelDecl(child.getOpcode())) {
                continue;
            }
            foundDeclaration = true;
            if (isDisj(child.getOpcode()) || child.getChildren().isEmpty()) {
                return false;
            }
            DomainDescriptor domain = domainDescriptor(child.getChildren().get(0));
            if (hasEmptyAdmissibleBindingSet(domain)) {
                return false;
            }
            for (int index = 1; index < child.getChildren().size(); index++) {
                EGraphNode variable = child.getChildren().get(index);
                if (variable.getOpcode() != Opcode.VARIABLE) {
                    continue;
                }
                String primitiveType = primitiveVarType(variable.getSourceType());
                if (!domainUsesPrimitiveCarrier(domain.domain, primitiveType)
                        || !hasNonemptyBindingOfType(env, primitiveType)) {
                    return false;
                }
            }
        }
        return foundDeclaration;
    }

    private boolean hasNonemptyBindingOfType(
            Map<String, QuantiVar> env,
            String primitiveType) {
        java.util.LinkedHashSet<QuantiVar> enclosing =
                new java.util.LinkedHashSet<>(env.values());
        enclosing.addAll(matrixQuantiVars);
        String required = normalizeType(primitiveType);
        for (QuantiVar binding : enclosing) {
            if (normalizeType(binding.getTypeName()).equals(required)
                    && normalizeType(binding.getCarrierTypeName()).equals(required)
                    && cardinalityGuaranteesNonempty(binding.getCardinality())) {
                return true;
            }
        }
        return false;
    }

    private static void markTemporalSnapshotReads(EGraphNode node, java.util.Set<String> names) {
        if (names.isEmpty()) return;
        // Alpha-renamed local declarations cannot alias an imported coordinate.
        if (node.getOpcode() == Opcode.VARIABLE && names.contains(node.getAlphaName())) {
            node.markTemporalSnapshotBinding();
        }
        for (EGraphNode child : node.getChildren()) markTemporalSnapshotReads(child, names);
    }

    private boolean isTemporalSnapshotBinding(QuantiVar variable) {
        for (QuantiVar inherited : inheritedQuantiVars) {
            if (inherited == variable) {
                return true;
            }
        }
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            if (imported.variable() == variable) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-BIND-001")
    private RelDeclResult prenexRelDecl(
            Opcode quantifierOpcode,
            EGraphNode relDecl,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean parameterDecl,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            PrenexSlotAllocator slots,
            EGraphNode.SourceOrigin sourceOrigin) {
        SourceDeclarationDomain sourceDomain = sourceDeclarationDomain(relDecl, env);
        EGraphNode typeEGraph = null;
        if (!relDecl.getChildren().isEmpty()) {
            PrenexSlotAllocator domainSlots = slots.domainView();
            typeEGraph = prenex(relDecl.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/type", true, true, false, domainSlots);
        }
        EGraphNode normalizedTypeEGraph = typeEGraph == null
                ? null : toNNF(typeEGraph, false);
        DomainDescriptor domain = domainDescriptor(normalizedTypeEGraph);
        List<QuantiVar> quantiVars = new ArrayList<>();
        Quantifier quantifier = quantifierOf(quantifierOpcode, negated);
        if (hasEmptyAdmissibleBindingSet(domain)) {
            return new RelDeclResult(quantiVars, emptyDomainValue(quantifier));
        }
        boolean disj = isDisj(relDecl.getOpcode());
        int disjointnessClass = disj ? nextDisjointnessClass++ : 0;
        String deBruijnBase = bindingPath + (negated ? "@neg" : "@pos");

        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            if (candidate.getOpcode() != Opcode.VARIABLE) {
                continue;
            }
            String key = bindingKey(candidate);
            String originalName = candidate.getSourceName();
            String alphaName = "_q" + nextVarId[0];
            String varType = primitiveVarType(candidate.getSourceType());
            QuantiVar qv = new QuantiVar(nextVarId[0], alphaName, originalName, varType);
            qv.setSourceOrigin(sourceOrigin);
            qv.addOriginalName(candidate.getSemanticIdentity());
            qv.mergeExactAlloyType(bindingTypeEvidence(candidate, typeEGraph));
            qv.setQuantifier(quantifier);
            qv.setCardinality(domain.cardinality);
            boolean guardedDomain = domain.domain != null
                    && needsDomainConstraint(domain, varType);
            qv.setDisjointnessClass(disjointnessClass);
            qv.setBindingPath(deBruijnBase);
            qv.setDeBruijnKey(deBruijnBase + "#" + (i - 1) + ":" + qv.getCardinality()
                    + ":" + normalizeType(varType));
            QuantiVar representative = parameterDecl ? qv : slots.bind(qv);
            var frame = retainDeclarationFrame(representative, qv, relDecl, candidate, i - 1, sourceDomain);
            if (frame != null) issuedDomainLowerings.put(frame, guardedDomain);
            checkUnusedBindingProjection(relDecl, candidate, i - 1, representative);
            if (representative == qv) {
                nextVarId[0]++;
            }
            candidate.setAlphaName(representative.getName());
            quantiVars.add(representative);
            if (guardedDomain) {
                var guard = domainConstraint(representative, candidate, domain.domain);
                if (frame != null) {
                    var origin = new DomainGuardOrigin(frame, issuedDeclarationFrames.indexOf(frame));
                    issuedDomainGuards.put(frame, origin);
                    guard.domainGuardOrigin = origin;
                }
                constraints.add(guard);
            }
            if (parameterDecl) {
                params.add(representative);
            }
            if (key != null) {
                env.put(key, representative);
            }
        }
        // This is a source-observed ordered tuple. It does not assert that
        // grouped ONE equals a sequence of nested unique quantifiers.
        if (!parameterDecl && !negated && quantifierOpcode == Opcode.ONE && !disj
                && relDecl.getOpcode() == Opcode.GENERICRELDECL
                && quantiVars.size() >= 2 && quantiVars.size() <= 64
                && children.size() == quantiVars.size() + 1
                && domain.cardinality == Cardinality.ONE
                && typeEGraph != null && typeEGraph.getOpcode() == Opcode.ONE
                && typeEGraph.getChildren().size() == 1
                && typeEGraph.getChildren().get(0).getOpcode() == Opcode.GLOBALBINDING) {
            boolean originalSlots = true;
            for (int i = 0; i < quantiVars.size(); i++) {
                var q = quantiVars.get(i); var variable = children.get(i + 1);
                var type = q.getExactAlloyType();
                originalSlots &= variable.getOpcode() == Opcode.VARIABLE
                        && q.getBindingPath().equals(deBruijnBase)
                        && q.getDeBruijnKey().equals(deBruijnBase + "#" + i + ":ONE:" + normalizeType(q.getTypeName()))
                        && q.getOriginalNames().contains(variable.getSemanticIdentity())
                        && q.getOriginalNames().contains(variable.getSourceName())
                        && type != null && type.hasParserAuthenticatedAncestry()
                        && type.relationArity() == 1
                        && type.sameOccurrenceEvidenceAs(variable.getExactAlloyType())
                        && type.sameOccurrenceEvidenceAs(typeEGraph.getChildren().get(0).getExactAlloyType());
            }
            if (originalSlots) groupedOneOrigins.add(new GroupedOneOrigin(
                    relDecl.getSourceOccurrenceLineage(), List.copyOf(quantiVars),
                    quantiVars.stream().map(QuantiVar::getDeBruijnKey).toList(), deBruijnBase));
        }
        return new RelDeclResult(quantiVars, null);
    }

    private static Boolean emptyDomainValue(Quantifier quantifier) {
        switch (quantifier) {
            case ALL:
            case NO:
            case LONE:
            case NOTONE:
                return true;
            case SOME:
            case ONE:
            case NOTLONE:
                return false;
            default:
                return null;
        }
    }

    @LeanVerifiedRewrite("R0-CORE-013")
    private static Boolean emptyTupleDomainValue(
            EGraphNode quantifierNode,
            Quantifier quantifier) {
        if (quantifier == Quantifier.SUM || quantifier == Quantifier.COMPREHENSION) {
            return null;
        }
        for (EGraphNode child : quantifierNode.getChildren()) {
            if (!isRelDecl(child.getOpcode()) || child.getChildren().isEmpty()) {
                continue;
            }
            EGraphNode normalizedDomain = removeEndNodes(
                    toNNF(cloneEGraph(child.getChildren().get(0)), false));
            if (hasEmptyAdmissibleBindingSet(domainDescriptor(normalizedDomain))) {
                return emptyDomainValue(quantifier);
            }
        }
        return null;
    }

    @LeanVerifiedRewrite("R0-BIND-001")
    private static EGraphNode applyDomainConstraints(EGraphNode body, List<EGraphNode> constraints, Quantifier quantifier) {
        if (constraints.isEmpty()) {
            return body;
        }
        EGraphNode domain = conjoin(null, constraints);
        if (quantifier == Quantifier.ALL) {
            if (body == null) {
                return domain;
            }
            EGraphNode implication = syntheticNode(domain, Opcode.IMPLIES, -1);
            implication.addNormalizedChild(domain);
            implication.addNormalizedChild(body);
            return implication;
        }
        return conjoin(body, constraints);
    }

    private static EGraphNode conjoin(EGraphNode body, List<EGraphNode> constraints) {
        if (constraints.isEmpty()) {
            return body;
        }
        if (body == null && constraints.size() == 1) {
            return constraints.get(0);
        }
        EGraphNode source = body == null ? constraints.get(0) : body;
        EGraphNode conjunction = syntheticNode(source, Opcode.AND, -1);
        if (body != null) {
            conjunction.addNormalizedChild(body);
        }
        for (EGraphNode constraint : constraints) {
            conjunction.addNormalizedChild(constraint);
        }
        return conjunction;
    }

    private static EGraphNode booleanConstant(EGraphNode source, boolean value) {
        EGraphNode constant = syntheticNode(source, Opcode.CONSTANT, value ? -7 : -8);
        constant.setSourceName(Boolean.toString(value));
        constant.setSourceType("Bool");
        constant.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        return constant;
    }

    /**
     * Source-semantic smart rules that must be visible at the certified
     * adapter boundary. Algebraic A/C/I normalization deliberately remains
     * after the certification snapshot and requires its own law evidence.
     */
    private Map<String, QuantiVar> guardedBindingFacts() {
        Map<String, QuantiVar> result = new HashMap<>();
        addGuardedBindingFacts(result, params);
        addGuardedBindingFacts(result, inheritedQuantiVars);
        addGuardedBindingFacts(result, phaseLocalVariables());
        addGuardedBindingFacts(result, matrixQuantiVars);
        return result;
    }

    private List<QuantiVar> phaseLocalVariables() {
        List<QuantiVar> result = new ArrayList<>(phaseLocalBindingImports.size());
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            result.add(imported.variable());
        }
        return result;
    }

    private static void addGuardedBindingFacts(
            Map<String, QuantiVar> target,
            List<QuantiVar> variables) {
        for (QuantiVar variable : variables) {
            target.put(variable.getName(), variable);
            target.put(variable.getDeBruijnKey(), variable);
            for (String original : variable.getOriginalNames()) {
                target.putIfAbsent(original, variable);
            }
        }
    }

    private static EGraphNode normalizePrePrenexSourceRules(EGraphNode node) {
        return normalizeGuardedSourceRules(
                eliminatePrePrenexMembership(node), Collections.emptyMap());
    }

    // Preserve the checked membership shape before source-rule distribution.
    // This walk is never used after declaration ownership has been issued.
    @LeanVerifiedRewrite("R0-BIND-002")
    private static EGraphNode eliminatePrePrenexMembership(EGraphNode node) {
        if (node == null) return null;
        EGraphNode eliminated = eliminateSingleMembershipQuantifier(node);
        if (eliminated != null) node = eliminated;
        if (node.getChildren().isEmpty()) return node;
        List<EGraphNode> children = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            children.add(eliminatePrePrenexMembership(child));
        }
        node.setNormalizedChildren(children);
        return node;
    }

    @LeanVerifiedRewrite({
            "R0-CORE-007", "R0-CORE-020", "R0-CORE-021", "R0-CORE-022",
            "R0-CORE-023", "R0-CORE-024", "R0-REL-004", "R0-REL-034"
    })
    private static EGraphNode normalizeGuardedSourceRules(
            EGraphNode node,
            Map<String, QuantiVar> bindings) {
        if (node == null) {
            return null;
        }
        List<EGraphNode> rewritten = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode normalized = normalizeGuardedSourceRules(child, bindings);
            if (normalized != null && normalized.getOpcode() != Opcode.END) {
                rewritten.add(normalized);
            }
        }
        node.setNormalizedChildren(rewritten);

        if (node.getOpcode() == Opcode.GLOBALBINDING
                && node.getChildren().isEmpty()) {
            EGraphNode abstractCarrier =
                    EGraphNode.parserCertifiedAbstractUnionCarrier(
                            node, Collections.singletonList(node));
            if (abstractCarrier != null) {
                return abstractCarrier;
            }
        }

        EGraphNode relationalRewrite = normalizeRelationalUnaryAndIdentityRules(
                node, rewritten, bindings);
        if (relationalRewrite != node) {
            return relationalRewrite;
        }

        if (node.getOpcode() == Opcode.NOT
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && rewritten.size() == 1) {
            Boolean child = booleanConstantValue(rewritten.get(0));
            if (child != null) {
                return booleanConstant(node, !child);
            }
            EGraphNode rewrittenChild = rewritten.get(0);
            if ((rewrittenChild.getOpcode() == Opcode.AND
                            || rewrittenChild.getOpcode() == Opcode.OR)
                    && EGraphNode.hasBooleanRewriteAuthority(rewrittenChild)
                    && EGraphNode.hasBooleanOperands(rewrittenChild)) {
                return normalizeGuardedSourceRules(
                        removeEndNodes(toNNF(node, false)), bindings);
            }
        }

        if ((node.getOpcode() == Opcode.AND || node.getOpcode() == Opcode.OR)
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)) {
            boolean conjunction = node.getOpcode() == Opcode.AND;
            boolean absorbing = !conjunction;
            boolean neutral = conjunction;
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                Boolean value = booleanConstantValue(child);
                if (value != null && value == absorbing) {
                    return booleanConstant(node, absorbing);
                }
                if (value == null || value != neutral) {
                    retained.add(child);
                }
            }
            if (retained.isEmpty()) {
                return booleanConstant(node, neutral);
            }
            if (containsSemanticComplement(retained)) {
                return booleanConstant(node, !conjunction);
            }
            if (retained.size() == 1) {
                return retained.get(0);
            }
            node.setNormalizedChildren(retained);
            if (EGraphNode.containsCertifiedCoveredDualBranch(
                    node, node.getChildClasses())) {
                return booleanConstant(node, !conjunction);
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
        }

        if (node.getOpcode() == Opcode.PLUS) {
            for (EGraphNode child : rewritten) {
                if (isUniv(child)) {
                    return child;
                }
            }
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!isNone(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            EGraphNode partition =
                    EGraphNode.parserCertifiedDifferencePartitionRecombination(
                            node, node.getChildClasses());
            if (partition != null) {
                return normalizeGuardedSourceRules(partition, bindings);
            }
            EGraphNode structuralAbsorption =
                    EGraphNode.parserCertifiedStructuralLatticeAbsorption(
                            node, node.getChildClasses());
            if (structuralAbsorption != null) {
                return normalizeGuardedSourceRules(
                        structuralAbsorption, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionLatticeFactoring(
                            node, node.getChildClasses());
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode difference = EGraphNode.parserCertifiedDifferenceFactoring(
                    node, node.getChildClasses());
            if (difference != null) {
                return difference;
            }
            EGraphNode join = EGraphNode.parserCertifiedJoinUnionFactoring(
                    node, node.getChildClasses());
            if (join != null) {
                // Factoring an outer chain coordinate can expose a union of
                // shorter JOINs at the next coordinate. Close that strictly
                // smaller duplicated-chain problem before the certification
                // matrix is cloned; post-snapshot transfer may then compare
                // only the certified ACI operand, never a distributive edit.
                return normalizeGuardedSourceRules(join, bindings);
            }
            EGraphNode productCarrier =
                    EGraphNode.parserCertifiedProductUnionCarrier(
                            node, node.getChildClasses());
            if (productCarrier != null) {
                return productCarrier;
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
            EGraphNode abstractCarrier =
                    EGraphNode.parserCertifiedAbstractUnionCarrier(
                            node, retained);
            if (abstractCarrier != null) {
                return abstractCarrier;
            }
            retained = removeSubrelationsCoveredByFullCarriers(retained);
            if (retained.size() == 1) {
                return retained.get(0);
            }
            if (!retained.equals(node.getChildren())) {
                node.setNormalizedChildren(retained);
            }
        }

        if (node.getOpcode() == Opcode.INTERSECT) {
            for (EGraphNode child : rewritten) {
                if (isNone(child)) {
                    return child;
                }
            }
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!isUniv(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            retained = removeFullCarriersContainingSubrelations(retained);
            if (retained.size() == 1) {
                return retained.get(0);
            }
            if (!retained.equals(node.getChildren())) {
                node.setNormalizedChildren(retained);
            }
            EGraphNode disjoint =
                    EGraphNode.parserCertifiedDifferenceDisjointness(
                            node, node.getChildClasses());
            if (disjoint != null) {
                return disjoint;
            }
            EGraphNode structuralAbsorption =
                    EGraphNode.parserCertifiedStructuralLatticeAbsorption(
                            node, node.getChildClasses());
            if (structuralAbsorption != null) {
                return normalizeGuardedSourceRules(
                        structuralAbsorption, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionLatticeFactoring(
                            node, node.getChildClasses());
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode productIntersection =
                    EGraphNode.parserCertifiedProductIntersectionFactoring(
                            node, node.getChildClasses());
            if (productIntersection != null) {
                return normalizeGuardedSourceRules(
                        productIntersection, bindings);
            }
            EGraphNode difference = EGraphNode.parserCertifiedDifferenceFactoring(
                    node, node.getChildClasses());
            if (difference != null) {
                return difference;
            }
            EGraphNode extractedDifference =
                    EGraphNode.parserCertifiedIntersectionDifferenceExtraction(
                            node, node.getChildClasses());
            if (extractedDifference != null) {
                return normalizeGuardedSourceRules(
                        extractedDifference, bindings);
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
        }

        if (node.getOpcode() == Opcode.MINUS && rewritten.size() == 2) {
            EGraphNode left = rewritten.get(0);
            EGraphNode right = rewritten.get(1);
            EGraphNode partition =
                    EGraphNode.parserCertifiedDifferencePartitionNormalization(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (partition != null) {
                return normalizeGuardedSourceRules(partition, bindings);
            }
            if (isNone(left)) {
                return left;
            }
            if (isNone(right)) {
                return left;
            }
            if (isUniv(right)
                    || EGraphNode.sameSemanticInvocation(left, right)) {
                return EGraphNode.derivedSetConstant(
                        node,
                        syntheticId(node, -9),
                        "none",
                        node.getExactAlloyType());
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionDifferenceFactoring(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode productDifference =
                    EGraphNode.parserCertifiedProductDifferenceFactoring(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (productDifference != null) {
                return normalizeGuardedSourceRules(
                        productDifference, bindings);
            }
            EGraphNode rightNestedDifference =
                    EGraphNode.parserCertifiedRightNestedDifference(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (rightNestedDifference != null) {
                return normalizeGuardedSourceRules(
                        rightNestedDifference, bindings);
            }
            EGraphNode nestedDifference =
                    EGraphNode.parserCertifiedLeftNestedDifference(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (nestedDifference != null) {
                return normalizeGuardedSourceRules(
                        nestedDifference, bindings);
            }
        }


        if ((node.getOpcode() == Opcode.ARROW || node.getOpcode() == Opcode.JOIN)
                && EGraphNode.isExactRelationNode(node)) {
            for (EGraphNode child : rewritten) {
                if (isNone(child)) {
                    return EGraphNode.derivedSetConstant(
                            node,
                            syntheticId(node, -10),
                            "none",
                            node.getExactAlloyType());
                }
            }
        }

        Boolean reflexiveComparison =
                EGraphNode.parserCertifiedReflexiveComparison(
                        node, node.getChildClasses());
        if (reflexiveComparison != null) {
            return booleanConstant(node, reflexiveComparison);
        }

        Boolean structuralSubset =
                EGraphNode.parserCertifiedStructuralSubsetComparison(
                        node, node.getChildClasses());
        if (structuralSubset != null) {
            return booleanConstant(node, structuralSubset);
        }

        EGraphNode subsetExpansion =
                EGraphNode.parserCertifiedSubsetLatticeExpansion(
                        node, node.getChildClasses());
        if (subsetExpansion != null) {
            return normalizeGuardedSourceRules(subsetExpansion, bindings);
        }

        if ((node.getOpcode() == Opcode.IN
                        || node.getOpcode() == Opcode.NOT_IN)
                && rewritten.size() == 2
                && isNone(rewritten.get(1))
                && isStaticallyNonemptySet(rewritten.get(0), bindings)) {
            return booleanConstant(node, node.getOpcode() == Opcode.NOT_IN);
        }

        // Keep variable membership visible until the prefix supplies binding
        // facts. The checked rule above can then consume ONE/SOME admissions.
        boolean awaitingBindingFacts = bindings.isEmpty() && rewritten.size() == 2
                && rewritten.get(0).getOpcode() == Opcode.VARIABLE;
        EGraphNode emptyRightSubset = awaitingBindingFacts ? null
                : EGraphNode.parserCertifiedEmptyRightSubsetExpansion(
                        node, node.getChildClasses());
        if (emptyRightSubset != null) {
            return normalizeGuardedSourceRules(emptyRightSubset, bindings);
        }

        if (node.getOpcode() == Opcode.IN && rewritten.size() == 2) {
            EGraphNode lhs = rewritten.get(0);
            EGraphNode rhs = rewritten.get(1);
            if (isNone(lhs)
                    && (isNone(rhs)
                            || EGraphNode.hasCompatibleRelationArity(lhs, rhs))) {
                return booleanConstant(node, true);
            }
            if (isNone(rhs) && isStaticallyNonemptySet(lhs, bindings)) {
                return booleanConstant(node, false);
            }
            if (isUniv(rhs)) {
                return booleanConstant(node, true);
            }
        }

        if (node.getOpcode() == Opcode.NOT_IN && rewritten.size() == 2) {
            EGraphNode lhs = rewritten.get(0);
            EGraphNode rhs = rewritten.get(1);
            if ((isNone(lhs)
                            && (isNone(rhs)
                                    || EGraphNode.hasCompatibleRelationArity(lhs, rhs)))
                    || isUniv(rhs)) {
                return booleanConstant(node, false);
            }
            if (isNone(rhs) && isStaticallyNonemptySet(lhs, bindings)) {
                return booleanConstant(node, true);
            }
        }

        if (node.hasUnaryCardinalityOperand() && isNone(rewritten.get(0))) {
            switch (node.getOpcode()) {
                case NO:
                case LONE:
                    return booleanConstant(node, true);
                case SOME:
                case ONE:
                    return booleanConstant(node, false);
                default:
                    break;
            }
        }
        return node;
    }

    private static boolean containsSemanticComplement(List<EGraphNode> nodes) {
        for (int left = 0; left < nodes.size(); left++) {
            for (int right = left + 1; right < nodes.size(); right++) {
                if (EGraphNode.areSemanticComplements(
                        nodes.get(left), nodes.get(right))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUniv(EGraphNode node) {
        return EGraphNode.isSetConstant(node, "univ");
    }

    @LeanVerifiedRewrite({"R0-REL-028", "R0-REL-033"})
    private static EGraphNode normalizeRelationalUnaryAndIdentityRules(
            EGraphNode node,
            List<EGraphNode> rewritten,
            Map<String, QuantiVar> bindings) {
        if ((node.getOpcode() == Opcode.SOME
                        || node.getOpcode() == Opcode.NO)
                && rewritten.size() == 1) {
            EGraphNode difference =
                    EGraphNode.parserCertifiedDifferenceCardinalityExpansion(
                            node, node.getChildClasses().get(0));
            if (difference != null) {
                return normalizeGuardedSourceRules(difference, bindings);
            }
            EGraphNode expanded =
                    EGraphNode.parserCertifiedUnionCardinalityExpansion(
                            node, node.getChildClasses().get(0));
            if (expanded != null) {
                return expanded;
            }
        }

        if ((node.getOpcode() == Opcode.DOMAIN
                        || node.getOpcode() == Opcode.RANGE)
                && rewritten.size() == 2) {
            EGraphNode identity =
                    EGraphNode.parserCertifiedRestrictionIdentityOrZero(
                            node, node.getEClassRef());
            if (identity != null) {
                return identity;
            }
            EGraphNode unary =
                    EGraphNode.parserCertifiedUnaryRestrictionOrientation(
                            node, node.getEClassRef());
            if (unary != null) {
                return unary;
            }
            EGraphNode restriction = EGraphNode.parserCertifiedNestedRestriction(
                    node,
                    node.getChildClasses().get(0),
                    node.getChildClasses().get(1));
            if (restriction != null) {
                return restriction;
            }
        }

        if (node.getOpcode() == Opcode.JOIN && rewritten.size() == 2) {
            EGraphNode contextualRestriction =
                    EGraphNode.parserCertifiedJoinRestrictionNormalization(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (contextualRestriction != null) {
                return normalizeGuardedSourceRules(
                        contextualRestriction, bindings);
            }
        }

        if (node.getOpcode() == Opcode.JOIN
                && rewritten.size() >= 2
                && EGraphNode.isExactRelationNode(node)
                && rewritten.stream().allMatch(EGraphNode::isExactRelationNode)) {
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!EGraphNode.isIdentityRelation(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            return node;
        }

        if (node.getOpcode() == Opcode.TRANSPOSE
                && rewritten.size() == 1
                && isExactBinaryRelation(node)) {
            EGraphNode empty =
                    EGraphNode.parserCertifiedEmptyRelationalUnaryIdentity(
                            node, node.getChildClasses().get(0));
            if (empty != null) {
                return empty;
            }
            EGraphNode child = rewritten.get(0);
            if (EGraphNode.isIdentityRelation(child)
                    && sameExactRelationOccurrence(node, child)) {
                return child;
            }
            if (child.getOpcode() == Opcode.TRANSPOSE
                    && child.getChildren().size() == 1
                    && isExactBinaryRelation(child)
                    && sameExactRelationOccurrence(
                            node, child.getChildren().get(0))) {
                return child.getChildren().get(0);
            }
            if (child.getOpcode() == Opcode.ARROW
                    && child.getChildren().size() == 2
                    && isExactBinaryRelation(child)) {
                EGraphNode reversed = copyShallow(child, Opcode.ARROW);
                reversed.setExactAlloyType(node.getExactAlloyType());
                reversed.setNormalizedChildren(List.of(
                        child.getChildren().get(1),
                        child.getChildren().get(0)));
                return reversed;
            }
            EGraphNode reversedJoin =
                    EGraphNode.parserCertifiedTransposeJoinReversal(
                            node, node.getChildClasses().get(0));
            if (reversedJoin != null) {
                return reversedJoin;
            }
            EGraphNode closure =
                    EGraphNode.parserCertifiedTransposeClosureCommutation(
                            node, node.getChildClasses().get(0));
            if (closure != null) {
                return normalizeGuardedSourceRules(closure, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedTransposeRestrictionSwap(
                            node, node.getChildClasses().get(0));
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode distributed = distributeTransposeThroughContainer(
                    node, child);
            if (distributed != null) {
                return distributed;
            }
        }

        if ((node.getOpcode() == Opcode.CLOSURE
                        || node.getOpcode() == Opcode.RCLOSURE)
                && rewritten.size() == 1
                && isExactBinaryRelation(node)) {
            EGraphNode empty =
                    EGraphNode.parserCertifiedEmptyRelationalUnaryIdentity(
                            node, node.getChildClasses().get(0));
            if (empty != null) {
                return empty;
            }
            EGraphNode child = rewritten.get(0);
            if (EGraphNode.isIdentityRelation(child)
                    && sameExactRelationOccurrence(node, child)) {
                return child;
            }
            if ((child.getOpcode() == Opcode.CLOSURE
                            || child.getOpcode() == Opcode.RCLOSURE)
                    && child.getChildren().size() == 1
                    && isExactBinaryRelation(child)) {
                Opcode resultOpcode = node.getOpcode() == Opcode.RCLOSURE
                                || child.getOpcode() == Opcode.RCLOSURE
                        ? Opcode.RCLOSURE : Opcode.CLOSURE;
                if (child.getOpcode() == resultOpcode
                        && sameExactRelationOccurrence(node, child)) {
                    return child;
                }
                EGraphNode collapsed = copyShallow(node, resultOpcode);
                collapsed.setNormalizedChildren(Collections.singletonList(
                        child.getChildren().get(0)));
                return collapsed;
            }
        }
        return node;
    }

    @LeanVerifiedRewrite("R0-REL-001")
    private static EGraphNode distributeTransposeThroughContainer(
            EGraphNode transpose,
            EGraphNode container) {
        EGraphNode distributed = buildTransposeContainer(
                transpose,
                container,
                transpose.getExactAlloyType(),
                -30);
        if (distributed != null) {
            distributed.preserveSourceOccurrenceLineageFrom(transpose);
        }
        return distributed;
    }

    private static EGraphNode buildTransposeContainer(
            EGraphNode owner,
            EGraphNode container,
            is.fivefivefive.ACGN.alloy.ExactAlloyType resultType,
            int salt) {
        Opcode opcode = container.getOpcode();
        boolean aciContainer = opcode == Opcode.PLUS
                || opcode == Opcode.INTERSECT;
        if ((!aciContainer && opcode != Opcode.MINUS)
                || !isExactBinaryRelation(container)
                || (aciContainer && (!container.isSetFlexibleArity()
                        || container.getChildren().size() < 2))
                || (opcode == Opcode.MINUS
                        && container.getChildren().size() != 2)) {
            return null;
        }
        List<EGraphNode> transposedOperands = new ArrayList<>(
                container.getChildren().size());
        int index = 0;
        for (EGraphNode operand : container.getChildren()) {
            EGraphNode derived = buildNormalizedTransposeOperand(
                    owner, operand, salt - index++ * 17);
            if (derived == null) {
                return null;
            }
            transposedOperands.add(derived);
        }
        EGraphNode distributed = copyShallow(container, opcode);
        distributed.setExactAlloyType(resultType);
        distributed.setNormalizedChildren(transposedOperands);
        return distributed;
    }

    private static EGraphNode buildNormalizedTransposeOperand(
            EGraphNode owner,
            EGraphNode operand,
            int salt) {
        if (!isExactBinaryRelation(operand)) {
            return null;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact;
        try {
            exact = is.fivefivefive.ACGN.alloy.ExactAlloyType
                    .parserCertifiedTranspose(operand.getExactAlloyType());
        } catch (IllegalArgumentException rejectedProof) {
            return null;
        }
        if (!owner.getExactAlloyType().sharesParserModuleAuthorityWith(exact)) {
            return null;
        }
        if (operand.getOpcode() == Opcode.ARROW
                && operand.getChildren().size() == 2) {
            EGraphNode reversed = copyShallow(operand, Opcode.ARROW);
            reversed.setExactAlloyType(exact);
            reversed.setNormalizedChildren(List.of(
                    operand.getChildren().get(1),
                    operand.getChildren().get(0)));
            return reversed;
        }
        EGraphNode distributed = buildTransposeContainer(
                owner, operand, exact, salt - 1);
        if (distributed != null) {
            return distributed;
        }
        EGraphNode transpose = EGraphNode.inOwningArena(
                owner,
                syntheticId(owner, salt),
                Opcode.TRANSPOSE,
                new ArrayList<>(),
                false,
                1,
                false,
                owner.getMetatype(),
                owner.getSemanticProfile());
        transpose.setSourceName("UNOPE_TRANSPOSE");
        transpose.setSourceType(operand.getSourceType());
        transpose.setExactAlloyType(exact);
        transpose.addNormalizedChild(operand);
        return transpose;
    }

    private static boolean isExactBinaryRelation(EGraphNode node) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact =
                node == null ? null : node.getExactAlloyType();
        return exact != null
                && (exact.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                        || exact.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.EMPTY_RELATION)
                && exact.relationArity() == 2;
    }

    private static boolean sameExactRelationOccurrence(
            EGraphNode left,
            EGraphNode right) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType leftType =
                left == null ? null : left.getExactAlloyType();
        is.fivefivefive.ACGN.alloy.ExactAlloyType rightType =
                right == null ? null : right.getExactAlloyType();
        return leftType != null
                && rightType != null
                && (leftType.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                        || leftType.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.EMPTY_RELATION)
                && leftType.sameOccurrenceEvidenceAs(rightType);
    }

    @LeanVerifiedRewrite("R0-REL-032")
    private static List<EGraphNode> removeSubrelationsCoveredByFullCarriers(
            List<EGraphNode> children) {
        List<EGraphNode> retained = new ArrayList<>(children.size());
        for (int candidateIndex = 0;
                candidateIndex < children.size();
                candidateIndex++) {
            EGraphNode candidate = children.get(candidateIndex);
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingCarriers(
                    candidate, candidateIndex, children);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        return retained;
    }

    private static EGraphNode pruneSubrelationsCoveredBySiblingCarriers(
            EGraphNode candidate,
            int candidateIndex,
            List<EGraphNode> siblings) {
        if (isCoveredBySiblingFullCarrier(candidate, candidateIndex, siblings)) {
            return null;
        }
        if (candidate.getOpcode() != Opcode.PLUS) {
            return candidate;
        }
        List<EGraphNode> retained = new ArrayList<>(candidate.getChildren().size());
        for (EGraphNode child : candidate.getChildren()) {
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingSignatures(
                    child, siblings);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        if (retained.isEmpty()) {
            return null;
        }
        if (retained.size() == 1) {
            return retained.get(0);
        }
        if (retained.size() != candidate.getChildren().size()) {
            candidate.setNormalizedChildren(retained);
        }
        return candidate;
    }

    private static EGraphNode pruneSubrelationsCoveredBySiblingSignatures(
            EGraphNode candidate,
            List<EGraphNode> siblings) {
        for (EGraphNode carrier : siblings) {
            if (EGraphNode.isParserCertifiedSubrelationOfFullSignature(
                    candidate, carrier)) {
                return null;
            }
        }
        if (candidate.getOpcode() != Opcode.PLUS) {
            return candidate;
        }
        List<EGraphNode> retained = new ArrayList<>(candidate.getChildren().size());
        for (EGraphNode child : candidate.getChildren()) {
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingSignatures(
                    child, siblings);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        if (retained.isEmpty()) {
            return null;
        }
        if (retained.size() == 1) {
            return retained.get(0);
        }
        if (retained.size() != candidate.getChildren().size()) {
            candidate.setNormalizedChildren(retained);
        }
        return candidate;
    }

    private static boolean isCoveredBySiblingFullCarrier(
            EGraphNode candidate,
            int candidateIndex,
            List<EGraphNode> siblings) {
        for (int carrierIndex = 0;
                carrierIndex < siblings.size();
                carrierIndex++) {
            if (carrierIndex == candidateIndex) {
                continue;
            }
            if (EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                            candidate, siblings.get(carrierIndex))
                    && (!EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                    siblings.get(carrierIndex), candidate)
                            || (candidateIndex >= 0
                                    && candidateIndex > carrierIndex))) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-REL-032")
    private static List<EGraphNode> removeFullCarriersContainingSubrelations(
            List<EGraphNode> children) {
        List<EGraphNode> retained = new ArrayList<>(children.size());
        for (int carrierIndex = 0;
                carrierIndex < children.size();
                carrierIndex++) {
            EGraphNode carrier = children.get(carrierIndex);
            boolean redundant = false;
            for (int candidateIndex = 0;
                    candidateIndex < children.size();
                    candidateIndex++) {
                if (candidateIndex == carrierIndex) {
                    continue;
                }
                if (EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                children.get(candidateIndex), carrier)
                        && (!EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                        carrier, children.get(candidateIndex))
                                || carrierIndex > candidateIndex)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                retained.add(carrier);
            }
        }
        return retained;
    }

    private static Boolean booleanConstantValue(EGraphNode node) {
        if (EGraphNode.isBooleanConstant(node, true)) {
            return Boolean.TRUE;
        }
        if (EGraphNode.isBooleanConstant(node, false)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isStaticallyNonemptySet(
            EGraphNode node,
            Map<String, QuantiVar> bindings) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() != Opcode.VARIABLE) {
            return false;
        }
        QuantiVar variable = bindings.get(node.getAlphaName());
        if (variable == null) {
            variable = bindings.get(node.getSourceName());
        }
        if (variable == null) {
            variable = bindings.get(bindingKey(node));
        }
        if (variable == null) {
            return false;
        }
        Cardinality cardinality = variable.getCardinality();
        return cardinalityGuaranteesNonempty(cardinality);
    }

    private static boolean cardinalityGuaranteesNonempty(Cardinality cardinality) {
        return cardinality == Cardinality.ONE || cardinality == Cardinality.SOME;
    }

    private static String primitiveVarType(String type) {
        String normalized = normalizeType(type);
        return normalized.isEmpty() ? type : normalized;
    }

    private static boolean needsDomainConstraint(DomainDescriptor domain, String primitiveType) {
        if (domain == null || domain.domain == null) {
            return false;
        }
        String primitiveDomain = primitiveDomainName(domain.domain);
        if (primitiveDomain != null && primitiveType != null) {
            return !normalizeType(primitiveDomain).equals(normalizeType(primitiveType));
        }
        return true;
    }

    private static boolean domainUsesPrimitiveCarrier(
            EGraphNode domain,
            String primitiveType) {
        if (domain == null || primitiveType == null) {
            return false;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact = domain.getExactAlloyType();
        if (exact != null
                && exact.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                && exact.relationArity() == 1
                && !exact.alternatives().isEmpty()) {
            String required = normalizeType(primitiveType);
            for (List<String> alternative : exact.alternatives()) {
                if (alternative.size() != 1
                        || !normalizeType(alternative.get(0)).equals(required)) {
                    return false;
                }
            }
            return true;
        }
        String primitiveDomain = primitiveDomainName(domain);
        return primitiveDomain != null
                && normalizeType(primitiveDomain).equals(normalizeType(primitiveType));
    }

    private static DomainDescriptor domainDescriptor(EGraphNode typeEGraph) {
        if (typeEGraph == null) {
            return new DomainDescriptor(null, Cardinality.ONE);
        }
        Cardinality cardinality = cardinalityOf(typeEGraph.getOpcode());
        if (cardinality != null && typeEGraph.getChildren().size() == 1) {
            return new DomainDescriptor(typeEGraph.getChildren().get(0), cardinality);
        }
        // Alloy declaration domains default to exactly one value. Explicit
        // set/lone/some/one wrappers above override this source-language default.
        return new DomainDescriptor(typeEGraph, Cardinality.ONE);
    }

    private static String primitiveDomainName(EGraphNode typeEGraph) {
        if (typeEGraph == null) {
            return null;
        }
        if (typeEGraph.getOpcode() == Opcode.GLOBALBINDING || typeEGraph.getOpcode() == Opcode.CONSTANT) {
            return firstNonEmpty(typeEGraph.getSourceName(), typeEGraph.getSourceType());
        }
        return null;
    }

    private static Cardinality cardinalityOf(Opcode opcode) {
        switch (opcode) {
            case ONE:
                return Cardinality.ONE;
            case SOME:
                return Cardinality.SOME;
            case LONE:
                return Cardinality.LONE;
            case EXACTLY:
                return Cardinality.EXACTLY;
            case SETOF:
                return Cardinality.SET;
            default:
                return null;
        }
    }

    private static boolean isNone(EGraphNode node) {
        return EGraphNode.isSetConstant(node, "none");
    }

    private static boolean hasEmptyAdmissibleBindingSet(DomainDescriptor domain) {
        if (domain == null || !isStaticallyEmptySet(domain.domain)) {
            return false;
        }
        return domain.cardinality == Cardinality.SOME
                || domain.cardinality == Cardinality.ONE;
    }

    private static boolean isStaticallyEmptySet(EGraphNode node) {
        if (node == null) {
            return false;
        }
        if (isNone(node)) {
            return true;
        }
        if (node.getOpcode() == Opcode.INTERSECT) {
            for (EGraphNode child : node.getChildren()) {
                if (isStaticallyEmptySet(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.getOpcode() == Opcode.MINUS && node.getChildren().size() == 2) {
            EGraphNode left = node.getChildren().get(0);
            EGraphNode right = node.getChildren().get(1);
            return isStaticallyEmptySet(left)
                    || EGraphNode.sameSemanticInvocation(left, right);
        }
        return false;
    }

    private static EGraphNode domainConstraint(QuantiVar qv, EGraphNode sourceVariable, EGraphNode domain) {
        EGraphNode constraint = syntheticNode(sourceVariable, Opcode.IN, -1);
        EGraphNode variable = EGraphNode.inOwningArena(
                sourceVariable,
                sourceVariable.getId(),
                Opcode.VARIABLE,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.ATOMIC,
                sourceVariable.getSemanticProfile());
        variable.setSourceName(sourceVariable.getSourceName());
        variable.setSourceType(qv.getTypeName());
        boolean sourceBound = hasSourceBoundDomainEvidence(qv, sourceVariable);
        variable.setExactAlloyType(
                sourceBound || authenticatedEmptyBindingType(qv.getExactAlloyType())
                        ? qv.getExactAlloyType()
                        : is.fivefivefive.ACGN.alloy.ExactAlloyType.unaryRelation(
                                qv.getTypeName()));
        variable.setAlphaName(qv.getName());
        if (sourceBound) {
            // This is a new occurrence of the same binding, not a clone of its
            // source occurrence. Keep its fresh lineage and exact parser type.
            variable.preserveSemanticIdentityFrom(sourceVariable);
        }
        constraint.addNormalizedChild(variable);
        constraint.addNormalizedChild(cloneEGraph(domain));
        return constraint;
    }

    private static boolean hasSourceBoundDomainEvidence(QuantiVar binding, EGraphNode source) {
        var exact = binding.getExactAlloyType();
        return source.getOpcode() == Opcode.VARIABLE
                && binding.getName().equals(source.getAlphaName())
                && source.getSemanticIdentity() != null
                && binding.getOriginalNames().contains(source.getSemanticIdentity())
                && binding.getOriginalNames().contains(source.getSourceName())
                && exact != null && exact.relationArity() == 1
                && exact.hasParserAuthenticatedAncestry()
                && exact.sameOccurrenceEvidenceAs(source.getExactAlloyType());
    }

    @LeanVerifiedRewrite({"R0-CORE-007", "R0-CORE-008", "R0-CORE-009"})
    private static EGraphNode toNNF(EGraphNode node, boolean negated) {
        if (node == null) {
            return null;
        }
        Opcode opcode = node.getOpcode();
        if (opcode == Opcode.END) {
            return null;
        }
        boolean booleanIte = opcode == Opcode.ITE;
        if ((isBooleanBranchConnective(opcode) || booleanIte)
                && (!EGraphNode.hasBooleanRewriteAuthority(node)
                        || !EGraphNode.hasBooleanOperands(node))) {
            EGraphNode retained = copyShallow(node, opcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode retainedChild = toNNF(child, false);
                if (retainedChild != null) {
                    retained.addNormalizedChild(retainedChild);
                }
            }
            return negated
                    ? syntheticUnary(node, Opcode.NOT, retained, -1)
                    : retained;
        }
        if (opcode == Opcode.NOT && node.getChildren().size() == 1) {
            return toNNF(node.getChildren().get(0), !negated);
        }
        if (isQuantifierNode(node)) {
            Opcode rewrittenOpcode = opcode;
            boolean negateBody = false;
            boolean retainOuterNegation = negated;
            if (negated) {
                switch (opcode) {
                    case FORALL:
                        rewrittenOpcode = Opcode.EXISTS;
                        negateBody = true;
                        retainOuterNegation = false;
                        break;
                    case EXISTS:
                        rewrittenOpcode = Opcode.FORALL;
                        negateBody = true;
                        retainOuterNegation = false;
                        break;
                    case NO:
                        rewrittenOpcode = Opcode.EXISTS;
                        retainOuterNegation = false;
                        break;
                    default:
                        break;
                }
            }
            EGraphNode rewritten = copyQuantifierShallow(node, rewrittenOpcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(
                        child, negateBody && !isRelDecl(child.getOpcode()));
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return retainOuterNegation
                    ? syntheticUnary(node, Opcode.NOT, rewritten, -1)
                    : rewritten;
        }
        if (opcode == Opcode.IFF
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            return toNNF(expandIff(node, negated), false);
        }
        if (opcode == Opcode.IMPLIES
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = node.getChildren().get(0);
            EGraphNode right = node.getChildren().get(1);
            if (negated) {
                EGraphNode conjunction = syntheticNode(node, Opcode.AND, -1);
                conjunction.addNormalizedChild(toNNF(left, false));
                conjunction.addNormalizedChild(toNNF(right, true));
                return conjunction;
            }
            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -1);
            disjunction.addNormalizedChild(toNNF(left, true));
            disjunction.addNormalizedChild(toNNF(right, false));
            return disjunction;
        }
        if (opcode == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            return toNNF(expandIte(node, negated), false);
        }
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            EGraphNode rewritten = copyShallow(node, negated ? dualBooleanOpcode(opcode) : opcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(child, negated);
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }
        Opcode dual = negated ? dualOpcode(opcode) : opcode;
        if (dual != null) {
            EGraphNode rewritten = copyShallow(node, dual);
            if ((opcode == Opcode.IN && dual == Opcode.NOT_IN) || (opcode == Opcode.NOT_IN && dual == Opcode.IN))
                rewritten.domainGuardOrigin = node.domainGuardOrigin;
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(child, negated && dualNegatesChildren(opcode));
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }
        if (negated) {
            return syntheticUnary(node, Opcode.NOT, toNNF(node, false), -1);
        }
        EGraphNode rewritten = copyShallow(node, opcode);
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = toNNF(child, false);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    @LeanVerifiedRewrite("R0-CORE-002")
    private static EGraphNode removeEndNodes(EGraphNode node) {
        if (node == null || node.getOpcode() == Opcode.END) {
            return null;
        }
        List<EGraphNode> rewrittenChildren = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = removeEndNodes(child);
            if (rewrittenChild != null) {
                rewrittenChildren.add(rewrittenChild);
            }
        }
        node.setNormalizedChildren(rewrittenChildren);
        if (isAssociative(node.getOpcode())
                && node.getChildren().size() == 1
                && (!isBooleanBranchConnective(node.getOpcode())
                        || (EGraphNode.hasBooleanRewriteAuthority(node)
                                && EGraphNode.hasBooleanOperands(node)))) {
            return node.getChildren().get(0);
        }
        if (isUnaryOperator(node.getOpcode()) && node.getChildren().isEmpty()) {
            return null;
        }
        if (isBinaryOperator(node.getOpcode()) && node.getChildren().size() < 2) {
            return null;
        }
        if (isDanglingStructuralMarker(node)) {
            return null;
        }
        return node;
    }

    private static boolean isDanglingStructuralMarker(EGraphNode node) {
        if (!node.getChildren().isEmpty()) {
            return false;
        }
        switch (node.getOpcode()) {
            case VARIABLE:
            case GLOBALBINDING:
            case CONSTANT:
            case CALL:
            case REF:
            case LET:
            case PREDICATE:
            case FUNCTION:
                return false;
            default:
                return true;
        }
    }

    private static boolean isUnaryOperator(Opcode opcode) {
        return opcode == Opcode.NOT || opcode == Opcode.SOME || opcode == Opcode.NO || opcode == Opcode.LONE
                || opcode == Opcode.ONE || opcode == Opcode.SETOF || opcode == Opcode.EXACTLY
                || opcode == Opcode.TRANSPOSE || opcode == Opcode.RCLOSURE || opcode == Opcode.CLOSURE
                || opcode == Opcode.CARDINALITY || opcode == Opcode.CAST2INT || opcode == Opcode.CAST2SIGINT
                || opcode == Opcode.PRIME || opcode == Opcode.BEFORE || opcode == Opcode.HISTORICALLY
                || opcode == Opcode.ONCE || opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY
                || opcode == Opcode.AFTER;
    }

    private static boolean isBinaryOperator(Opcode opcode) {
        switch (opcode) {
            case IMPLIES:
            case IFF:
            case EQUALS:
            case NOT_EQUALS:
            case IN:
            case NOT_IN:
            case GT:
            case GTE:
            case LT:
            case LTE:
            case NOT_GT:
            case NOT_GTE:
            case NOT_LT:
            case NOT_LTE:
            case JOIN:
            case ARROW:
            case ANY_ARROW_SOME:
            case ANY_ARROW_ONE:
            case ANY_ARROW_LONE:
            case SOME_ARROW_ANY:
            case SOME_ARROW_SOME:
            case SOME_ARROW_ONE:
            case SOME_ARROW_LONE:
            case ONE_ARROW_ANY:
            case ONE_ARROW_SOME:
            case ONE_ARROW_ONE:
            case ONE_ARROW_LONE:
            case LONE_ARROW_ANY:
            case LONE_ARROW_SOME:
            case LONE_ARROW_ONE:
            case LONE_ARROW_LONE:
            case ISSEQ_ARROW_LONE:
            case DOMAIN:
            case RANGE:
            case PLUSPLUS:
            case MINUS:
            case IMINUS:
            case DIV:
            case REM:
            case SHL:
            case SHA:
            case SHR:
            case UNTIL:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
                return true;
            default:
                return false;
        }
    }

    @LeanVerifiedRewrite({"R0-CORE-017", "R0-CORE-018", "R0-CORE-019"})
    private static EGraphNode normalizeAssociativeCommutative(EGraphNode node) {
        if (node == null) {
            return null;
        }
        List<EGraphNode> rewrittenChildren = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode normalizedChild = normalizeAssociativeCommutative(child);
            if (normalizedChild == null) {
                continue;
            }
            if (node.sameFlatOperatorInstance(normalizedChild)) {
                rewrittenChildren.addAll(normalizedChild.getChildren());
            } else {
                rewrittenChildren.add(normalizedChild);
            }
        }
        if (node.isOrderInsensitive()) {
            Collections.sort(rewrittenChildren, Comparator.comparing(NormalForm::sortKey));
        }
        node.setNormalizedChildren(rewrittenChildren);
        return node;
    }

    /**
     * Exposes each maximal Boolean ACI region to the slot allocator without
     * applying commutativity or idempotence before certification. Quantifier,
     * temporal, and all other operator nodes remain explicit barriers.
     */
    @LeanVerifiedRewrite("R0-CORE-017")
    private static EGraphNode flattenBooleanAssociationForPrenex(
            EGraphNode node) {
        if (node == null) {
            return null;
        }
        EGraphNode flattened = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode flattenedChild = flattenBooleanAssociationForPrenex(child);
            if (flattenedChild != null
                    && (node.getOpcode() == Opcode.AND
                            || node.getOpcode() == Opcode.OR)
                    && node.sameFlatOperatorInstance(flattenedChild)) {
                for (EGraphNode grandchild : flattenedChild.getChildren()) {
                    flattened.addNormalizedChild(grandchild);
                }
            } else if (flattenedChild != null) {
                flattened.addNormalizedChild(flattenedChild);
            }
        }
        return flattened;
    }

    @LeanVerifiedRewrite("R0-CORE-005")
    private static EGraphNode expandIff(EGraphNode node, boolean negated) {
        EGraphNode left = node.getChildren().get(0);
        EGraphNode right = node.getChildren().get(1);
        if (negated) {
            EGraphNode leftAndNotRight = syntheticNode(node, Opcode.AND, -1);
            leftAndNotRight.addNormalizedChild(cloneEGraph(left));
            leftAndNotRight.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(right), -2));

            EGraphNode rightAndNotLeft = syntheticNode(node, Opcode.AND, -3);
            rightAndNotLeft.addNormalizedChild(cloneEGraph(right));
            rightAndNotLeft.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(left), -4));

            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -5);
            disjunction.addNormalizedChild(leftAndNotRight);
            disjunction.addNormalizedChild(rightAndNotLeft);
            return disjunction;
        }

        EGraphNode leftImpliesRight = syntheticNode(node, Opcode.IMPLIES, -1);
        leftImpliesRight.addNormalizedChild(cloneEGraph(left));
        leftImpliesRight.addNormalizedChild(cloneEGraph(right));

        EGraphNode rightImpliesLeft = syntheticNode(node, Opcode.IMPLIES, -2);
        rightImpliesLeft.addNormalizedChild(cloneEGraph(right));
        rightImpliesLeft.addNormalizedChild(cloneEGraph(left));

        EGraphNode conjunction = syntheticNode(node, Opcode.AND, -3);
        conjunction.addNormalizedChild(leftImpliesRight);
        conjunction.addNormalizedChild(rightImpliesLeft);
        return conjunction;
    }

    @LeanVerifiedRewrite("R0-CORE-006")
    private static EGraphNode expandIte(EGraphNode node, boolean negated) {
        EGraphNode condition = node.getChildren().get(0);
        EGraphNode thenBranch = node.getChildren().get(1);
        EGraphNode elseBranch = node.getChildren().get(2);

        EGraphNode thenCase = syntheticNode(node, Opcode.AND, -1);
        thenCase.addNormalizedChild(cloneEGraph(condition));
        thenCase.addNormalizedChild(cloneEGraph(thenBranch));

        EGraphNode elseCase = syntheticNode(node, Opcode.AND, -2);
        elseCase.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(condition), -3));
        elseCase.addNormalizedChild(cloneEGraph(elseBranch));

        EGraphNode expanded = syntheticNode(node, Opcode.OR, -4);
        expanded.addNormalizedChild(thenCase);
        expanded.addNormalizedChild(elseCase);
        if (negated) {
            return syntheticUnary(node, Opcode.NOT, expanded, -5);
        }
        return expanded;
    }

    private static EGraphNode syntheticUnary(EGraphNode source, Opcode opcode, EGraphNode child, int offset) {
        EGraphNode node = syntheticNode(source, opcode, offset);
        node.addNormalizedChild(child);
        return node;
    }

    private static EGraphNode syntheticNode(EGraphNode source, Opcode opcode, int offset) {
        EGraphNode synthetic = EGraphNode.inOwningArena(
                source,
                syntheticId(source, offset),
                opcode,
                new ArrayList<>(),
                isCommutative(opcode),
                maxArity(opcode),
                isFlexibleArity(opcode),
                Metatype.BOOLEAN,
                source.getSemanticProfile());
        synthetic.setSourceType("Bool");
        synthetic.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        if (isBooleanBranchConnective(opcode) || opcode == Opcode.ITE) {
            synthetic.markDerivedBooleanRewriteAuthority();
        }
        return synthetic;
    }

    private static EGraphNode copyShallow(EGraphNode source, Opcode opcode) {
        boolean sameOpcode = source.getOpcode() == opcode;
        EGraphNode copy = EGraphNode.inOwningArena(
                source,
                source.getId(),
                opcode,
                new ArrayList<>(),
                sameOpcode ? source.isCommutative() : isCommutative(opcode),
                source.getMaxArity(),
                source.isFlexibleArity(),
                source.getMetatype(),
                source.getSemanticProfile());
        copy.setSourceName(sameOpcode ? source.getSourceName() : null);
        // A rewritten operator still derives from this parser occurrence; this
        // position is context, never a claim of a source-level repair patch.
        copy.setSourceOrigin(source.getSourceOrigin());
        copy.setSourceType(sameOpcode || !isBooleanBranchConnective(opcode)
                ? source.getSourceType() : "Bool");
        copy.setExactAlloyType(sameOpcode || !isBooleanBranchConnective(opcode)
                ? source.getExactAlloyType()
                : is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        copy.setAlphaName(sameOpcode ? source.getAlphaName() : null);
        if (sameOpcode) {
            copy.preserveSourceOccurrenceLineageFrom(source);
            copy.domainGuardOrigin = source.domainGuardOrigin;
            copy.domainContextOrigin = source.domainContextOrigin;
            copy.attachUnusedBindingReduction(source.unusedBindingReduction());
            copyCurrentDeclarationSlot(source,copy);
        }
        copy.setCallOccurrenceId(sameOpcode ? source.getCallOccurrenceId() : -1L);
        copy.setDeclaredArity(sameOpcode ? source.getDeclaredArity() : -1);
        copy.setCallArityAuthority(sameOpcode ? source.getCallArityAuthority() : null);
        if (sameOpcode) {
            copy.preserveTemporalReferenceAuthorityFrom(source);
            copy.preserveTemporalSnapshotBindingFrom(source);
        }
        if (sameOpcode) {
            copy.preserveDerivedBooleanRewriteAuthorityFrom(source);
        } else if (isBooleanBranchConnective(opcode) || opcode == Opcode.ITE) {
            copy.markDerivedBooleanRewriteAuthority();
        }
        if (sameOpcode) {
            // Install semantic identity authority last: ordinary metadata setters
            // intentionally revoke reserved built-in authority.
            copy.preserveSemanticIdentityFrom(source);
            copy.preserveParserSignatureEvidenceFrom(source);
        }
        return copy;
    }

    private static EGraphNode copyQuantifierShallow(EGraphNode source, Opcode opcode) {
        if (source.getOpcode() == opcode) {
            return copyShallow(source, opcode);
        }
        EGraphNode copy = EGraphNode.inOwningArena(
                source,
                source.getId(),
                opcode,
                new ArrayList<>(),
                false,
                source.getMaxArity(),
                source.isFlexibleArity(),
                source.getMetatype(),
                source.getSemanticProfile());
        copy.setSourceType(source.getSourceType());
        copy.setSourceOrigin(source.getSourceOrigin());
        copy.setExactAlloyType(source.getExactAlloyType());
        return copy;
    }

    private static boolean isCommutative(Opcode opcode) {
        return opcode == Opcode.AND || opcode == Opcode.OR || opcode == Opcode.IFF
                || opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS
                || opcode == Opcode.INTERSECT || opcode == Opcode.PLUS || opcode == Opcode.MUL
                || opcode == Opcode.IPLUS;
    }

    private static boolean isAssociative(Opcode opcode) {
        return AlloyOperatorPolicy.isFlatSetOperator(opcode);
    }

    private static boolean isBooleanBranchConnective(Opcode opcode) {
        return opcode == Opcode.NOT
                || opcode == Opcode.AND
                || opcode == Opcode.OR
                || opcode == Opcode.IMPLIES
                || opcode == Opcode.IFF;
    }

    private static int maxArity(Opcode opcode) {
        if (opcode == Opcode.CONSTANT) {
            return 0;
        }
        if (opcode == Opcode.NOT) {
            return 1;
        }
        return isFlexibleArity(opcode) ? -1 : 2;
    }

    private static boolean isFlexibleArity(Opcode opcode) {
        return isAssociative(opcode);
    }

    private static String sortKey(EGraphNode node) {
        StringBuilder sb = new StringBuilder();
        appendSortKey(node, sb);
        return sb.toString();
    }

    private static void appendSortKey(EGraphNode node, StringBuilder sb) {
        sb.append(node.getOpcode()).append('{')
                .append(node.getSemanticProfile().fingerprint()).append(';')
                .append(node.getExactAlloyType() == null
                        ? "" : node.getExactAlloyType().stableString()).append(';')
                .append(node.getArityPolicy()).append(';')
                .append(node.getSiblingQuotient()).append(';')
                .append(node.getFlatLicense()).append(';')
                .append(node.getUnitLicense()).append("}:");
        if (node.getOpcode() == Opcode.CALL) {
            sb.append(CallMetadata.semanticKey(node));
        } else if (node.getOpcode() == Opcode.VARIABLE
                && node.getAlphaName() != null) {
            sb.append(node.getAlphaName());
        } else if (node.getSemanticIdentity() != null) {
            sb.append(node.getSemanticIdentity());
        } else if (node.getAlphaName() != null) {
            sb.append(node.getAlphaName());
        } else if (node.getSourceName() != null) {
            sb.append(node.getSourceName());
        } else if (node.getChildren().isEmpty()) {
            sb.append(node.getSourceType() == null ? "" : node.getSourceType());
        }
        sb.append('[');
        for (EGraphNode child : node.getChildren()) {
            appendSortKey(child, sb);
            sb.append(',');
        }
        sb.append(']');
    }

    private static Opcode dualBooleanOpcode(Opcode opcode) {
        return opcode == Opcode.AND ? Opcode.OR : Opcode.AND;
    }

    @LeanVerifiedRewrite({"R0-CORE-009", "R0-CORE-010"})
    private static Opcode dualOpcode(Opcode opcode) {
        switch (opcode) {
            case EQUALS:
                return Opcode.NOT_EQUALS;
            case NOT_EQUALS:
                return Opcode.EQUALS;
            case GT:
                return Opcode.LTE;
            case GTE:
                return Opcode.LT;
            case IN:
                return Opcode.NOT_IN;
            case LT:
                return Opcode.GTE;
            case LTE:
                return Opcode.GT;
            case NOT_GT:
                return Opcode.GT;
            case NOT_GTE:
                return Opcode.GTE;
            case NOT_IN:
                return Opcode.IN;
            case NOT_LT:
                return Opcode.LT;
            case NOT_LTE:
                return Opcode.LTE;
            case SOME:
                return Opcode.NO;
            case NO:
                return Opcode.SOME;
            case ALWAYS:
                return Opcode.EVENTUALLY;
            case EVENTUALLY:
                return Opcode.ALWAYS;
            case HISTORICALLY:
                return Opcode.ONCE;
            case ONCE:
                return Opcode.HISTORICALLY;
            case UNTIL:
                return Opcode.RELEASES;
            case RELEASES:
                return Opcode.UNTIL;
            case SINCE:
                return Opcode.TRIGGERED;
            case TRIGGERED:
                return Opcode.SINCE;
            default:
                return null;
        }
    }

    private static boolean dualNegatesChildren(Opcode opcode) {
        return opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY
                || opcode == Opcode.HISTORICALLY || opcode == Opcode.ONCE
                || opcode == Opcode.UNTIL || opcode == Opcode.RELEASES
                || opcode == Opcode.SINCE || opcode == Opcode.TRIGGERED;
    }

    private static int syntheticId(EGraphNode source, int offset) {
        return -Math.abs(source.getId()) * 16 + offset;
    }

    private static EGraphNode cloneEGraph(EGraphNode node) {
        EGraphNode clone = EGraphNode.inOwningArena(
                node,
                node.getId(),
                node.getOpcode(),
                new ArrayList<>(),
                node.isCommutative(),
                node.getMaxArity(),
                node.isFlexibleArity(),
                node.getMetatype(),
                node.getSemanticProfile());
        clone.setSourceName(node.getSourceName());
        clone.setSourceOrigin(node.getSourceOrigin());
        clone.setSourceType(node.getSourceType());
        clone.setExactAlloyType(node.getExactAlloyType());
        clone.setAlphaName(node.getAlphaName());
        clone.preserveSourceOccurrenceLineageFrom(node);
        clone.domainGuardOrigin = node.domainGuardOrigin;
        clone.domainContextOrigin = node.domainContextOrigin;
        clone.attachUnusedBindingReduction(node.unusedBindingReduction());
        copyCurrentDeclarationSlot(node,clone);
        clone.setCallOccurrenceId(node.getCallOccurrenceId());
        clone.setDeclaredArity(node.getDeclaredArity());
        clone.setCallArityAuthority(node.getCallArityAuthority());
        clone.preserveTemporalReferenceAuthorityFrom(node);
        clone.preserveDerivedBooleanRewriteAuthorityFrom(node);
        for (EGraphNode child : node.getChildren()) {
            clone.addNormalizedChild(cloneEGraph(child));
        }
        // Install semantic identity authority after all public metadata and child
        // mutations, each of which deliberately revokes reserved authority.
        clone.preserveSemanticIdentityFrom(node);
        clone.preserveParserSignatureEvidenceFrom(node);
        return clone;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        if (type.startsWith("VAR_")) {
            return type.substring(4);
        }
        return type;
    }

    private static boolean isQuantifier(Opcode opcode) {
        return opcode == Opcode.FORALL || opcode == Opcode.EXISTS || opcode == Opcode.NO
                || opcode == Opcode.LONE || opcode == Opcode.ONE || opcode == Opcode.SUM
                || opcode == Opcode.COMPREHENSION;
    }

    private static boolean isQuantifierNode(EGraphNode node) {
        if (!isQuantifier(node.getOpcode())) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private static int countFormulaQuantifierNodes(EGraphNode node) {
        if (node == null) {
            return 0;
        }
        int count = isQuantifierNode(node)
                && node.getOpcode() != Opcode.COMPREHENSION
                && node.getOpcode() != Opcode.SUM ? 1 : 0;
        for (EGraphNode child : node.getChildren()) {
            count += countFormulaQuantifierNodes(child);
        }
        return count;
    }

    private static boolean consumesMatrixNegation(Opcode opcode) {
        return opcode == Opcode.NO || opcode == Opcode.ONE || opcode == Opcode.LONE;
    }

    private static boolean childNegated(Opcode opcode, int childIndex, boolean negated) {
        switch (opcode) {
            case IMPLIES:
                return childIndex == 0 ? !negated : negated;
            case ITE:
                // Boolean ITEs were expanded before prenexing. A remaining ITE
                // is relational, so its condition and value branches are all
                // expression-positive; formula polarity must not be injected.
                return false;
            default:
                return negated;
        }
    }

    private static String childBindingPath(String parentPath, Opcode opcode, int childIndex, boolean childNegated) {
        StringBuilder path = new StringBuilder(parentPath);
        path.append('/').append(opcode.name().toLowerCase()).append('[').append(childIndex).append(']');
        if (childNegated) {
            path.append("/not");
        }
        return path.toString();
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.VAR || opcode == Opcode.DISJVAR
                || opcode == Opcode.GENERICRELDECL;
    }

    private static boolean isDisj(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.DISJVAR;
    }

    @LeanVerifiedRewrite({"R0-CORE-011", "R0-CORE-012"})
    private static Quantifier quantifierOf(Opcode opcode, boolean negated) {
        switch (opcode) {
            case FORALL:
                return negated ? Quantifier.SOME : Quantifier.ALL;
            case EXISTS:
                return negated ? Quantifier.ALL : Quantifier.SOME;
            case NO:
                return negated ? Quantifier.SOME : Quantifier.ALL;
            case LONE:
                return negated ? Quantifier.NOTLONE : Quantifier.LONE;
            case ONE:
                return negated ? Quantifier.NOTONE : Quantifier.ONE;
            case SUM:
                return Quantifier.SUM;
            case COMPREHENSION:
                return Quantifier.COMPREHENSION;
            default:
                return Quantifier.SOME;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static final class RelDeclResult {
        private final List<QuantiVar> quantiVars;
        private final Boolean emptyDomainValue;

        private RelDeclResult(List<QuantiVar> quantiVars, Boolean emptyDomainValue) {
            this.quantiVars = quantiVars;
            this.emptyDomainValue = emptyDomainValue;
        }
    }

    private final class PrenexSlotAllocator {
        private final Map<BindingSignature, Integer> nextSlots;
        private final Map<BindingCoordinate, QuantiVar> representatives;
        private Quantifier reusableQuantifier;
        private Map<BindingSignature, Integer> fallbackSlots;
        private boolean reuseOpen;

        private PrenexSlotAllocator() {
            this(new HashMap<>(), new HashMap<>(),
                    null, new HashMap<>(), false);
        }

        private PrenexSlotAllocator(
                Map<BindingSignature, Integer> nextSlots,
                Map<BindingCoordinate, QuantiVar> representatives,
                Quantifier reusableQuantifier,
                Map<BindingSignature, Integer> fallbackSlots,
                boolean reuseOpen) {
            this.nextSlots = nextSlots;
            this.representatives = representatives;
            this.reusableQuantifier = reusableQuantifier;
            this.fallbackSlots = fallbackSlots;
            this.reuseOpen = reuseOpen;
        }

        private PrenexSlotAllocator copy() {
            return new PrenexSlotAllocator(
                    new HashMap<>(nextSlots),
                    representatives,
                    reusableQuantifier,
                    new HashMap<>(fallbackSlots),
                    reuseOpen);
        }

        private PrenexSlotAllocator domainView() {
            PrenexSlotAllocator domain = copy();
            domain.reusableQuantifier = null;
            domain.fallbackSlots = new HashMap<>();
            domain.reuseOpen = false;
            return domain;
        }

        private PrenexSlotAllocator branchFrom(
                PrenexSlotAllocator base,
                Quantifier reusable) {
            PrenexSlotAllocator branch = copy();
            branch.reusableQuantifier = reusable;
            branch.fallbackSlots = new HashMap<>();
            for (Map.Entry<BindingSignature, Integer> entry : nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    branch.fallbackSlots.put(entry.getKey(), entry.getValue());
                }
            }
            branch.reuseOpen = true;
            branch.nextSlots.entrySet().removeIf(
                    entry -> entry.getKey().quantifier == reusable);
            for (Map.Entry<BindingSignature, Integer> entry : base.nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    branch.nextSlots.put(entry.getKey(), entry.getValue());
                }
            }
            return branch;
        }

        private void enterQuantifier(Quantifier quantifier) {
            if (reuseOpen && reusableQuantifier != quantifier) {
                closeReuse();
            }
        }

        private void enterStructure(Quantifier compatibleQuantifier) {
            if (reuseOpen && reusableQuantifier != compatibleQuantifier) {
                closeReuse();
            }
        }

        private void closeReuse() {
            if (!reuseOpen) {
                return;
            }
            for (Map.Entry<BindingSignature, Integer> entry : fallbackSlots.entrySet()) {
                nextSlots.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            reuseOpen = false;
        }

        private void mergeBranch(
                PrenexSlotAllocator branch,
                Quantifier reusable) {
            for (Map.Entry<BindingSignature, Integer> entry : branch.nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    nextSlots.merge(entry.getKey(), entry.getValue(), Math::max);
                } else {
                    nextSlots.put(entry.getKey(), entry.getValue());
                }
            }
        }

        private QuantiVar bind(QuantiVar candidate) {
            BindingSignature signature = new BindingSignature(candidate);
            int ordinal = nextSlots.getOrDefault(signature, 0);
            nextSlots.put(signature, ordinal + 1);
            BindingCoordinate coordinate = new BindingCoordinate(signature, ordinal);
            QuantiVar representative = representatives.get(coordinate);
            if (representative != null) {
                representative.mergeSourceOrigin(candidate);
                representative.mergeExactAlloyType(candidate.getExactAlloyType());
                for (String name : candidate.getOriginalNames()) representative.addOriginalName(name);
                return representative;
            }
            representatives.put(coordinate, candidate);
            matrixQuantiVars.add(candidate);
            return candidate;
        }
    }

    private static is.fivefivefive.ACGN.alloy.ExactAlloyType bindingTypeEvidence(
            EGraphNode variable,
            EGraphNode domain) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType direct =
                variable.getExactAlloyType();
        if (usableBindingTypeEvidence(direct)) {
            return direct;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType declared = domain == null
                ? null : domain.getExactAlloyType();
        return usableBindingTypeEvidence(declared) ? declared : null;
    }

    private static boolean usableBindingTypeEvidence(
            is.fivefivefive.ACGN.alloy.ExactAlloyType evidence) {
        if (evidence == null) {
            return false;
        }
        if (evidence.kind()
                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.INT) {
            return true;
        }
        // The parser's unary empty type still authenticates the original slot.
        // Dropping it loses paid declaration links; it does not erase the domain.
        if (authenticatedEmptyBindingType(evidence)) {
            return true;
        }
        return evidence.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                && evidence.relationArity() >= 1
                && !evidence.alternatives().isEmpty();
    }

    private static boolean authenticatedEmptyBindingType(
            is.fivefivefive.ACGN.alloy.ExactAlloyType evidence) {
        return evidence != null
                && evidence.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.EMPTY_RELATION
                && evidence.relationArity() == 1
                && evidence.hasParserAuthenticatedAncestry();
    }

    private static void inheritBindingTypeEvidence(
            EGraphNode occurrence,
            QuantiVar binding) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType declared =
                binding.getExactAlloyType();
        if (declared == null) {
            return;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType current =
                occurrence.getExactAlloyType();
        if (current == null || current.sameOccurrenceEvidenceAs(declared)) {
            occurrence.setExactAlloyType(declared);
            return;
        }
        if (current.equals(declared)
                && !current.hasParserAuthenticatedAncestry()
                && declared.hasParserAuthenticatedAncestry()) {
            occurrence.setExactAlloyType(declared);
            return;
        }
        throw new IllegalStateException(
                "A bound variable occurrence has incompatible declaration type evidence");
    }

    private static final class BindingSignature {
        private final Quantifier quantifier;
        private final Cardinality cardinality;
        private final int disjointnessClass;
        private final String type;
        private final String carrierType;
        private final String dependentRelationType;

        private BindingSignature(QuantiVar variable) {
            this.quantifier = variable.getQuantifier();
            this.cardinality = variable.getCardinality();
            this.disjointnessClass = variable.getDisjointnessClass();
            this.type = normalizeType(variable.getTypeName());
            this.carrierType = normalizeType(variable.getCarrierTypeName());
            is.fivefivefive.ACGN.alloy.ExactAlloyType exact =
                    variable.getExactAlloyType();
            this.dependentRelationType = exact != null
                            && exact.kind()
                                    == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                            && exact.relationArity() > 1
                    ? exact.stableString() : "";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BindingSignature)) {
                return false;
            }
            BindingSignature signature = (BindingSignature) other;
            return quantifier == signature.quantifier
                    && cardinality == signature.cardinality
                    && disjointnessClass == signature.disjointnessClass
                    && type.equals(signature.type)
                    && carrierType.equals(signature.carrierType)
                    && dependentRelationType.equals(
                            signature.dependentRelationType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    quantifier, cardinality, disjointnessClass, type,
                    carrierType, dependentRelationType);
        }
    }

    private static final class BindingCoordinate {
        private final BindingSignature signature;
        private final int ordinal;

        private BindingCoordinate(BindingSignature signature, int ordinal) {
            this.signature = signature;
            this.ordinal = ordinal;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BindingCoordinate)) {
                return false;
            }
            BindingCoordinate coordinate = (BindingCoordinate) other;
            return ordinal == coordinate.ordinal
                    && signature.equals(coordinate.signature);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(signature, ordinal);
        }
    }

    private static final class DomainDescriptor {
        private final EGraphNode domain;
        private final Cardinality cardinality;

        private DomainDescriptor(EGraphNode domain, Cardinality cardinality) {
            this.domain = domain;
            this.cardinality = cardinality == null ? Cardinality.SET : cardinality;
        }
    }
}
