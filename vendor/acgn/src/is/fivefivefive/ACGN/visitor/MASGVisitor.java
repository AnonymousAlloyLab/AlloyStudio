package is.fivefivefive.ACGN.visitor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

import edu.mit.csail.sdg.ast.Sig.PrimSig;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprUnary;
import edu.mit.csail.sdg.ast.Type;
import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.etc.BiMap;
import is.fivefivefive.ACGN.structure.ScopeTreeNode;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import is.fivefivefive.ACGN.alloy.AAME;
import is.fivefivefive.ACGN.alloy.AssertSymbol;
import is.fivefivefive.ACGN.alloy.AlloyLibraryCallableLedger;
import is.fivefivefive.ACGN.alloy.ConstSymbol;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.EndSymbol;
import is.fivefivefive.ACGN.alloy.ExtFact;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.FieldConfiner;
import is.fivefivefive.ACGN.alloy.FieldRelation;
import is.fivefivefive.ACGN.alloy.LetSymbol;
import is.fivefivefive.ACGN.alloy.MiddleSymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.RefSymbol;
import is.fivefivefive.ACGN.alloy.SetSymbol;
import is.fivefivefive.ACGN.alloy.ShadowSymbol;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.alloy.DeclRootSymbol;
import is.fivefivefive.ACGN.alloy.DummySymbol;
import is.fivefivefive.ACGN.test.Playground;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.OpenDecl;
import parser.ast.nodes.SigDecl;
import parser.ast.nodes.Predicate;
import parser.ast.nodes.Check;
import parser.ast.nodes.Run;
import parser.ast.nodes.Assertion;
import parser.ast.nodes.Fact;
import parser.ast.nodes.Function;
import parser.ast.nodes.Body;
import parser.ast.nodes.ConstExpr;
import parser.ast.nodes.LetExpr;
import parser.ast.nodes.ITEFormula;
import parser.ast.nodes.ITEExpr;
import parser.ast.nodes.ITEExprOrFormula;
import parser.ast.nodes.QtFormula;
import parser.ast.nodes.RelDecl;
import parser.ast.nodes.QtExpr;
import parser.ast.nodes.QtExprOrFormula;
import parser.ast.nodes.CallFormula;
import parser.ast.nodes.CallExpr;
import parser.ast.nodes.Call;
import parser.ast.nodes.ListFormula;
import parser.ast.nodes.ListExpr;
import parser.ast.nodes.BinaryFormula;
import parser.ast.nodes.BinaryExpr;
import parser.ast.nodes.UnaryFormula;
import parser.ast.nodes.UnaryExpr;
import parser.ast.nodes.VarExpr;
import parser.ast.nodes.BinaryExpr.BinaryOp;
import parser.ast.nodes.FieldExpr;
import parser.ast.nodes.SigExpr;
import parser.ast.nodes.ExprOrFormula;
import parser.ast.nodes.VarDecl;
import parser.ast.nodes.ParamDecl;
import parser.ast.nodes.PredOrFun;
import parser.ast.nodes.FieldDecl;
import parser.ast.nodes.ModuleDecl;
import parser.ast.nodes.Node;
import parser.ast.visitor.GenericVisitor;
import parser.ast.visitor.PrettyStringVisitor;
import parser.etc.Pair;

// TODO : RESTRUCTURE: Get rid of all unnecessary new objects by looking for the nodes first. 
public class MASGVisitor implements GenericVisitor<AugmentedNode, ScopeTreeNode> {

    // forest: the ASG forest of the predicates within the model
    // TODO: A fix, such that the recursive returns of the nodes connects with each other. 
    private DoubleMap<Integer, Multigraph> forest;
    private AAME aame;
    // timeOfVisitMap is for concrete nodes only, tracking the nodes that we actually consider to visit. 
    // ATTENTION: this means the time of visit of the SOURCE node. Only nonleaf nodes need it. 
    private Map<AugmentedNode, Integer> timeOfVisitMap; // TODO: TRACK THIS. REFRACT IT INTO EACH SEPARATE MULTIGRAPH
    // ATTENTION: GlobalVariables is not the "global variables" within the model, but describing the global properties under each node.
    private GlobalVariables globalVariables;
    private int numPredicates, scopeNodeId;
    // store local symbols within the scope of each predicate.
    // TODO: We may need more branching for localSymbols. Consider a ``scope tree''. 
    // private DoubleMap<Multigraph, Set<Symbol>> localSymbols;
    private ScopeTreeNode rootScope;
    // Unique nodes with unique symbols to represent.
    private BiMap<Symbol, AugmentedNode> uniqueNode;
    private Map<Symbol, Set<Symbol>> coarseToFineBin; // for each coarse symbol except local variables or predicate roots, the set of symbols that can be used to expand it in the fine-grained generation.
    public static final Symbol END_SYMBOL = new EndSymbol();
    // AugmentedNode stores graph-specific links and occurrence types, so sentinels
    // must not be shared across visitors.
    private final AugmentedNode endNode = new AugmentedNode(-128, 0, END_SYMBOL);
    private final SigSymbol EMPTY_SET_SYMBOL = SigSymbol.builtinNone();
    private final AugmentedNode EMPTY_SET_NODE = new AugmentedNode(126, 0, EMPTY_SET_SYMBOL);
    private final SigSymbol UNIVERSAL_SET_SYMBOL = SigSymbol.builtinUniv();
    private final AugmentedNode UNIVERSAL_SET_NODE = new AugmentedNode(126, 1, UNIVERSAL_SET_SYMBOL);
    private final SigSymbol INTEGER_SET_SYMBOL = SigSymbol.builtinInt();
    private final AugmentedNode INTEGER_SET_NODE = new AugmentedNode(126, 2, INTEGER_SET_SYMBOL);
    private final SigSymbol SEQUENCE_INDEX_SET_SYMBOL =
            SigSymbol.builtinSequenceIndex();
    private final AugmentedNode SEQUENCE_INDEX_SET_NODE =
            new AugmentedNode(126, 3, SEQUENCE_INDEX_SET_SYMBOL);
    public static final Symbol SHADOW_SYMBOL = ShadowSymbol.SHADOW;
    private final AugmentedNode shadowNode = new AugmentedNode(-128, 1, SHADOW_SYMBOL);
    private Map<Integer, AugmentedNode> nodeDict;
    private final Map<String, Integer> forestIdsByCallable = new HashMap<>();
    private final Map<String, List<CallableDescriptor>> callableDescriptors =
            new HashMap<>();
    private final Map<CallableKey, Integer> forestIdsByDescriptor = new HashMap<>();
    private final Map<CallableKey, Symbol> callableTargets = new HashMap<>();
    private final Map<String, ImportedModuleDescriptor> importedModules = new HashMap<>();
    private String moduleIdentity = "this";
    private CompModule parserModule;
    private long nextCallOccurrenceId;
    private long callOccurrences;
    private long callsContainingCalls;
    private long capturedCallVisits;
    private long validatedCallVisits;
    private final Map<AugmentedNode, CallVisitCapture> callVisitCaptures =
            new java.util.IdentityHashMap<>();
    private Set<String> selectedCallables;
    private AugmentedNode overallRoot;
    private Map<String, SigSymbol> unfoundSigs;
    private final Map<String, SigSymbol> signatureSymbols = new HashMap<>();
    private final Map<FieldKey, FieldRelation> fieldSymbols = new HashMap<>();
    public static final boolean USE_SHADOW = false;
    public static final boolean TYPE_SPECIAL_SETS = false;

    public MASGVisitor() {
        forest = new DoubleMap<>();
        aame = new AAME();
        timeOfVisitMap = new HashMap<>();
        // forest.put(0, new Multigraph()); // zero-th tree in the forest is the main AST/G
        numPredicates = 0;
        scopeNodeId = 0;
        globalVariables = new GlobalVariables();
        // localSymbols = new DoubleMap<Multigraph, Set<Symbol>>();
        uniqueNode = new BiMap<>();
        uniqueNode.put(END_SYMBOL, endNode);
        uniqueNode.put(EMPTY_SET_SYMBOL, EMPTY_SET_NODE);
        uniqueNode.put(SHADOW_SYMBOL, shadowNode);
        uniqueNode.put(UNIVERSAL_SET_SYMBOL, UNIVERSAL_SET_NODE);
        uniqueNode.put(INTEGER_SET_SYMBOL, INTEGER_SET_NODE);
        uniqueNode.put(SEQUENCE_INDEX_SET_SYMBOL, SEQUENCE_INDEX_SET_NODE);
        nodeDict = new HashMap<>();
        nodeDict.put(0, endNode);
        nodeDict.put(1, EMPTY_SET_NODE);
        nodeDict.put(2, shadowNode);
        nodeDict.put(3, UNIVERSAL_SET_NODE);
        aame.addSymbol("NONE_SET", EMPTY_SET_SYMBOL);
        aame.addSymbol("none", EMPTY_SET_SYMBOL);
        aame.addSymbol("univ", UNIVERSAL_SET_SYMBOL);
        aame.addSymbol("Int", INTEGER_SET_SYMBOL);
        aame.addSymbol("seq/Int", SEQUENCE_INDEX_SET_SYMBOL);
        signatureSymbols.put("none", EMPTY_SET_SYMBOL);
        signatureSymbols.put("univ", UNIVERSAL_SET_SYMBOL);
        signatureSymbols.put("Int", INTEGER_SET_SYMBOL);
        signatureSymbols.put("seq/Int", SEQUENCE_INDEX_SET_SYMBOL);
        unfoundSigs = new HashMap<>();
        coarseToFineBin = new HashMap<>();
        for (DummySymbol ds : DummySymbol.ALL_DUMMIES) {
            if (ds != DummySymbol.DUMMY_LOCAL_VAR && ds != DummySymbol.DUMMY_PREDROOT) coarseToFineBin.put(ds, new HashSet<>());
        }
        endNode.setMaxDownlinks(0);
        EMPTY_SET_NODE.setMaxDownlinks(0);
        shadowNode.setMaxDownlinks(0);
        UNIVERSAL_SET_NODE.setMaxDownlinks(0);
        INTEGER_SET_NODE.setMaxDownlinks(0);
        SEQUENCE_INDEX_SET_NODE.setMaxDownlinks(0);
    }
    public MASGVisitor(GlobalVariables gv) {
        this();
        globalVariables = gv;
    }
    public MASGVisitor(CompModule parserModule) {
        this();
        this.parserModule = Objects.requireNonNull(parserModule, "parserModule");
    }
    public MASGVisitor(GlobalVariables gv, CompModule parserModule) {
        this(gv);
        this.parserModule = Objects.requireNonNull(parserModule, "parserModule");
    }
    public MASGVisitor(GlobalVariables gv, Set<String> selectedCallables) {
        this(gv);
        this.selectedCallables = selectedCallables == null
                ? null
                : Collections.unmodifiableSet(new HashSet<>(selectedCallables));
    }
    public MASGVisitor(
            GlobalVariables gv,
            Set<String> selectedCallables,
            CompModule parserModule) {
        this(gv, selectedCallables);
        this.parserModule = Objects.requireNonNull(parserModule, "parserModule");
    }
    public DoubleMap<Integer, Multigraph> getForest() {
        return forest;
    }
    public Integer getForestId(String callableName) {
        List<CallableDescriptor> candidates = callableDescriptors.get(callableName);
        if (candidates == null) {
            candidates = callableDescriptors.get(stripThisQualifier(callableName));
        }
        if (candidates != null) {
            List<CallableDescriptor> visited = candidates.stream()
                    .filter(candidate -> forestIdsByDescriptor.containsKey(candidate.key()))
                    .distinct()
                    .toList();
            if (visited.size() == 1) {
                return forestIdsByDescriptor.get(visited.get(0).key());
            }
            List<CallableDescriptor> zeroArity = visited.stream()
                    .filter(candidate -> candidate.arity == 0)
                    .toList();
            if (zeroArity.size() == 1) {
                return forestIdsByDescriptor.get(zeroArity.get(0).key());
            }
            return null;
        }
        return forestIdsByCallable.get(callableName);
    }

    public Integer getForestId(
            String callableName,
            CallSymbol.Kind kind,
            int arity) {
        CallableDescriptor descriptor = resolveDeclaredCallable(
                callableName, kind, arity);
        return forestIdsByDescriptor.get(descriptor.key());
    }
    public AAME getAAME() {
        return aame;
    }
    public int numPredicates() {
        return numPredicates;
    }
    public GlobalVariables getGlobalVariables() {
        return globalVariables;
    }
    public AugmentedNode getOverallRoot() {
        return overallRoot;
    }
    public BiMap<Symbol, AugmentedNode> getUniqueNode() {
        return uniqueNode;
    }
    public Set<Symbol> getFineSymbolsForCoarseSymbol(Symbol coarse) {
        return coarseToFineBin.getOrDefault(coarse, new HashSet<>());
    }
    public CallExtractionStats callExtractionStats() {
        return new CallExtractionStats(
                callOccurrences, callsContainingCalls,
                capturedCallVisits, validatedCallVisits);
    }
    // visits, all non-predicates are discarded. 
    // consider AAME into it. 
    
    // syn == 0, sem == 0 reserved for dummy roots of each non-global ASG. 
    // ModelUnit; the concrete "root". Syntactic == 0 for Non-modificable. Semantic == 1
    /*
     * An explanation of the Syntactic and Semantic identifiers: 
     * Syn := the identifiers of "what it does" in a concrete tree; Interchangable
     * Sem := what exactly it is. leaf symbols starts at 1. 
     * Special case: all non-changing nodes got assigned with zero syntactic. 
     * Dictionary of Syntactics: 
     *   0 = non-changing nodes, such as "ModuleDecl", "Open", irrelevant to modeling;
     *   1 = block starters of a FUNCTION (returns a set or element of a set);
     *   -1 = block starters of a PREDICATE (returns a boolean);
     *   21 = ASSERTIONS
     *   2 = dummy node for ITE / => ELSE EXPRS (set);
     *   -2 = dummy node for ITE / => ELSE FORMULAE (BOOLEAN) 
     *   3 = dummy node for the root of a quantifier EXPR; 
     *   -3 = dummy node for the root of a quantifier FORMULA;
     *   4 = starter of a ListExpr;
     *   -4 = starter of a ListFormula;
     *   5 = starter of a BinaryExpr;
     *   -5 = Binary Formula;
     *   6 = Unary Expr;
     *   -6 = Unary Formula;
     *   7 = CallExpr;
     *   -7 = CallFormula;
     *   15 = BinaryExpr for type checking only;
     *   16 = UnaryExpr for type checking only;
     *   -127 = dummy node for the list of declarations;
     *   -128 = the End Symbol (predefined);
     *   121 - 'iden' constant
     *   122 - Let Expressions; 
     *   123 - Integer Constants;
     *   124 - Boolean Constants;
     *   125 - FieldSymbols;
     *   126 - SigSymbols;
     *   127 - VarSymbols;
     */
    @Override
    public AugmentedNode visit(ModelUnit n, ScopeTreeNode arg) {
        if (parserModule == null) {
            throw new IllegalStateException(
                    "MASGVisitor requires the CompModule that produced its ModelUnit");
        }
        callOccurrences = 0;
        callsContainingCalls = 0;
        capturedCallVisits = 0;
        validatedCallVisits = 0;
        callVisitCaptures.clear();
        nextCallOccurrenceId = 0;
        indexReachableGlobalRelations();
        indexCallableDeclarations(n);
        AugmentedNode mu = new AugmentedNode(0, 1);
        overallRoot = mu;
        Multigraph demoGraph = new Multigraph(mu, globalVariables);
        forest.put(0, demoGraph);
        rootScope = new ScopeTreeNode(0, null, demoGraph);
        rootScope.addSymbol(EMPTY_SET_SYMBOL);
        rootScope.addSymbol(UNIVERSAL_SET_SYMBOL);
        demoGraph.addVertex(mu);
        AugmentedNode md = n.getModuleDecl().accept(this, rootScope);
        demoGraph.addVertex(md);
        demoGraph.connect(mu, md, demoGraph, 1, 1);
        // Open: non-modificable, syn == 0, sem == 3;
        for (OpenDecl o : n.getOpenDeclList()) {
            AugmentedNode oNode = o.accept(this, rootScope);
            demoGraph.addVertex(oNode);
            demoGraph.connect(mu, oNode, demoGraph, 2, 1);
            
        }
        int predId = 0;
        // SigDecl: non-mod, syn == 0, sem == 4; defines a new symbol in scope.
        for (SigDecl sd : n.getSigDeclList()) {
            AugmentedNode sdNode = sd.accept(this, rootScope);
            demoGraph.addVertex(sdNode);
            demoGraph.connect(mu, sdNode, demoGraph, 3 + predId, 1);
            predId++;
        }

        // Predicate : each creates a tree in the forest. syn = 1, sem == 0; define a new predicate, which is a subtree. 
        // the foci of learning
        
        for (Predicate p : n.getPredDeclList()) {
            if (!shouldVisitCallable(p.getName())) {
                continue;
            }
            AugmentedNode pNode = p.accept(this, rootScope);
            demoGraph.addVertex(pNode);
            demoGraph.connect(mu, pNode, demoGraph, 3 + predId, 1);
            predId++;
        }

        // Function : a callable symbol, unchanged in operation
        for (Function f : n.getFunDeclList()) {
            if (!shouldVisitCallable(f.getName())) {
                continue;
            }
            AugmentedNode fNode = f.accept(this, rootScope);
            demoGraph.addVertex(fNode);
            demoGraph.connect(mu, fNode, demoGraph, 3 + predId, 1);
            predId++;
        }
        // Facts can be directly stored in AAME. 
        if (selectedCallables == null) {
            for (Fact f : n.getFactDeclList()) {
                AugmentedNode fNode = f.accept(this, rootScope);
                demoGraph.addVertex(fNode);
                demoGraph.connect(mu, fNode, demoGraph, 3 + predId, 1);
                predId++;
            }
            // Assertion: a non-modifiable node, syn == 0, sem == 21
            for (Assertion a : n.getAssertDeclList()) {
                AugmentedNode aNode = a.accept(this, rootScope);
                demoGraph.addVertex(aNode);
                demoGraph.connect(mu, aNode, demoGraph, 3 + predId, 1);
                predId++;
            }
            // Check: a non-modifiable node, syn == 0, sem == 101
            for (Check c : n.getCheckCmdList()) {
                AugmentedNode cNode = c.accept(this, rootScope);
                demoGraph.addVertex(cNode);
                demoGraph.connect(mu, cNode, demoGraph, 3 + predId, 1);
                predId++;
            }
            // Run: a non-modifiable node, syn == 0, sem == 102
            for (Run r : n.getRunCmdList()) {
                AugmentedNode rNode = r.accept(this, rootScope);
                demoGraph.addVertex(rNode);
                demoGraph.connect(mu, rNode, demoGraph, 3 + predId, 1);
                predId++;
            }
        }
        if (!unfoundSigs.isEmpty()) {
            throw new RuntimeException("Unfound sigs: " + unfoundSigs);
        }
        globalVariables.addUniqueNodes(uniqueNode);
        return mu;
    }

    private boolean shouldVisitCallable(String name) {
        return selectedCallables == null || selectedCallables.contains(name);
    }

    @Override
    public AugmentedNode visit(ModuleDecl n, ScopeTreeNode arg) {
        return new AugmentedNode(0, 2);
    }

    @Override
    public AugmentedNode visit(OpenDecl n, ScopeTreeNode arg) {
        return new AugmentedNode(0, 3);
    }

    @Override
    public AugmentedNode visit(SigDecl n, ScopeTreeNode arg) {
        String nameKey = n.getName();
        SigSymbol sigsy = signatureSymbols.get(nameKey);
        if (sigsy == null) {
            sigsy = new SigSymbol(nameKey);
        }
        coarseToFineBin.get(DummySymbol.DUMMY_SIG).add(sigsy);
        if (unfoundSigs.containsKey(nameKey)) {
            sigsy = unfoundSigs.get(nameKey);
            unfoundSigs.remove(nameKey);
        }
        signatureSymbols.put(nameKey, sigsy);
        aame.addSymbol(nameKey, sigsy);
        rootScope.addSymbol(sigsy);
        AugmentedNode sigExprNode = uniqueNode.get(sigsy);
        if (sigExprNode == null) {
            sigExprNode = new AugmentedNode(126, uniqueNode.size(), sigsy);
            uniqueNode.put(sigsy, sigExprNode);
        }
        sigExprNode.setMaxDownlinks(n.getFieldList().size() + 1);
        updateTimeOfVisit(sigExprNode, arg);
        int iter = 1;
        for (FieldDecl f : n.getFieldList()) {
            AugmentedNode field = f.accept(this, arg);
            visitAndConnect(sigExprNode, field, iter, arg);
            iter++;
        }
        visitAndConnect(sigExprNode, endNode, iter, arg);
        return sigExprNode;
    }

    /*
     * TODO: Remember that predicates and similar items are called in CallExprOrFormula. Update the timeOfVisit there. 
     */
    @Override
    public AugmentedNode visit(Predicate n, ScopeTreeNode arg) {
        return visitPredOrFun(n, arg);
    }

    @Override
    public AugmentedNode visit(Function n, ScopeTreeNode arg) {
        return visitPredOrFun(n, arg);
    }

    private AugmentedNode visitPredOrFun(PredOrFun n, ScopeTreeNode arg) {
        if (arg == null) {
            rootScope = new ScopeTreeNode(scopeNodeId, null, null);
            scopeNodeId++;
        }
        numPredicates++; 
        scopeNodeId++;
        // Once declared, the PredNode when called is just a near-leaf (d=1) node symbol. 
        int syn = n instanceof Function ? 1 : -1;
        AugmentedNode predNode = new AugmentedNode(syn, numPredicates);
        
        String predName = n.getName();
        int declaredArity = declaredArity(n);
        CallSymbol.Kind declaredKind = n instanceof Function
                ? CallSymbol.Kind.EXPRESSION : CallSymbol.Kind.FORMULA;
        CallableDescriptor callable = resolveDeclaredCallable(
                predName, declaredKind, declaredArity);
        forestIdsByCallable.putIfAbsent(predName, numPredicates);
        forestIdsByDescriptor.put(callable.key(), numPredicates);
        Symbol predSymbol = new PredRootSymbol(
                predNode,
                callable.sourceName,
                callable.identity,
                callable.kind,
                callable.arity,
                callable.arityAuthority);
        predNode.setSymbol(predSymbol);
        uniqueNode.put(predSymbol, predNode);
        callableTargets.put(callable.key(), predSymbol);
        // System.out.println("Visiting predicate: " + predName + " with symbol: " + predSymbol + " into uniqueNode. ");
        aame.addSymbol(predName, predSymbol);
        Multigraph predGraph = new Multigraph(predNode, globalVariables);
        forest.put(numPredicates, predGraph);
        // localSymbols.put(predGraph, new HashSet<Symbol>());
        ScopeTreeNode subscope = new ScopeTreeNode(scopeNodeId, rootScope, predGraph);
        // System.out.println("Scope affliation root: " + subscope.getAffliation().getRoot());
        updateTimeOfVisit(predNode, subscope);
        int predTimeOfVisit = predGraph.getTimeOfVisitMap().getOrDefault(predNode, 1);
        predNode.initLocalTovAsRoot(predGraph);
        int iter = 2;
        for (ParamDecl pd : n.getParamList()) {
            // From here, we need to pass the subgraph into the child nodes.
            // TODO: Incorporate the timeOfVisitMap to ensure unique visit time.
            AugmentedNode pdNode = pd.accept(this, subscope);
            globalVariables.addEdge(predNode, pdNode, 2); // equivalent in infinity;
            visitAndConnectAt(predNode, pdNode, iter, subscope, predTimeOfVisit);
            // predGraph.connect(predNode, pdNode, iter, 1);
            iter++;
        }
        globalVariables.addEdge(predNode, endNode, iter);
        visitAndConnectAt(predNode, endNode, iter, subscope, predTimeOfVisit);
        AugmentedNode bodyNode = n.getBody().accept(this, subscope);
        globalVariables.addEdge(predNode, bodyNode, 1);
        visitAndConnectAt(predNode, bodyNode, 1, subscope, predTimeOfVisit);
        // predGraph.addVertex(bodyNode);
        // predGraph.connect(predNode, bodyNode, 1, 1);
        return predNode;
    }

    // Assume that a RelDecl declares a set of relations all subject to the same type scope. 
    // RelDecls here except Fields.
    // TODO: How to capture the types of the RelDecls???
    private AugmentedNode visitRelDecl(RelDecl n, ScopeTreeNode arg) {
        // All real decls goes to this. Concrete symbol to consider. 
        // Set<Symbol> localSyms = arg.getSymbols();
        int isVar = n.isVariable() ? 1 : 0;
        int isDisj = n.isDisjoint() ? 1 : 0;
        // syntactic: according to the signature type and the confiners. 
        int semantic = isVar * 2 + isDisj; // class of the decl; confined by property of the decl. 
        Symbol declRootSym = new DeclRootSymbol(semantic);
        AugmentedNode declRoot; // a virtual root node of the decl set. 
        if (!uniqueNode.containsKey(declRootSym)) {
            uniqueNode.put(declRootSym, new AugmentedNode(-127, semantic, declRootSym));
            declRoot = uniqueNode.get(declRootSym);
        } else {
            declRoot = uniqueNode.get(declRootSym);
            if (declRoot.getSyntactic() != -127 || declRoot.getSemantic() != semantic) {
                throw new RuntimeException("Inconsistent syntactic/semantic for " + declRootSym + ": " + declRoot);
            }
        }
        declRoot = uniqueNode.get(declRootSym) == null ? 
            declRoot : uniqueNode.get(declRootSym);
        updateTimeOfVisit(declRoot, arg);
        Multigraph graph = arg.getAffliation();
        int declarationTimeOfVisit = graph.getTimeOfVisitMap()
                .getOrDefault(declRoot, 1);
        graph.addVertex(declRoot);
        ExprOrFormula expr = n.getExpr(); // the type with constraints. 
        AugmentedNode exprNode;
        if (TYPE_SPECIAL_SETS) {
            exprNode = visitTypeExpr(expr, arg, 1);
        } else {
            exprNode = expr.accept(this, arg);
        }
        // globalVariables.addEdge(declRoot, exprNode, 1);
        visitAndConnectAt(
                declRoot, exprNode, 1, arg, declarationTimeOfVisit);
        // Pair<SigSymbol, Set<FieldConfiner>> sigPair = getSigSymbolByExpr(expr);
        // SigSymbol sigSymbol = sigPair.a;
        // TODO: not actually sigs; need to capture complex type expressions. 
        SigSymbol sigSymbol = typeCheckExpr(expr);
        if (sigSymbol == null) {
            sigSymbol = UNIVERSAL_SET_SYMBOL;
        }
        Integer graphId = forest.rget(graph);
        int variableScope = graphId == null ? -Math.max(1, arg.getId()) : graphId;
        for (String name : n.getNames()) {
            String lexicalIdentity = "var/scope:" + arg.getId()
                    + "/slot:" + arg.allocateBindingSlot();
            VarSymbol varSym = new VarSymbol(
                    sigSymbol.getName(), name, lexicalIdentity, variableScope, exprNode);
            if (arg.getParent() == null) {
                // it is rootscope
                coarseToFineBin.get(DummySymbol.DUMMY_GLOBAL_VAR).add(varSym);
            }
            arg.addSymbol(varSym);
            AugmentedNode varNode = new AugmentedNode(127, uniqueNode.size(), varSym);
            uniqueNode.put(varSym, varNode);
            varNode.setMaxDownlinks(0);
        }
        int iter = 2;
        for (ExprOrFormula v : n.getVariables()) {
            AugmentedNode varNode = v.accept(this, arg);
            if (Playground.DEBUG) {
                PrettyStringVisitor psv = new PrettyStringVisitor();
                String varStr = psv.visit(v, null);
                psv = new PrettyStringVisitor();
                if (n instanceof ParamDecl) {
                    ParamDecl pd = (ParamDecl) n;
                    psv = new PrettyStringVisitor();
                    String paramStr = psv.visit(pd, null);
                    // System.out.println("Visiting variable " + varStr + " in parameter declaration " + paramStr);
                } else if (n instanceof VarDecl) {
                    VarDecl vd = (VarDecl) n;
                    psv = new PrettyStringVisitor();
                    String varDeclStr = psv.visit(vd, null);
                    // System.out.println("Visiting variable " + varStr + " in variable declaration " + varDeclStr);
                } else {
                    // System.out.println("Visiting variable " + varStr + " in unknown declaration " + n.getClass().getSimpleName());
                }
            }
            // globalVariables.addEdge(declRoot, varNode, iter);
            visitAndConnectAt(
                    declRoot, varNode, iter, arg, declarationTimeOfVisit);
            iter++;
        }
        // graph.addVertex(endNode);
        // globalVariables.addEdge(declRoot, endNode, iter);
        visitAndConnectAt(
                declRoot, endNode, iter, arg, declarationTimeOfVisit);
        return declRoot;
    }
    // TODO: Down here we need more implementation of the gv augmentations.

    private void visitAndConnect(AugmentedNode parent, AugmentedNode child, int position, ScopeTreeNode arg) {
        int timeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(parent, 1);
        visitAndConnectAt(parent, child, position, arg, timeOfVisit);
    }

    private void visitAndConnectAt(AugmentedNode parent, AugmentedNode child, int position, ScopeTreeNode arg, int timeOfVisit) {
        if (child == null) {
            return;
        }
        Multigraph graph = arg.getAffliation();
        if (Playground.DEBUG) {
            System.out.println("Visiting " + parent.getSymbol().getName() + " for " + child.getSymbol().getName() + " at time of visit " + timeOfVisit);
        }
        graph.addVertex(parent);
        boolean flagEq = USE_SHADOW && parent.equals(child) && parent.getSyntactic() != 15 && parent.getSyntactic() != 16;
        if (flagEq) {
            // create a shadow node
            if (Playground.DEBUG) {
                System.out.println("Shadow node created for " + parent.getSymbol().getName() + " at time of visit " + timeOfVisit);
            }
            graph.addVertex(shadowNode);
            updateTimeOfVisit(shadowNode, arg);
            int shadowTimeOfVisit = graph.getTimeOfVisitMap()
                    .getOrDefault(shadowNode, 1);
            ExactAlloyType parentType = parent.getExactType(graph, timeOfVisit);
            if (parentType == null) {
                throw new IllegalStateException(
                        "A shadow occurrence cannot lose its source exact type");
            }
            shadowNode.setExactType(graph, shadowTimeOfVisit, parentType);
            graph.connect(parent, shadowNode, graph, position, timeOfVisit);
            globalVariables.addEdge(parent, shadowNode, position);
        } else {
            graph.addVertex(child);
            graph.connect(parent, child, graph, position, timeOfVisit);
            globalVariables.addEdge(parent, child, position);
        }
        if (Playground.DEBUG) {
            System.out.println(parent.getDownlinksAtTimeOfVisit(graph, timeOfVisit));
            System.out.println(parent.getDownlinks());
        }
    }

    // TODO: We have not touched internal nodes yet. ALSO UPDATE THE INTERNAL TIME OF VISIT
    private void updateTimeOfVisit(AugmentedNode parent, ScopeTreeNode arg) {

        if (!timeOfVisitMap.containsKey(parent)) {
            timeOfVisitMap.put(parent, 1);
        } else {
            int timeOfVisit = timeOfVisitMap.get(parent);
            timeOfVisitMap.put(parent, timeOfVisit + 1);
        }
        Multigraph graph = arg.getAffliation();
        Map<AugmentedNode, Integer> localTovMap = graph.getTimeOfVisitMap();
        if (!localTovMap.containsKey(parent)) {
            localTovMap.put(parent, 1);
            if (Playground.DEBUG) {
                System.out.println("Time of visit for " + parent.getSymbol().getName() + " set to 1");
            }
        } else {
            int localTov = localTovMap.get(parent);
            localTovMap.put(parent, localTov + 1);
            if (Playground.DEBUG) {
                System.out.println("Updated time of visit for " + parent.getSymbol().getName() + " to " + (localTov + 1));
            }
        }
    }

    private void recordExactType(
            AugmentedNode node,
            ExprOrFormula source,
            ScopeTreeNode scope,
            int timeOfVisit) {
        if (node == null || source == null || scope == null
                || scope.getAffliation() == null) {
            throw new IllegalArgumentException(
                    "Exact Alloy type provenance requires one complete source occurrence");
        }
        node.setExactType(
                scope.getAffliation(), timeOfVisit,
                ExactAlloyType.fromParser(source.getType(), parserModule));
    }

    private void recordLeafExactType(
            AugmentedNode node,
            ExprOrFormula source,
            ScopeTreeNode scope) {
        if (node == null) {
            throw new IllegalStateException("A source leaf has no MASG node");
        }
        Multigraph graph = scope.getAffliation();
        graph.addVertex(node);
        recordExactType(
                node,
                source,
                scope,
                node.nextExactTypeVisit(graph));
    }
    private AugmentedNode visitTypeExpr(ExprOrFormula expr, ScopeTreeNode arg, int depth) {
        // TODO: Implement type expression visiting logic
        if (expr instanceof UnaryExpr) {
            UnaryExpr unExpr = (UnaryExpr) expr;
            if (unExpr.getOp() == UnaryExpr.UnaryOp.NOOP) {
                return visitTypeExpr(unExpr.getSub(), arg, depth); // noop is not depth-increasing, just bypass it.
            }
            int syntactic = 16;
            Pair<String, Integer> labelAndSemantics = getUnaryExprSymbolLabelAndSemantic(unExpr);
            String label = MiddleSymbol.TYPECONFINEROP_PREFIX + labelAndSemantics.a + "@" + depth;
            int semantic = labelAndSemantics.b;
            Symbol midSym = new MiddleSymbol(label);
            midSym.setMaxDownlinks(1);
            AugmentedNode midNode = new AugmentedNode(syntactic, semantic, midSym);
            uniqueNode.put(midSym, midNode);
            updateTimeOfVisit(midNode, arg);
            recordExactType(
                    midNode,
                    expr,
                    arg,
                    arg.getAffliation().getTimeOfVisitMap().getOrDefault(midNode, 1));
            ExprOrFormula childExpr = unExpr.getSub();
            AugmentedNode childNode = visitTypeExpr(childExpr, arg, depth + 1);
            visitAndConnect(midNode, childNode, 1, arg);
            return midNode;
        } else if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            int syntactic = 15;
            Pair<String, Integer> labelAndSemantics = getBinaryExprSymbolLabelAndSemantic(binExpr);
            String label = MiddleSymbol.TYPECONFINEROP_PREFIX + labelAndSemantics.a + "@" + depth;
            int semantic = labelAndSemantics.b;
            Symbol midSym = new MiddleSymbol(label);
            midSym.setMaxDownlinks(2);
            AugmentedNode midNode = new AugmentedNode(syntactic, semantic, midSym);
            uniqueNode.put(midSym, midNode);
            updateTimeOfVisit(midNode, arg);
            recordExactType(
                    midNode,
                    expr,
                    arg,
                    arg.getAffliation().getTimeOfVisitMap().getOrDefault(midNode, 1));
            ExprOrFormula leftExpr = binExpr.getLeft();
            ExprOrFormula rightExpr = binExpr.getRight();
            AugmentedNode leftNode = visitTypeExpr(leftExpr, arg, depth + 1);
            AugmentedNode rightNode = visitTypeExpr(rightExpr, arg, depth + 1);
            visitAndConnect(midNode, leftNode, 1, arg);
            visitAndConnect(midNode, rightNode, 2, arg);
            return midNode;
        }
        
        return expr.accept(this, arg);
    }


    private void downTimeOfVisit(AugmentedNode parent, ScopeTreeNode arg) {
        if (!timeOfVisitMap.containsKey(parent)) {
            throw new RuntimeException("Time of visit not supposed to downgrade here");
        } else {
            int timeOfVisit = timeOfVisitMap.get(parent);
            timeOfVisitMap.put(parent, timeOfVisit - 1);
        }
        Multigraph graph = arg.getAffliation();
        Map<AugmentedNode, Integer> localTovMap = graph.getTimeOfVisitMap();
        if (!localTovMap.containsKey(parent)) {
            throw new RuntimeException("Time of visit not supposed to downgrade here");
        } else {
            int localTov = localTovMap.get(parent);
            localTovMap.put(parent, localTov - 1);
            if (Playground.DEBUG) {
                System.out.println("Updated time of visit for " + parent.getSymbol().getName() + " to " + (localTov - 1));
            }
        }
    }

    private Pair<SigSymbol, Set<FieldConfiner>> getSigSymbolByExpr(ExprOrFormula n) {
        // Question: what is the ** signature ** type of the paramater? The expr of the ParamDecl is an arbitrary Expr. 
        try {
            Set<FieldConfiner> confiners = new HashSet<>();
            Node iter = n;
            while (iter instanceof UnaryExpr) {
                UnaryExpr unIter = (UnaryExpr) iter;
                switch (unIter.getOp()) {
                    case SET:
                        confiners.add(FieldConfiner.SET);
                        break;
                    case LONE:
                        confiners.add(FieldConfiner.LONE);
                        break;
                    case ONE:
                        confiners.add(FieldConfiner.ONE);
                        break;
                    case SOME:
                        confiners.add(FieldConfiner.SOME);
                        break;
                    case EXACTLYOF:
                        confiners.add(FieldConfiner.EXACTLY);
                        break;
                    case NOOP:
                        break;
                    default:
                        throw new Exception("Unknown unary operator: " + unIter.getOp());
                }
                iter = unIter.getChildren().get(0);
            }
            if (iter instanceof SigExpr) {
                SigExpr sigExpr = (SigExpr) iter;
                String sigName = sigExpr.getName();
                SigSymbol concSigSymbol = signatureSymbolOrPlaceholder(sigName);
                return Pair.of(concSigSymbol, confiners);
            } else {
                if (iter instanceof BinaryExpr) {
                    BinaryExpr binExpr = (BinaryExpr) iter;
                    if (binExpr.getOp() == BinaryOp.JOIN) {
                        Node fieldExprRaw = binExpr.getChildren().get(1);

                        if (fieldExprRaw instanceof UnaryExpr) {
                            if (((UnaryExpr)fieldExprRaw).getOp() == UnaryExpr.UnaryOp.NOOP) {
                                fieldExprRaw = ((UnaryExpr)fieldExprRaw).getChildren().get(0);
                            }
                        } 
                        if (!(fieldExprRaw instanceof FieldExpr)) {
                            fieldExprRaw = binExpr.getChildren().get(0);
                            if (((UnaryExpr)fieldExprRaw).getOp() == UnaryExpr.UnaryOp.NOOP) {
                                fieldExprRaw = ((UnaryExpr)fieldExprRaw).getChildren().get(0);
                            }
                        }
                        FieldExpr fieldExpr = (FieldExpr) fieldExprRaw;
                        FieldRelation fieldRel = fieldSymbol(fieldExpr);
                        SetSymbol iterSym = fieldRel;
                        while (iterSym instanceof FieldRelation) {
                            iterSym = ((FieldRelation) iterSym).getTarget();
                        }
                        SigSymbol sigSym = (SigSymbol) iterSym;
                        return Pair.of(sigSym, confiners);
                    } else {
                        // The parser type fixes the rightmost result column of
                        // products such as seq/Int -> A. Treating the left
                        // branch as a user signature leaves Alloy built-ins in
                        // the unresolved-signature ledger.
                        SigSymbol annotated = typeCheckAnnotatedExpr(binExpr);
                        if (annotated != null) {
                            return Pair.of(annotated, confiners);
                        }
                        return getSigSymbolByExpr(binExpr.getLeft());
                    }
                }
                throw new Exception("Unknown expression type: " + iter.getClass());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override 
    public AugmentedNode visit(ParamDecl n, ScopeTreeNode arg) {
        return visitRelDecl(n, arg);
    }

    @Override
    public AugmentedNode visit(Body n, ScopeTreeNode arg) {
        // not a concrete node, bypass
        Node bodyChild = n.getChildren().get(0);
        return bodyChild.accept(this, arg);
    }

    @Override
    public AugmentedNode visit(Check n, ScopeTreeNode arg) {
        return new AugmentedNode(0, 101);
    }

    @Override
    public AugmentedNode visit(Run n, ScopeTreeNode arg) {
        return new AugmentedNode(0, 102);
    }

    // Assertion is also a paragraph to be checked
    @Override
    public AugmentedNode visit(Assertion n, ScopeTreeNode arg) {
        String name = n.getName();
        AugmentedNode assertionRoot = new AugmentedNode(21, 1);
        assertionRoot.setMaxDownlinks(1);
        Multigraph subgraph = new Multigraph(assertionRoot, globalVariables);
        Symbol assertionSym = new AssertSymbol(name, subgraph);
        arg.addSymbol(assertionSym);
        uniqueNode.put(assertionSym, assertionRoot);
        assertionRoot.setSymbol(assertionSym);
        aame.addSymbol(name, assertionSym);
        scopeNodeId++;
        ScopeTreeNode subscope = new ScopeTreeNode(scopeNodeId, arg, subgraph);
        AugmentedNode body = n.getBody().accept(this, subscope);
        // globalVariables.addEdge(assertionRoot, body, 1);
        visitAndConnect(assertionRoot, body, 1, subscope);
        return assertionRoot;
    }
    
    // catch the explicit facts
    @Override
    public AugmentedNode visit(Fact n, ScopeTreeNode arg) {
        PrettyStringVisitor psv = new PrettyStringVisitor();
        String code = psv.visit(n, null);
        ExtFact fact = new ExtFact(true, code);
        aame.addFact(fact);
        return new AugmentedNode(0, 5);
    }

    @Override
    public AugmentedNode visit(ConstExpr n, ScopeTreeNode arg) {
        String val = n.getValue();
        if (n.isBoolean()) {
            if (val.equalsIgnoreCase("true")) {
                Symbol boolSymbol = new ConstSymbol("true", true, false);
                AugmentedNode node = new AugmentedNode(124, 1, boolSymbol);
                node.setMaxDownlinks(0);
                recordLeafExactType(node, n, arg);
                uniqueNode.put(boolSymbol, node);
                return node;
            } else {
                Symbol boolSymbol = new ConstSymbol("false", true, false);
                AugmentedNode node = new AugmentedNode(124, 0, boolSymbol);
                node.setMaxDownlinks(0);
                recordLeafExactType(node, n, arg);
                uniqueNode.put(boolSymbol, node);
                return node;
            }
        } else {
            if (val.equalsIgnoreCase("iden")) {
                Symbol constSymbol = ConstSymbol.builtinIden();
                AugmentedNode node = new AugmentedNode(121, 1, constSymbol);
                node.setMaxDownlinks(0);
                recordLeafExactType(node, n, arg);
                uniqueNode.put(constSymbol, node);
                return node;
            }
            try {
                int semantic = Integer.parseInt(n.getValue());
                Symbol constSymbol = new ConstSymbol(n.getValue(), false, false);
                AugmentedNode node = new AugmentedNode(123, semantic, constSymbol);
                node.setMaxDownlinks(0);
                recordLeafExactType(node, n, arg);
                uniqueNode.put(constSymbol, node);
                return node;
            } catch (Exception e) {
                System.out.println("Type of constant not supported! ");
                throw e;
            }
        }
    }

    // A new symbol, a new scope
    @Override
    public AugmentedNode visit(LetExpr n, ScopeTreeNode arg) {
        scopeNodeId++;
        ScopeTreeNode child = new ScopeTreeNode(scopeNodeId, arg);
        // Question: Is the 'var' here a VarExpr? Does it allow further cons? 
        ExprOrFormula var = n.getVar();
        if (var instanceof UnaryExpr) {
            if (((UnaryExpr) var).getOp() == UnaryExpr.UnaryOp.NOOP) {
                var = ((UnaryExpr) var).getSub();
            }
        }
        ExprOrFormula bound = n.getBound();
        Body body = n.getBody();
        arg.addChildren(child);
        if (var instanceof VarExpr) { 
            VarExpr varExpr = (VarExpr) var;
            AugmentedNode letNode = new AugmentedNode(122, uniqueNode.size());
            letNode.setMaxDownlinks(3);
            Symbol refSymbol = new LetSymbol(
                    letNode,
                    varExpr.getName(),
                    "let/scope:" + child.getId());
            coarseToFineBin.get(DummySymbol.DUMMY_LET).add(refSymbol);
            uniqueNode.put(refSymbol, letNode);
            child.addSymbol(refSymbol);
            letNode.setSymbol(refSymbol);
            updateTimeOfVisit(letNode, arg);
            int letTimeOfVisit = arg.getAffliation()
                    .getTimeOfVisitMap().getOrDefault(letNode, 1);
            recordExactType(letNode, n, arg, letTimeOfVisit);
            AugmentedNode boundNode = bound.accept(this, arg); 
            // globalVariables.addEdge(letNode, boundNode, 1);
            AugmentedNode bodyNode = body.accept(this, child);
            // globalVariables.addEdge(letNode, bodyNode, 2);
            visitAndConnect(letNode, boundNode, 1, arg);
            visitAndConnect(letNode, bodyNode, 2, arg);
            return letNode;
        } else {
            throw new RuntimeException("Implicit let not supported! ");
        }
    }

    private AugmentedNode visitITE(ITEExprOrFormula n, ScopeTreeNode arg) {
        int syntactic = n instanceof ITEExpr ? 2 : -2;
        String label = n instanceof ITEExpr ? "ITE_EXPR" : "ITE_FORMULA";
        MiddleSymbol ITESymbol = new MiddleSymbol(label);
        AugmentedNode ITEDummy;
        if (uniqueNode.containsKey(ITESymbol)) {
            ITEDummy = uniqueNode.get(ITESymbol);
        } else {
            ITEDummy = new AugmentedNode(syntactic, 1, ITESymbol);
            uniqueNode.put(ITESymbol, ITEDummy);
        }
        ITEDummy.setMaxDownlinks(3);
        updateTimeOfVisit(ITEDummy, arg);
        int iteTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(ITEDummy, 1);
        recordExactType(ITEDummy, n, arg, iteTimeOfVisit);
        ExprOrFormula condition = n.getCondition();
        ExprOrFormula thenClause = n.getThenClause();
        ExprOrFormula elseClause = n.getElseClause();
        AugmentedNode condNode = condition.accept(this, arg);
        // globalVariables.addEdge(ITEDummy, condNode, 1);
        AugmentedNode thenNode = thenClause.accept(this, arg);
        // globalVariables.addEdge(ITEDummy, thenNode, 2);
        AugmentedNode elseNode = elseClause.accept(this, arg);
        // globalVariables.addEdge(ITEDummy, elseNode, 3);
        visitAndConnectAt(ITEDummy, condNode, 1, arg, iteTimeOfVisit);
        visitAndConnectAt(ITEDummy, thenNode, 2, arg, iteTimeOfVisit);
        visitAndConnectAt(ITEDummy, elseNode, 3, arg, iteTimeOfVisit);
        return ITEDummy;
    }

    @Override
    public AugmentedNode visit(ITEFormula n, ScopeTreeNode arg) {
        return visitITE(n, arg);
    }

    @Override
    public AugmentedNode visit(ITEExpr n, ScopeTreeNode arg) {
        return visitITE(n, arg);
    }

    // A list of Var Decls with confiners, usually the actual root under a predicate
    private AugmentedNode visitQt(QtExprOrFormula n, ScopeTreeNode arg) {
        int syntactic = n instanceof QtExpr ? 3 : -3;
        String label = n instanceof QtExpr ? "QT_EXPR" : "QT_FORMULA";
        int semantic = -1;
        if (n instanceof QtFormula) {
            QtFormula.Quantifier quantifier = ((QtFormula) n).getOp();
            if (quantifier == QtFormula.Quantifier.ALL) {
                semantic = 1; // universal quantifier
                label += "_ALL1"; 
            } else if (quantifier == QtFormula.Quantifier.SOME) {
                semantic = 2; // existential quantifier
                label += "_SOME2"; 
            } else if (quantifier == QtFormula.Quantifier.NO) {
                semantic = 3; // negation of existential quantifier
                label += "_NO3";  
            } else if (quantifier == QtFormula.Quantifier.LONE) {
                semantic = 4; // lone quantifier
                label += "_LONE4"; 
            } else if (quantifier == QtFormula.Quantifier.ONE) {
                semantic = 5; // one quantifier
                label += "_ONE5"; 
            }
        } else {
            QtExpr.Quantifier quantifier = ((QtExpr) n).getOp();
            if (quantifier == QtExpr.Quantifier.SUM) {
                semantic = 1; // summation
                label += "_SUM1"; 
            } else if (quantifier == QtExpr.Quantifier.COMPREHENSION) {
                semantic = 2; // comprehension
                label += "_COMP2";
            }
        }
        MiddleSymbol qtSymbol = new MiddleSymbol(label, true, true);
        qtSymbol.setInfiniteRoot(true); // infinite root for quantifier
        List<VarDecl> varDecls = n.getVarDecls();
        scopeNodeId++;
        ScopeTreeNode subscope = new ScopeTreeNode(scopeNodeId, arg);
        Body body = n.getBody();
        Multigraph graph = arg.getAffliation();
        AugmentedNode qtRoot;
        if (uniqueNode.containsKey(qtSymbol)) {
            qtRoot = uniqueNode.get(qtSymbol);
        } else {
            qtRoot = new AugmentedNode(syntactic, semantic, qtSymbol);
            uniqueNode.put(qtSymbol, qtRoot);
        }
        graph.addVertex(qtRoot);
        updateTimeOfVisit(qtRoot, arg);
        int qtTimeOfVisit = graph.getTimeOfVisitMap().getOrDefault(qtRoot, 1);
        recordExactType(qtRoot, n, arg, qtTimeOfVisit);
        int iter = 2;
        for (VarDecl var : varDecls) {
            AugmentedNode varDeclNode = var.accept(this, subscope);
            // globalVariables.addEdge(qtRoot, varDeclNode, 2);
            visitAndConnectAt(qtRoot, varDeclNode, iter, subscope, qtTimeOfVisit);
            if (Playground.DEBUG) System.out.println("VarDecl at " + iter + ": " + var);
            iter++;
        }
        visitAndConnectAt(qtRoot, endNode, iter, subscope, qtTimeOfVisit);
        AugmentedNode bodyNode = body.accept(this, subscope);
        // globalVariables.addEdge(qtRoot, bodyNode, 1);
        visitAndConnectAt(qtRoot, bodyNode, 1, subscope, qtTimeOfVisit);
        return qtRoot;
    }

    @Override
    public AugmentedNode visit(QtFormula n, ScopeTreeNode arg) {
        return visitQt(n, arg);
    }

    @Override
    public AugmentedNode visit(QtExpr n, ScopeTreeNode arg) {
        return visitQt(n, arg);
    }

    // Calling a predicate or function
    @Override
    public AugmentedNode visit(CallFormula n, ScopeTreeNode arg) {
        return visitCall(n, arg, CallSymbol.Kind.FORMULA, -7);
    }

    @Override
    public AugmentedNode visit(CallExpr n, ScopeTreeNode arg) {
        return visitCall(n, arg, CallSymbol.Kind.EXPRESSION, 7);
    }

    private AugmentedNode visitCall(
            Call n,
            ScopeTreeNode arg,
            CallSymbol.Kind kind,
            int syntactic) {
        if (arg == null || arg.getAffliation() == null) {
            throw new IllegalStateException("A call must be visited inside a callable graph");
        }
        List<ExprOrFormula> arguments = n.getArguments();
        if (arguments == null) {
            throw new IllegalArgumentException("Call arguments must not be null: " + n.getName());
        }
        callOccurrences++;
        if (arguments.stream().anyMatch(MASGVisitor::containsCall)) {
            callsContainingCalls++;
        }
        CallableDescriptor descriptor = resolveCallable(n, kind, arguments.size());
        CallSymbol callSymbol = new CallSymbol(
                kind,
                descriptor.sourceName,
                descriptor.identity,
                descriptor.arity,
                nextCallOccurrenceId++,
                descriptor.arityAuthority);
        CallVisitCapture capture = new CallVisitCapture(
                syntactic, uniqueNode.size(), callSymbol);
        AugmentedNode callNode = capture.node();
        if (callVisitCaptures.put(callNode, capture) != null) {
            throw new IllegalStateException("A CALL node was captured more than once");
        }
        capturedCallVisits++;
        callNode.setMaxDownlinks(callSymbol.getMaxDownlinks());
        uniqueNode.put(callSymbol, callNode);
        if (arguments.size() != callSymbol.getDeclaredArity()) {
            throw new IllegalStateException(
                    "Call arity disagrees with its declaration: " + callSymbol
                            + " has " + arguments.size() + " arguments");
        }
        Multigraph localGraph = arg.getAffliation();
        localGraph.addVertex(callNode);
        updateTimeOfVisit(callNode, arg);
        int callTov = localGraph.getTimeOfVisitMap().getOrDefault(callNode, 1);
        recordExactType(callNode, n, arg, callTov);

        Symbol predOrFunSymbol = callableTargets.get(descriptor.key());
        if (predOrFunSymbol == null) {
            predOrFunSymbol = aame.getSymbol(n.getName());
            if (!(predOrFunSymbol instanceof is.fivefivefive.ACGN.alloy.CallableTargetSymbol)
                    || !((is.fivefivefive.ACGN.alloy.CallableTargetSymbol) predOrFunSymbol)
                            .matchesCall(callSymbol)) {
                predOrFunSymbol = null;
            }
        }
        AugmentedNode calledNode = uniqueNode.get(predOrFunSymbol);
        if (calledNode == null) {
            // Built-ins and forward references remain explicit callee leaves.
            calledNode = new AugmentedNode(
                    125,
                    uniqueNode.size(),
                    new RefSymbol(
                            null,
                            descriptor.sourceName,
                            descriptor.identity,
                            descriptor.kind,
                            descriptor.arity,
                            descriptor.arityAuthority));
            ((RefSymbol) calledNode.getSymbol()).setNode(calledNode);
            coarseToFineBin.get(DummySymbol.DUMMY_REF).add((RefSymbol) calledNode.getSymbol());
            uniqueNode.put(calledNode.getSymbol(), calledNode);
        }
        visitAndConnectAt(callNode, calledNode, 1, arg, callTov);
        int iter = 2;
        for (ExprOrFormula param : arguments) {
            AugmentedNode paramAug = param.accept(this, arg);
            if (paramAug == null) {
                throw new IllegalStateException(
                        "Call argument " + (iter - 2) + " did not produce an MASG node for "
                                + n.getName());
            }
            visitAndConnectAt(callNode, paramAug, iter, arg, callTov);
            iter++;
        }
        visitAndConnectAt(callNode, endNode, iter, arg, callTov);
        if (!capture.matches(callNode, callSymbol)
                || callVisitCaptures.get(callNode) != capture) {
            throw new IllegalStateException(
                    "A CALL visit no longer matches its creation-time capture");
        }
        validateCompletedCallVisit(callNode, callSymbol, localGraph, callTov);
        validatedCallVisits++;
        return callNode;
    }

    private static boolean containsCall(Node root) {
        if (root == null) {
            return false;
        }
        java.util.ArrayDeque<Node> pending = new java.util.ArrayDeque<>();
        Set<Node> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof Call) {
                return true;
            }
            pending.addAll(node.getChildren());
        }
        return false;
    }

    public static final class CallExtractionStats {
        private final long occurrences;
        private final long containingCalls;
        private final long capturedVisits;
        private final long validatedVisits;

        private CallExtractionStats(
                long occurrences,
                long containingCalls,
                long capturedVisits,
                long validatedVisits) {
            this.occurrences = occurrences;
            this.containingCalls = containingCalls;
            this.capturedVisits = capturedVisits;
            this.validatedVisits = validatedVisits;
        }

        public long occurrences() {
            return occurrences;
        }

        public long containingCalls() {
            return containingCalls;
        }

        public long capturedVisits() {
            return capturedVisits;
        }

        public long validatedVisits() {
            return validatedVisits;
        }
    }

    /** A CALL node and its parser-owned identity captured in one construction step. */
    private static final class CallVisitCapture {
        private final AugmentedNode node;
        private final CallSymbol symbol;
        private final long occurrenceId;

        private CallVisitCapture(
                int syntactic,
                int semantic,
                CallSymbol symbol) {
            this.symbol = java.util.Objects.requireNonNull(symbol, "CALL symbol");
            this.occurrenceId = symbol.getOccurrenceId();
            this.node = new AugmentedNode(syntactic, semantic, symbol);
        }

        private AugmentedNode node() {
            return node;
        }

        private boolean matches(AugmentedNode candidate, CallSymbol candidateSymbol) {
            return candidate == node
                    && candidateSymbol == symbol
                    && candidate.getSymbol() == symbol
                    && candidateSymbol.getOccurrenceId() == occurrenceId;
        }
    }

    private static void validateCompletedCallVisit(
            AugmentedNode callNode,
            CallSymbol callSymbol,
            Multigraph graph,
            int callTov) {
        List<MASGEdge> edges = callNode.getDownlinksAtTimeOfVisit(graph, callTov);
        int expected = callSymbol.getDeclaredArity() + 2;
        if (edges == null || edges.size() != expected) {
            throw new IllegalStateException(
                    "Incomplete call visit " + callSymbol + "@" + callTov
                            + ": expected " + expected + " downlinks, found "
                            + (edges == null ? 0 : edges.size()));
        }
        MASGEdge[] byPosition = new MASGEdge[expected + 1];
        for (MASGEdge edge : edges) {
            int position = edge.getPosition();
            if (edge.getTimeOfVisit() != callTov
                    || edge.getSource() != callNode
                    || position < 1
                    || position > expected
                    || byPosition[position] != null) {
                throw new IllegalStateException(
                        "Malformed call edge for " + callSymbol + "@" + callTov);
            }
            byPosition[position] = edge;
        }
        Symbol callee = byPosition[1].getTarget().getSymbol();
        if (!callSymbol.matchesTarget(callee)) {
            throw new IllegalStateException(
                    "Call callee mismatch for " + callSymbol + "@" + callTov);
        }
        for (int position = 2; position < expected; position++) {
            Symbol argument = byPosition[position].getTarget().getSymbol();
            if (argument != null && argument.isEndSymbol()) {
                throw new IllegalStateException(
                        "Call argument position contains a terminator for " + callSymbol);
            }
        }
        Symbol terminator = byPosition[expected].getTarget().getSymbol();
        if (terminator == null || !terminator.isEndSymbol()) {
            throw new IllegalStateException(
                    "Call visit lacks its final terminator for " + callSymbol + "@" + callTov);
        }
    }

    private void indexCallableDeclarations(ModelUnit model) {
        callableDescriptors.clear();
        forestIdsByDescriptor.clear();
        callableTargets.clear();
        importedModules.clear();
        String declaredModule = model.getModuleDecl() == null
                ? null
                : model.getModuleDecl().getModelName();
        moduleIdentity = declaredModule == null
                || declaredModule.trim().isEmpty()
                || "unknown".equalsIgnoreCase(declaredModule.trim())
                ? anonymousModuleIdentity(model)
                : declaredModule.trim();
        for (Predicate predicate : model.getPredDeclList()) {
            registerCallable(predicate, CallSymbol.Kind.FORMULA);
        }
        for (Function function : model.getFunDeclList()) {
            registerCallable(function, CallSymbol.Kind.EXPRESSION);
        }
        for (OpenDecl open : model.getOpenDeclList()) {
            String fileName = open.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                continue;
            }
            String declaredAlias = open.getAlias();
            boolean hasDeclaredAlias = declaredAlias != null
                    && !declaredAlias.trim().isEmpty();
            String alias = declaredAlias;
            if (!hasDeclaredAlias) {
                int slash = fileName.lastIndexOf('/');
                alias = slash < 0 ? fileName : fileName.substring(slash + 1);
            }
            ImportedModuleDescriptor module =
                    new ImportedModuleDescriptor(fileName, open.getArguments());
            registerImportedAlias(alias, module);
            if (!hasDeclaredAlias) {
                registerImportedAlias(fileName, module);
            }
        }
    }

    private void registerImportedAlias(
            String alias,
            ImportedModuleDescriptor descriptor) {
        ImportedModuleDescriptor previous = importedModules.putIfAbsent(alias, descriptor);
        if (previous != null && !previous.equals(descriptor)) {
            throw new IllegalStateException(
                    "Ambiguous imported module alias " + alias + ": "
                            + previous.identityPrefix() + " versus "
                            + descriptor.identityPrefix());
        }
    }

    private void registerCallable(PredOrFun callable, CallSymbol.Kind kind) {
        String sourceName = callable.getName();
        String localName = stripThisQualifier(sourceName);
        String identity = sourceName.contains("/")
                ? sourceName
                : moduleIdentity + "/" + localName;
        int arity = declaredArity(callable);
        CallableDescriptor descriptor = new CallableDescriptor(
                sourceName,
                identity,
                arity,
                kind,
                CallSymbol.ArityAuthority.DECLARATION);
        putCallableAlias(sourceName, descriptor);
        putCallableAlias(localName, descriptor);
        putCallableAlias("this/" + localName, descriptor);
        putCallableAlias(identity, descriptor);
    }

    private void putCallableAlias(String alias, CallableDescriptor descriptor) {
        List<CallableDescriptor> descriptors = callableDescriptors.computeIfAbsent(
                alias, ignored -> new java.util.ArrayList<>());
        if (descriptors.stream().anyMatch(existing -> existing != descriptor
                && existing.kind == descriptor.kind
                && existing.arity == descriptor.arity)) {
            throw new IllegalStateException("Ambiguous callable alias: " + alias);
        }
        if (!descriptors.contains(descriptor)) {
            descriptors.add(descriptor);
        }
    }

    private static String anonymousModuleIdentity(ModelUnit model) {
        String source = model.accept(new PrettyStringVisitor(), null);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return "anonymous/sha256-" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private CallableDescriptor resolveCallable(
            Call call,
            CallSymbol.Kind kind,
            int observedArity) {
        String sourceName = call.getName();
        CallableDescriptor descriptor = findDeclaredCallable(
                sourceName, kind, observedArity);
        if (descriptor == null) {
            List<CallableDescriptor> declared = callableDescriptors.get(sourceName);
            if (declared == null) {
                declared = callableDescriptors.get(stripThisQualifier(sourceName));
            }
            if (declared != null) {
                List<Integer> declaredArities = declared.stream()
                        .filter(candidate -> candidate.kind == kind)
                        .map(candidate -> candidate.arity)
                        .distinct()
                        .sorted()
                        .toList();
                if (!declaredArities.isEmpty()) {
                    throw new IllegalStateException(
                            "Call arity disagrees with declaration for " + sourceName
                                    + ": expected one of " + declaredArities
                                    + ", found " + observedArity);
                }
                throw new IllegalStateException(
                        "Call kind disagrees with declaration for " + sourceName);
            }
            ImportedCall importedCall = importedCallable(sourceName);
            if (importedCall == null) {
                throw new IllegalStateException(
                        "Unresolved call declaration: " + sourceName);
            }
            AlloyLibraryCallableLedger.Signature signature =
                    AlloyLibraryCallableLedger.require(
                            importedCall.module.fileName,
                            importedCall.member,
                            kind,
                            observedArity);
            return new CallableDescriptor(
                    sourceName,
                    importedCall.identity(),
                    signature.arity(),
                    kind,
                    CallSymbol.ArityAuthority.TYPECHECKED_IMPORT);
        }
        return descriptor;
    }

    private CallableDescriptor resolveDeclaredCallable(
            String sourceName,
            CallSymbol.Kind kind,
            int arity) {
        CallableDescriptor descriptor = findDeclaredCallable(sourceName, kind, arity);
        if (descriptor == null) {
            throw new IllegalStateException(
                    "Callable declaration was not indexed uniquely: " + sourceName
                            + "/" + kind + "/" + arity);
        }
        return descriptor;
    }

    private CallableDescriptor findDeclaredCallable(
            String sourceName,
            CallSymbol.Kind kind,
            int arity) {
        List<CallableDescriptor> candidates = callableDescriptors.get(sourceName);
        if (candidates == null) {
            candidates = callableDescriptors.get(stripThisQualifier(sourceName));
        }
        if (candidates == null) {
            return null;
        }
        List<CallableDescriptor> matches = candidates.stream()
                .filter(candidate -> candidate.kind == kind && candidate.arity == arity)
                .distinct()
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous callable declaration: " + sourceName
                            + "/" + kind + "/" + arity);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static int declaredArity(PredOrFun callable) {
        int arity = 0;
        for (ParamDecl declaration : callable.getParamList()) {
            arity += declaration.getNames().size();
        }
        return arity;
    }

    private ImportedCall importedCallable(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        String matchedPrefix = null;
        for (String prefix : importedModules.keySet()) {
            if (sourceName.startsWith(prefix + "/")
                    && (matchedPrefix == null || prefix.length() > matchedPrefix.length())) {
                matchedPrefix = prefix;
            }
        }
        if (matchedPrefix == null) {
            return null;
        }
        ImportedModuleDescriptor module = importedModules.get(matchedPrefix);
        String member = sourceName.substring(matchedPrefix.length() + 1);
        if (member.isEmpty() || member.indexOf('/') >= 0) {
            return null;
        }
        return new ImportedCall(module, member);
    }

    private static String stripThisQualifier(String name) {
        return name != null && name.startsWith("this/") ? name.substring(5) : name;
    }

    private static final class CallableDescriptor {
        private final String sourceName;
        private final String identity;
        private final int arity;
        private final CallSymbol.Kind kind;
        private final CallSymbol.ArityAuthority arityAuthority;

        private CallableDescriptor(
                String sourceName,
                String identity,
                int arity,
                CallSymbol.Kind kind,
                CallSymbol.ArityAuthority arityAuthority) {
            this.sourceName = sourceName;
            this.identity = identity;
            this.arity = arity;
            this.kind = kind;
            this.arityAuthority = arityAuthority;
        }

        private CallableKey key() {
            return new CallableKey(identity, kind, arity);
        }
    }

    private record CallableKey(
            String identity,
            CallSymbol.Kind kind,
            int arity) {
    }

    private static final class ImportedModuleDescriptor {
        private final String fileName;
        private final List<String> arguments;

        private ImportedModuleDescriptor(String fileName, List<String> arguments) {
            this.fileName = fileName;
            this.arguments = arguments == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
        }

        private String identityPrefix() {
            if (arguments.isEmpty()) {
                return fileName;
            }
            return fileName + "<" + String.join(",", arguments) + ">";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ImportedModuleDescriptor)) {
                return false;
            }
            ImportedModuleDescriptor descriptor = (ImportedModuleDescriptor) other;
            return fileName.equals(descriptor.fileName)
                    && arguments.equals(descriptor.arguments);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fileName, arguments);
        }
    }

    private static final class ImportedCall {
        private final ImportedModuleDescriptor module;
        private final String member;

        private ImportedCall(ImportedModuleDescriptor module, String member) {
            this.module = module;
            this.member = member;
        }

        private String identity() {
            return module.identityPrefix() + "/" + member;
        }
    }

    @Override
    public AugmentedNode visit(ListFormula n, ScopeTreeNode arg) {
        int semantics;
        switch (n.getOp()) {
            case AND: semantics = 1; break;
            case OR: semantics = 2; break;
            default: throw new RuntimeException("Custom labeled list not supported");
        }
        MiddleSymbol opSymbol = new MiddleSymbol("LIST_FORMULA_" + semantics);
        opSymbol.setInfiniteRoot(true); // infinite root for list formula
        AugmentedNode opNode;
        if (uniqueNode.containsKey(opSymbol)) {
            opNode = uniqueNode.get(opSymbol);
        } else {
            opNode = new AugmentedNode(-4, semantics, opSymbol);
            uniqueNode.put(opSymbol, opNode);
        }
        arg.getAffliation().addVertex(opNode);
        updateTimeOfVisit(opNode, arg);
        int listTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(opNode, 1);
        recordExactType(opNode, n, arg, listTimeOfVisit);
        int iter = 1;
        for (ExprOrFormula child : n.getArguments()) {
            AugmentedNode argChildNode = child.accept(this, arg);
            // globalVariables.addEdge(opNode, argChildNode, iter);
            visitAndConnectAt(opNode, argChildNode, iter, arg, listTimeOfVisit);
            iter++;
        }
        // globalVariables.addEdge(opNode, endNode, iter);
        visitAndConnectAt(opNode, endNode, iter, arg, listTimeOfVisit);
        return opNode;
    }

    @Override
    public AugmentedNode visit(ListExpr n, ScopeTreeNode arg) {
        int semantics;
        switch (n.getOp()) {
            case DISJOINT: semantics = 1; break;
            case TOTALORDER: semantics = 2; break;
            default: throw new RuntimeException("Custom labeled list not supported");
        }
        MiddleSymbol opSymbol = new MiddleSymbol("LIST_EXPR_" + semantics);
        opSymbol.setInfiniteRoot(true); // infinite root for list expression
        AugmentedNode opNode;
        if (uniqueNode.containsKey(opSymbol)) {
            opNode = uniqueNode.get(opSymbol);
        } else {
            opNode = new AugmentedNode(4, semantics, opSymbol);
            uniqueNode.put(opSymbol, opNode);
        }
        updateTimeOfVisit(opNode, arg);
        int listTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(opNode, 1);
        recordExactType(opNode, n, arg, listTimeOfVisit);
        int iter = 1;
        for (ExprOrFormula child : n.getArguments()) {
            AugmentedNode argChildNode = child.accept(this, arg);
            // globalVariables.addEdge(opNode, argChildNode, iter);
            visitAndConnectAt(opNode, argChildNode, iter, arg, listTimeOfVisit);
            iter++;
        }
        visitAndConnectAt(opNode, endNode, iter, arg, listTimeOfVisit);
        return opNode;
    }

    @Override
    public AugmentedNode visit(BinaryFormula n, ScopeTreeNode arg) {
        String symbolLabel = "BOP_";
        int semantic = 0;
        int syntactic = -5;
        switch (n.getOp()) {
            case EQUALS: 
                symbolLabel = "BOP_EQ"; 
                semantic = 1;
                break;
            case NOT_EQUALS: 
                symbolLabel = "BOP_NEQ"; 
                semantic = 2;
                break;
            case AND:
                symbolLabel = "BOP_AND";
                semantic = 3;
                break;
            case GT:
                symbolLabel = "BOP_GT";
                semantic = 4;
                break;
            case GTE:
                symbolLabel = "BOP_GTE";
                semantic = 5;
                break;
            case IFF:
                symbolLabel = "BOP_IFF";
                semantic = 6;
                break;
            case IMPLIES:
                symbolLabel = "BOP_IMPLIES";
                semantic = 7;
                break;
            case IN:
                symbolLabel = "BOP_IN";
                semantic = 8;
                break;
            case LT:
                symbolLabel = "BOP_LT";
                semantic = 9;
                break;
            case LTE:
                symbolLabel = "BOP_LTE";
                semantic = 10;
                break;
            case NOT_GT:
                symbolLabel = "BOP_NOT_GT";
                semantic = 11;
                break;
            case NOT_GTE:
                symbolLabel = "BOP_NOT_GTE";
                semantic = 12;
                break;
            case NOT_IN:
                symbolLabel = "BOP_NOT_IN";
                semantic = 13;
                break;
            case NOT_LT:
                symbolLabel = "BOP_NOT_LT";
                semantic = 14;
                break;
            case NOT_LTE:
                symbolLabel = "BOP_NOT_LTE";
                semantic = 15;
                break;
            case OR:
                symbolLabel = "BOP_OR";
                semantic = 16;
                break;
            case RELEASES:
                symbolLabel = "BOP_RELEASES";
                semantic = 17;
                break;
            case SINCE:
                symbolLabel = "BOP_SINCE";
                semantic = 18;
                break;
            case TRIGGERED:
                symbolLabel = "BOP_TRIGGERED";
                semantic = 19;
                break;
            case UNTIL:
                symbolLabel = "BOP_UNTIL";
                semantic = 20;
                break;
            default:
                break;
        }
        MiddleSymbol bopSymbol = new MiddleSymbol(symbolLabel);
        AugmentedNode bopNode;
        if (uniqueNode.containsKey(bopSymbol)) {
            bopNode = uniqueNode.get(bopSymbol);
        } else {
            bopNode = new AugmentedNode(syntactic, semantic, bopSymbol);
            uniqueNode.put(bopSymbol, bopNode);
        }
        bopNode.setMaxDownlinks(2);
        bopSymbol.setMaxDownlinks(2);
        arg.getAffliation().addVertex(bopNode);
        updateTimeOfVisit(bopNode, arg);
        int bopTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(bopNode, 1);
        recordExactType(bopNode, n, arg, bopTimeOfVisit);
        ExprOrFormula left = n.getLeft();
        ExprOrFormula right = n.getRight();
        AugmentedNode leftNode = left.accept(this, arg);
        AugmentedNode rightNode = right.accept(this, arg);
        connectBinaryOccurrence(
                bopNode, leftNode, rightNode, arg, bopTimeOfVisit);
        return bopNode;
    }

    @Override
    public AugmentedNode visit(BinaryExpr n, ScopeTreeNode arg) {
        String symbolLabel = "BOPEXPR_";
        int syntactic = 5;
        int semantic = 0;
        Pair<String, Integer> symbolInfo = getBinaryExprSymbolLabelAndSemantic(n);
        symbolLabel = symbolInfo.a;
        semantic = symbolInfo.b;
        MiddleSymbol bopSymbol = new MiddleSymbol(symbolLabel);
        AugmentedNode bopNode;
        if (uniqueNode.containsKey(bopSymbol)) {
            bopNode = uniqueNode.get(bopSymbol);
        } else {
            bopNode = new AugmentedNode(syntactic, semantic, bopSymbol);
            uniqueNode.put(bopSymbol, bopNode);
        }
        bopNode.setMaxDownlinks(2);
        bopSymbol.setMaxDownlinks(2);
        arg.getAffliation().addVertex(bopNode);
        updateTimeOfVisit(bopNode, arg);
        int bopTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(bopNode, 1);
        recordExactType(bopNode, n, arg, bopTimeOfVisit);
        ExprOrFormula left = n.getLeft();
        ExprOrFormula right = n.getRight();
        AugmentedNode leftNode = left.accept(this, arg);
        AugmentedNode rightNode = right.accept(this, arg);
        connectBinaryOccurrence(
                bopNode, leftNode, rightNode, arg, bopTimeOfVisit);
        return bopNode;
    }

    private void connectBinaryOccurrence(
            AugmentedNode parent,
            AugmentedNode left,
            AugmentedNode right,
            ScopeTreeNode scope,
            int parentVisit) {
        // The saved parent visit owns these edges; nested same-operator visits retain
        // their later visit numbers so each source occurrence remains addressable.
        visitAndConnectAt(parent, left, 1, scope, parentVisit);
        visitAndConnectAt(parent, right, 2, scope, parentVisit);
    }

    private Pair<String, Integer> getBinaryExprSymbolLabelAndSemantic(BinaryExpr n) {
        String symbolLabel = "BOPEXPR_";
        int semantic = 0;
        switch (n.getOp()) {
            case ARROW:
                symbolLabel = "BOPEXPR_ARROW";
                semantic = 1;
                break;
            case ANY_ARROW_SOME:
                symbolLabel = "BOPEXPR_ANY_ARROW_SOME";
                semantic = 2;
                break;
            case ANY_ARROW_ONE:
                symbolLabel = "BOPEXPR_ANY_ARROW_ONE";
                semantic = 3;
                break;
            case ANY_ARROW_LONE:
                symbolLabel = "BOPEXPR_ANY_ARROW_LONE";
                semantic = 4;
                break;
            case SOME_ARROW_ANY:
                symbolLabel = "BOPEXPR_SOME_ARROW_ANY";
                semantic = 5;
                break;
            case SOME_ARROW_SOME:
                symbolLabel = "BOPEXPR_SOME_ARROW_SOME";
                semantic = 6;
                break;
            case SOME_ARROW_ONE:
                symbolLabel = "BOPEXPR_SOME_ARROW_ONE";
                semantic = 7;
                break;
            case SOME_ARROW_LONE:
                symbolLabel = "BOPEXPR_SOME_ARROW_LONE";
                semantic = 8;
                break;
            case ONE_ARROW_ANY:
                symbolLabel = "BOPEXPR_ONE_ARROW_ANY";
                semantic = 9;
                break;
            case ONE_ARROW_SOME:
                symbolLabel = "BOPEXPR_ONE_ARROW_SOME";
                semantic = 10;
                break;
            case ONE_ARROW_ONE:
                symbolLabel = "BOPEXPR_ONE_ARROW_ONE";
                semantic = 11;
                break;
            case ONE_ARROW_LONE:
                symbolLabel = "BOPEXPR_ONE_ARROW_LONE";
                semantic = 12;
                break;
            case LONE_ARROW_ANY:
                symbolLabel = "BOPEXPR_LONE_ARROW_ANY";
                semantic = 13;
                break;
            case LONE_ARROW_SOME:
                symbolLabel = "BOPEXPR_LONE_ARROW_SOME";
                semantic = 14;
                break;
            case LONE_ARROW_ONE:
                symbolLabel = "BOPEXPR_LONE_ARROW_ONE";
                semantic = 15;
                break;
            case LONE_ARROW_LONE:
                symbolLabel = "BOPEXPR_LONE_ARROW_LONE";
                semantic = 16;
                break;
            case ISSEQ_ARROW_LONE:
                symbolLabel = "BOPEXPR_ISSEQ_ARROW_LONE";
                semantic = 17;
                break;
            case JOIN:
                symbolLabel = "BOPEXPR_JOIN";
                semantic = 18;
                break;
            case DOMAIN:
                symbolLabel = "BOPEXPR_DOMAIN";
                semantic = 19;
                break;
            case RANGE:
                symbolLabel = "BOPEXPR_RANGE";
                semantic = 20;
                break;
            case INTERSECT:
                symbolLabel = "BOPEXPR_INTERSECT";
                semantic = 21;
                break;
            case PLUSPLUS:
                symbolLabel = "BOPEXPR_PLUSPLUS";
                semantic = 22;
                break;
            case PLUS:
                symbolLabel = "BOPEXPR_PLUS";
                semantic = 23;
                break;
            case IPLUS:
                symbolLabel = "BOPEXPR_IPLUS";
                semantic = 24;
                break;
            case MINUS:
                symbolLabel = "BOPEXPR_MINUS";
                semantic = 25;
                break;
            case IMINUS:
                symbolLabel = "BOPEXPR_IMINUS";
                semantic = 26;
                break;
            case MUL:
                symbolLabel = "BOPEXPR_MUL";
                semantic = 27;
                break;
            case DIV:
                symbolLabel = "BOPEXPR_DIV";
                semantic = 28;
                break;
            case REM:
                symbolLabel = "BOPEXPR_REM";
                semantic = 29;
                break;
            case SHL:
                symbolLabel = "BOPEXPR_SHL";
                semantic = 30;
                break;
            case SHA:
                symbolLabel = "BOPEXPR_SHA";
                semantic = 31;
                break;
            case SHR:
                symbolLabel = "BOPEXPR_SHR";
                semantic = 32;
                break;
            default:
                break;
        }
        return Pair.of(symbolLabel, semantic);
    }


    @Override
    public AugmentedNode visit(UnaryFormula n, ScopeTreeNode arg) {
        String symbolLabel = "UNOPF_";
        int syntactic = -6;
        int semantic = 0;
        switch (n.getOp()) {
            case LONE:
                symbolLabel = "UNOPF_LONE";
                semantic = 1;
                break;
            case ONE:
                symbolLabel = "UNOPF_ONE";
                semantic = 2;
                break;
            case SOME:
                symbolLabel = "UNOPF_SOME";
                semantic = 3;
                break;
            case NO:
                symbolLabel = "UNOPF_NO";
                semantic = 4;
                break;
            case NOT:
                symbolLabel = "UNOPF_NOT";
                semantic = 5;
                break;
            case BEFORE:
                symbolLabel = "UNOPF_BEFORE";
                semantic = 6;
                break;
            case HISTORICALLY:
                symbolLabel = "UNOPF_HISTORICALLY";
                semantic = 7;
                break;
            case ONCE:
                symbolLabel = "UNOPF_ONCE";
                semantic = 8;
                break;
            case ALWAYS:
                symbolLabel = "UNOPF_ALWAYS";
                semantic = 9;
                break;
            case EVENTUALLY:
                symbolLabel = "UNOPF_EVENTUALLY";
                semantic = 10;
                break;
            case AFTER:
                symbolLabel = "UNOPF_AFTER";
                semantic = 11;
                break;
            default:
                break;
        }
        MiddleSymbol unopSymbol = new MiddleSymbol(symbolLabel);
        AugmentedNode unopNode;
        if (uniqueNode.containsKey(unopSymbol)) {
            unopNode = uniqueNode.get(unopSymbol);
        } else {
            unopNode = new AugmentedNode(syntactic, semantic, unopSymbol);
            uniqueNode.put(unopSymbol, unopNode);
        }
        unopNode.setMaxDownlinks(1);
        unopSymbol.setMaxDownlinks(1);
        arg.getAffliation().addVertex(unopNode);
        updateTimeOfVisit(unopNode, arg);
        int unopTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(unopNode, 1);
        recordExactType(unopNode, n, arg, unopTimeOfVisit);
        ExprOrFormula sub = n.getSub();
        AugmentedNode subNode = sub.accept(this, arg);
        if (subNode == unopNode) {
            // shadow node created, need to down the time of visit tracker
            downTimeOfVisit(unopNode, arg);
        }
        // globalVariables.addEdge(unopNode, subNode, 1);
        visitAndConnectAt(unopNode, subNode, 1, arg, unopTimeOfVisit);
        // update the shadow node time of visit back
        if (subNode == unopNode) {
            updateTimeOfVisit(unopNode, arg);
        }
        return unopNode;
    }

    @Override
    public AugmentedNode visit(UnaryExpr n, ScopeTreeNode arg) {
        String symbolLabel = "UNOPE_";
        int syntactic = 6;
        int semantic = 0;
        if (n.getOp() == UnaryExpr.UnaryOp.NOOP) {
            return n.getSub().accept(this, arg);
        }
        Pair<String, Integer> labelAndSemantic = getUnaryExprSymbolLabelAndSemantic(n);
        symbolLabel = labelAndSemantic.a;
        semantic = labelAndSemantic.b;
        MiddleSymbol unopSymbol = new MiddleSymbol(symbolLabel);
        AugmentedNode unopNode;
        if (uniqueNode.containsKey(unopSymbol)) {
            unopNode = uniqueNode.get(unopSymbol);
        } else {
            unopNode = new AugmentedNode(syntactic, semantic, unopSymbol);
            if (Playground.DEBUG) System.out.println("Creating new unary node: " + unopSymbol);
            uniqueNode.put(unopSymbol, unopNode);
        }
        unopNode.setMaxDownlinks(1);
        unopSymbol.setMaxDownlinks(1);
        arg.getAffliation().addVertex(unopNode);
        updateTimeOfVisit(unopNode, arg);
        int unopTimeOfVisit = arg.getAffliation().getTimeOfVisitMap().getOrDefault(unopNode, 1);
        recordExactType(unopNode, n, arg, unopTimeOfVisit);
        ExprOrFormula sub = n.getSub();
        AugmentedNode subNode = sub.accept(this, arg);
        if (subNode == unopNode) {
            // shadow node created, need to down the time of visit tracker
            downTimeOfVisit(unopNode, arg);
        }
        // globalVariables.addEdge(unopNode, subNode, 1);
        visitAndConnectAt(unopNode, subNode, 1, arg, unopTimeOfVisit);
        // update the shadow node time of visit back
        if (subNode == unopNode) {
            updateTimeOfVisit(unopNode, arg);
        }
        return unopNode;
    }

    private Pair<String, Integer> getUnaryExprSymbolLabelAndSemantic(UnaryExpr n) {
        String symbolLabel = "UNOPE_";
        int semantic = 0;
        switch (n.getOp()) {
            case SET:
                symbolLabel = "UNOPE_SET";
                semantic = 1;
                break;
            case LONE:
                symbolLabel = "UNOPE_LONE";
                semantic = 2;
                break;
            case ONE:
                symbolLabel = "UNOPE_ONE";
                semantic = 3;
                break;
            case SOME:
                symbolLabel = "UNOPE_SOME";
                semantic = 4;
                break;
            case EXACTLYOF:
                symbolLabel = "UNOPE_EXACTLYOF";
                semantic = 5;
                break;
            case TRANSPOSE:
                symbolLabel = "UNOPE_TRANSPOSE";
                semantic = 6;
                break;
            case RCLOSURE:
                symbolLabel = "UNOPE_RCLOSURE";
                semantic = 7;
                break;
            case CLOSURE:
                symbolLabel = "UNOPE_CLOSURE";
                semantic = 8;
                break;
            case CARDINALITY:
                symbolLabel = "UNOPE_CARDINALITY";
                semantic = 9;
                break;
            case CAST2INT:
                symbolLabel = "UNOPE_CAST2INT";
                semantic = 10;
                break;
            case CAST2SIGINT:
                symbolLabel = "UNOPE_CAST2SIGINT";
                semantic = 11;
                break;
            case PRIME:
                symbolLabel = "UNOPE_PRIME";
                semantic = 12;
                break;
            default:
                break;
        }
        return Pair.of(symbolLabel, semantic);
    }

    // TODO: Singular exprs starting here. 
    // Only invoked when the symbol was already declared and now used. 
    private SigSymbol signatureSymbolOrPlaceholder(String name) {
        SigSymbol existing = signatureSymbols.get(name);
        if (existing != null) {
            return existing;
        }
        SigSymbol placeholder = new SigSymbol(name);
        signatureSymbols.put(name, placeholder);
        unfoundSigs.put(name, placeholder);
        return placeholder;
    }

    private void indexReachableGlobalRelations() {
        for (Sig signature : parserModule.getAllReachableSigs()) {
            String name = new SigSymbol(signature.label).getName();
            SigSymbol symbol = signatureSymbols.get(name);
            if (symbol == null) {
                symbol = SigSymbol.fromParser(signature, parserModule);
                signatureSymbols.put(name, symbol);
            }
            if (!uniqueNode.containsKey(symbol)) {
                AugmentedNode node = new AugmentedNode(126, uniqueNode.size(), symbol);
                node.setMaxDownlinks(0);
                uniqueNode.put(symbol, node);
            }
            coarseToFineBin.get(DummySymbol.DUMMY_SIG).add(symbol);
        }
        for (Sig signature : parserModule.getAllReachableSigs()) {
            SigSymbol source = signatureSymbols.get(
                    new SigSymbol(signature.label).getName());
            for (Sig.Field field : signature.getFields()) {
                String fieldName = simpleFieldName(field.label);
                ExactAlloyType exactType = ExactAlloyType.fromParser(
                        field.type(), parserModule);
                FieldKey key = new FieldKey(fieldName, exactType);
                if (fieldSymbols.containsKey(key)) {
                    continue;
                }
                FieldRelation symbol = new FieldRelation(
                        fieldName,
                        source,
                        targetSignature(field.type()),
                        fieldConfiners(field));
                fieldSymbols.put(key, symbol);
                AugmentedNode node = new AugmentedNode(125, uniqueNode.size(), symbol);
                node.setMaxDownlinks(0);
                uniqueNode.put(symbol, node);
                coarseToFineBin.get(DummySymbol.DUMMY_FIELD).add(symbol);
            }
        }
    }

    private SigSymbol targetSignature(Type type) {
        PrimSig common = null;
        for (Type.ProductType product : type) {
            if (product.isEmpty()) {
                continue;
            }
            PrimSig candidate = product.get(product.arity() - 1);
            common = common == null ? candidate : common.leastParent(candidate);
        }
        if (common == null) {
            throw new IllegalStateException(
                    "A parser field has no concrete target signature");
        }
        return signatureSymbolOrPlaceholder(new SigSymbol(common.label).getName());
    }

    private static Set<FieldConfiner> fieldConfiners(Sig.Field field) {
        Set<FieldConfiner> result = new HashSet<>();
        Expr expression = field.decl().expr;
        while (expression instanceof ExprUnary) {
            ExprUnary unary = (ExprUnary) expression;
            if (unary.op == ExprUnary.Op.SETOF) {
                result.add(FieldConfiner.SET);
            } else if (unary.op == ExprUnary.Op.LONEOF) {
                result.add(FieldConfiner.LONE);
            } else if (unary.op == ExprUnary.Op.ONEOF) {
                result.add(FieldConfiner.ONE);
            } else if (unary.op == ExprUnary.Op.SOMEOF) {
                result.add(FieldConfiner.SOME);
            } else if (unary.op == ExprUnary.Op.EXACTLYOF) {
                result.add(FieldConfiner.EXACTLY);
            }
            expression = unary.sub;
        }
        return result;
    }

    private static String simpleFieldName(String label) {
        int separator = label.lastIndexOf('/');
        return separator < 0 ? label : label.substring(separator + 1);
    }

    private FieldRelation fieldSymbol(FieldExpr expression) {
        ExactAlloyType exactType = ExactAlloyType.fromParser(
                expression.getType(), parserModule);
        return fieldSymbols.get(new FieldKey(expression.getName(), exactType));
    }

    private ExactAlloyType declaredFieldType(String ownerName, String fieldName) {
        String normalizedOwner = new SigSymbol(ownerName).getName();
        ExactAlloyType result = null;
        for (Sig signature : parserModule.getAllReachableSigs()) {
            if (!normalizedOwner.equals(new SigSymbol(signature.label).getName())) {
                continue;
            }
            for (Sig.Field field : signature.getFields()) {
                String simpleName = simpleFieldName(field.label);
                if (!fieldName.equals(simpleName)) {
                    continue;
                }
                if (result != null) {
                    throw new IllegalStateException(
                            "One signature declares duplicate field identity: "
                                    + ownerName + "." + fieldName);
                }
                result = ExactAlloyType.fromParser(field.type(), parserModule);
            }
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Cannot recover parser-certified field type for "
                            + ownerName + "." + fieldName);
        }
        return result;
    }

    private AugmentedNode visitAbsorbing(
            ExprOrFormula n,
            ScopeTreeNode arg,
            String name) {
        AugmentedNode result;
        if (n instanceof VarExpr) {
            Symbol lexical = arg == null ? null : arg.getSymbol(name);
            result = lexical == null ? null : uniqueNode.get(lexical);
        } else if (n instanceof SigExpr) {
            result = uniqueNode.get(signatureSymbols.get(name));
        } else if (n instanceof FieldExpr) {
            result = uniqueNode.get(fieldSymbol((FieldExpr) n));
        } else {
            result = null;
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Cannot resolve source leaf '" + name + "' in its lexical scope");
        }
        recordLeafExactType(result, n, arg);
        return result;
    }

    @Override
    public AugmentedNode visit(VarExpr n, ScopeTreeNode arg) {
        String name = n.getName();
        return visitAbsorbing(n, arg, name);
    }

    // TODO: How about fields? 
    @Override
    public AugmentedNode visit(FieldExpr n, ScopeTreeNode arg) {
        String name = n.getName();
        return visitAbsorbing(n, arg, name);
    }

    @Override
    public AugmentedNode visit(SigExpr n, ScopeTreeNode arg) {
        String name = n.getName();
        return visitAbsorbing(n, arg, name);
    }

    @Override
    public AugmentedNode visit(ExprOrFormula n, ScopeTreeNode arg) {
        AugmentedNode undefinedExpr = new AugmentedNode(-128, 0);
        return undefinedExpr;
    }

    @Override
    public AugmentedNode visit(VarDecl n, ScopeTreeNode arg) {
        return visitRelDecl(n, arg);
    }

    @Override
    public AugmentedNode visit(FieldDecl n, ScopeTreeNode arg) {
        SigDecl sig = (SigDecl) n.getParent();
        String sigName = sig.getName();
        int isVar = n.isVariable() ? 1 : 0;
        int isDisj = n.isDisjoint() ? 1 : 0;
        int semantic = isVar * 2 + isDisj + 4;
        Symbol declRootSym = new MiddleSymbol("FIELD_DECL");
        AugmentedNode declRoot;
        if (uniqueNode.containsKey(declRootSym)) {
            declRoot = uniqueNode.get(declRootSym);
        } else {
            declRoot = new AugmentedNode(-127, semantic, declRootSym);
            uniqueNode.put(declRootSym, declRoot);
        }
        if (timeOfVisitMap.containsKey(declRoot)) {
            int prevValue = timeOfVisitMap.get(declRoot);
            timeOfVisitMap.put(declRoot, prevValue + 1);
        } else {
            timeOfVisitMap.put(declRoot, 1);
        }
        SigSymbol sourceSymbol = signatureSymbols.get(sigName);
        if (sourceSymbol == null) {
            throw new RuntimeException("No signature found in AAME for signature " + sigName + " of field " + n.getNames().toString());
        }
        ExprOrFormula fieldRelType = n.getExpr();
        Pair<SigSymbol, Set<FieldConfiner>> targetPair = getSigSymbolByExpr(fieldRelType);
        SigSymbol targetSymbol = targetPair.a;
        Set<FieldConfiner> confiners = targetPair.b;
        int iter = 2;
        for (String fieldName : n.getNames()) {
            FieldKey fieldKey = new FieldKey(
                    fieldName, declaredFieldType(sigName, fieldName));
            FieldRelation fieldSymbol = fieldSymbols.get(fieldKey);
            if (fieldSymbol == null) {
                fieldSymbol = new FieldRelation(
                        fieldName, sourceSymbol, targetSymbol, confiners);
                fieldSymbols.put(fieldKey, fieldSymbol);
            }
            coarseToFineBin.get(DummySymbol.DUMMY_FIELD).add(fieldSymbol);
            AugmentedNode fieldNode = uniqueNode.get(fieldSymbol);
            if (fieldNode == null) {
                fieldNode = new AugmentedNode(125, uniqueNode.size(), fieldSymbol);
                uniqueNode.put(fieldSymbol, fieldNode);
                fieldNode.setMaxDownlinks(0);
            }
            aame.addSymbol(fieldName, fieldSymbol);
            fieldNode.setSymbol(fieldSymbol);
            // TODO: Name problem? Consider same-name nodes...
            visitAndConnect(declRoot, fieldNode, iter, arg);
            iter++;
        }
        visitAndConnect(declRoot, endNode, iter, arg);
        return declRoot;
    }
    private SigSymbol typeCheckExpr(ExprOrFormula e) {
        // use this function to check the overall set / type of the expression
        // System.out.println(e);
        ExactAlloyType exactType = ExactAlloyType.fromParser(
                e.getType(), parserModule);
        if (exactType.kind() == ExactAlloyType.Kind.EMPTY_RELATION) {
            return EMPTY_SET_SYMBOL;
        }
        SigSymbol annotated = typeCheckAnnotatedExpr(e);
        if (annotated != null) {
            return annotated;
        }
        SigSymbol inferred = null;
        if (e instanceof SigExpr) {
            SigExpr sigExpr = (SigExpr) e;
            String sigName = sigExpr.getName();
            inferred = signatureSymbols.get(sigName);
        } else if (e instanceof FieldExpr) {
            FieldExpr fieldExpr = (FieldExpr) e;
            FieldRelation fieldRel = fieldSymbol(fieldExpr);
            if (fieldRel != null) {
                SetSymbol iterSym = fieldRel;
                while (iterSym instanceof FieldRelation) {
                    iterSym = ((FieldRelation) iterSym).getTarget();
                }
                if (iterSym instanceof SigSymbol) {
                    inferred = (SigSymbol) iterSym;
                }
            }
        } else if (e instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) e;
            ExprOrFormula sub = unaryExpr.getSub();
            inferred = typeCheckExpr(sub);
        } else if (e instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) e;
            if (binaryExpr.getOp() == BinaryOp.JOIN) {
                // see if its left or right is a field. Fields first. 
                ExprOrFormula left = binaryExpr.getLeft();
                ExprOrFormula right = binaryExpr.getRight();
                if (isSigOrField(right)) {
                    inferred = typeCheckExpr(right);
                } else {
                    inferred = typeCheckExpr(left);
                }
            } else {
                // select one branch and find its type
                inferred = typeCheckExpr(binaryExpr.getLeft());
            }
        } else if (e instanceof ITEExpr) {
            // use its THEN branch\
            inferred = typeCheckExpr(((ITEExpr) e).getThenClause());
        }
        return inferred;
    }

    private SigSymbol typeCheckAnnotatedExpr(ExprOrFormula expr) {
        Type type = expr.getType();
        if (type == null || !type.hasTuple()) {
            return null;
        }
        PrimSig commonType = null;
        for (Type.ProductType product : type) {
            if (product.isEmpty()) {
                continue;
            }
            PrimSig candidate = product.get(product.arity() - 1);
            commonType = commonType == null ? candidate : commonType.leastParent(candidate);
        }
        if (commonType == null) {
            return null;
        }
        String typeName = new SigSymbol(commonType.label).getName();
        return signatureSymbolOrPlaceholder(typeName);
    }
    private boolean isSigOrField(ExprOrFormula e) {
        if (e instanceof SigExpr) {
            return true;
        }
        if (e instanceof FieldExpr) {
            return true;
        }
        if (e instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) e;
            ExprOrFormula sub = unaryExpr.getSub();
            return isSigOrField(sub);
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) e;
            if (binaryExpr.getOp() == BinaryOp.JOIN) {
                // see if its left or right is a field. Fields first. 
                ExprOrFormula left = binaryExpr.getLeft();
                ExprOrFormula right = binaryExpr.getRight();
                return isSigOrField(right) || isSigOrField(left);
            } else {
                // select one branch and find its type
                return isSigOrField(binaryExpr.getLeft());
            }
        }
        return false;
    }

    public ScopeTreeNode getRootScope() {
        return rootScope;
    }

    private static final class FieldKey {
        private final String name;
        private final ExactAlloyType exactType;

        private FieldKey(String name, ExactAlloyType exactType) {
            this.name = Objects.requireNonNull(name, "field name");
            this.exactType = Objects.requireNonNull(exactType, "field exact type");
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FieldKey)) {
                return false;
            }
            FieldKey key = (FieldKey) other;
            return name.equals(key.name) && exactType.equals(key.exactType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, exactType);
        }
    }
}
