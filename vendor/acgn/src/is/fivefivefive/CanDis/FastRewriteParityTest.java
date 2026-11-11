package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Source-backed parity and non-equivalence checks; never rewrites archived results. */
public final class FastRewriteParityTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONArray rows = new JSONObject(Files.readString(Path.of(
                "docs/fast-rewrite-certificate-equality-audit.json"))).getJSONArray("rows");
        check(rows.length() == 14, "the historical parity cohort has 14 pairs");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Path path = Path.of("classified-data", row.getString("path"));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path))).equals(row.getString("sourceSha256")),
                    "unchanged source: " + path);
            CompModule module = AlloyUtil.compileAlloyModule(path.toString());
            String stem = path.getFileName().toString().replace(".als", "");
            String left = stem.substring(stem.lastIndexOf('_') + 1);
            ModelUnit model = new ModelUnit(null, module);
            String right = model.getPredDeclList().stream().map(Predicate::getName)
                    .filter(name -> name.equalsIgnoreCase(left + "c")).findFirst().orElseThrow();
            Canonical.Prepared[] pair = prepare(module, model, left, right);
            List<String> retainedLeft = Canonical.certificationIrTemporalFol(pair[0]);
            List<String> retainedRight = Canonical.certificationIrTemporalFol(pair[1]);
            check(retainedLeft.equals(row.getJSONArray("leftFastIr").toList())
                    && retainedRight.equals(row.getJSONArray("rightFastIr").toList()),
                    "original certification input text: " + path);
            check(Canonical.distance(pair[0], pair[1]) == 0, "Fast zero: " + path
                    + "\n" + Canonical.irTemporalFol(pair[0]) + "\n" + Canonical.irTemporalFol(pair[1]));
            check(Canonical.distance(pair[1], pair[0]) == 0, "symmetric zero: " + path);
            check(Canonical.canonicalFormSize(pair[0]) == Canonical.canonicalFormSize(pair[1]),
                    "same comparison size for correct-pool prefilter: " + path);
            check(Canonical.edits(pair[0], pair[1]).equals(List.of("no-op")), "zero edit path: " + path);
            var certifiedLeft = CanonicalAlloyPipeline.prepare(pair[0]);
            var certifiedRight = CanonicalAlloyPipeline.prepare(pair[1]);
            check(CanonicalAlloyPipeline.distance(certifiedLeft, certifiedRight) == 0,
                    "certified zero: " + path);
            check(certifiedLeft.canonicalObservation().digest().equals(row.getString("leftObservationDigest")),
                    "unchanged left certified observation: " + path);
            check(certifiedRight.canonicalObservation().digest().equals(row.getString("rightObservationDigest")),
                    "unchanged right certified observation: " + path);
            check(retainedLeft.equals(Canonical.certificationIrTemporalFol(pair[0]))
                    && retainedRight.equals(Canonical.certificationIrTemporalFol(pair[1])),
                    "retained certification sources: " + path);
            check(!counterexample(module), "Alloy bounded equivalence: " + path);
            System.out.println("Parity PASS " + path + " (" + row.getInt("fastDistance") + " -> 0)");
        }

        fixture("join-unary-barrier", "sig S { r, t: set S } one sig M in S {}",
                "some ((r.M).t)", "some (r.(M.t))", false);
        fixture("comprehension-grouping", "sig S { r: set S }",
                "some { x:S, y:S | y in x.r }", "some { a,b:S | b in a.r }", true);
        fixture("comprehension-column-order", "sig S { r: set S } one sig A,B in S {}",
                "A->B in { x,y:S | y in x.r }", "A->B in { x,y:S | x in y.r }", false);
        fixture("disjoint-groups", "sig S {}",
                "some { disj a,b:S, disj c,d:S | a=c and b=d }",
                "some { disj a,b,c,d:S | a=c and b=d }", false);
        fixture("different-domains", "sig S {} sig T in S {}",
                "some { a:S | a in S }", "some { a:T | a in S }", false);
        fixture("qualified-fields", "sig Project {} sig Person { projects: set Project } sig Course { projects: set Project }",
                "all p:Project | one (Person <: projects).p",
                "all p:Project | one (Course <: projects).p", false);
        fixture("temporal-endpoint", "sig S {} var sig T in S {}",
                "some disj a,b:S | a in T and eventually b in T",
                "some disj a,b:S | a in T and eventually a in T", false);
        fixture("nested-shadowing", "sig S { r: set S } one sig A,B in S {}",
                "A->B in { x,y:S | some x:S | x->y in r }",
                "A->B in { a,b:S | some c:S | c->b in r }", true);
        fixture("derived-membership-labels", "sig P {} sig A,B in P {}",
                "all p:P | p not in A or p not in B",
                "all p:P | (p in A implies p not in B) and (p in B implies p not in A)", true);
        fixture("local-all-grouping", "sig U { posts:set P } sig P {}",
                "(all x,y:U | all p:P | p in x.posts and p in y.posts implies x=y) and P in U.posts",
                "(all x:U,y:U | all p:P | p in x.posts and p in y.posts implies x=y) and P in U.posts", true);
        fixture("duplicate-slot-map-order", "sig W {} sig H,R in W {} sig S {workers:set W}",
                "all h:H,r:R,w:S | h in w.workers implies r not in w.workers",
                "all h:H,r:R,w:S | (h in w.workers implies r not in w.workers) and (r in w.workers implies h not in w.workers)", true);
        historicalNegativePairs();
        System.out.println("FastRewriteParityTest: " + checks + " checks passed");
    }

    private static void historicalNegativePairs() throws Exception {
        String[][] pairs = {
            {"coursesNew/both/xT7fmCyXRAv38zbG7_inv4.als", "coursesNew/correct/2ahzaJ9AgN9ukD4kt_inv4.als"},
            {"coursesOld/both/4eqsRijCmEQSkCdnn_inv5.als", "coursesOld/correct/5pcZ6C6yzCZ34zXZM_inv5.als"},
            {"coursesOld/both/4oGQ9xsb3Yt7nnNR6_inv4.als", "coursesOld/correct/3j9uZWDLpybLZAJLC_inv4.als"},
            {"coursesOld/both/BRxDwiXZRDSqWwaMH_inv5.als", "coursesOld/correct/5pcZ6C6yzCZ34zXZM_inv5.als"},
            {"coursesOld/both/DzLsxBgpZviTAEpEC_inv5.als", "coursesOld/correct/ofcoPS2MrsRG5js2r_inv5.als"},
            {"coursesOld/both/EtFytpSuzQ9wqXt9k_inv5.als", "coursesOld/correct/a2cEgE7g3SAFJhHML_inv5.als"},
            {"coursesOld/both/bHZCYFmX7iTCqHoKw_inv5.als", "coursesOld/correct/GDANXupGbwonf47iX_inv5.als"},
            {"coursesOld/both/fLctzhA9H7EWbYyzS_inv5.als", "coursesOld/correct/GDANXupGbwonf47iX_inv5.als"},
            {"coursesOld/both/tHdp2ZvFNwyuQjxRc_inv5.als", "coursesOld/correct/a2cEgE7g3SAFJhHML_inv5.als"},
            {"trash_ltl/both/7GaRScrfGLJtSRDo9_inv8.als", "trash_ltl/correct/89pzjDpv7Srmd4ntF_inv8.als"}
        };
        for (String[] paths : pairs) {
            Canonical.Prepared left = prepareSingle(paths[0]);
            Canonical.Prepared right = prepareSingle(paths[1]);
            check(Canonical.distance(left, right) > 0, "historical false-zero remains positive: " + paths[0]);
            check(CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(left),
                    CanonicalAlloyPipeline.prepare(right)) > 0, "certified negative: " + paths[0]);
        }
    }

    private static Canonical.Prepared prepareSingle(String relative) throws Exception {
        Path path = Path.of("classified-data", relative);
        String stem = path.getFileName().toString().replace(".als", "");
        String name = stem.substring(stem.lastIndexOf('_') + 1);
        CompModule module = AlloyUtil.compileAlloyModule(path.toString());
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }

    private static Canonical.Prepared[] prepare(CompModule module, ModelUnit model,
            String left, String right) {
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of(left, right), module);
        visitor.visit(model, null);
        return new Canonical.Prepared[] {
            Canonical.prepare(visitor.getForest().get(visitor.getForestId(left))),
            Canonical.prepare(visitor.getForest().get(visitor.getForestId(right)))
        };
    }

    private static void fixture(String name, String declarations, String left, String right,
            boolean equivalent) throws Exception {
        Path path = Files.createTempFile("fast-parity-" + name, ".als");
        try {
            Files.writeString(path, declarations + "\npred lhs { " + left + " }\npred rhs { " + right
                    + " }\nassert same { lhs iff rhs }\ncheck same for 4"
                    + (declarations.contains("var ") ? " but 3 steps" : "") + "\n");
            CompModule module = AlloyUtil.compileAlloyModule(path.toString());
            check(module != null, name + " parses");
            Canonical.Prepared[] pair = prepare(module, new ModelUnit(null, module), "lhs", "rhs");
            check((Canonical.distance(pair[0], pair[1]) == 0) == equivalent,
                    name + " Fast comparison\n" + Canonical.irTemporalFol(pair[0])
                            + "\n" + Canonical.irTemporalFol(pair[1]));
            check(counterexample(module) != equivalent, name + " Alloy semantic check");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static boolean counterexample(CompModule module) throws Exception {
        Command command = module.getAllCommands().stream().filter(c -> c.check).findFirst().orElseThrow();
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                module.getAllReachableSigs(), command, options).satisfiable();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
