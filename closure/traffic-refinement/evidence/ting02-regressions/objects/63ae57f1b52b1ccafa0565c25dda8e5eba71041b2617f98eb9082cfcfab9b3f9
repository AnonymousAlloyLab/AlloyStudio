import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.*;
import pt.haslab.alloyaddons.ExprNormalizer;
import pt.haslab.alloyaddons.ExprStringify;
import pt.haslab.specassistant.services.treeedit.ASTEditDiff;
import pt.haslab.mutation.Candidate;
import pt.haslab.mutation.mutator.Mutator;
import pt.haslab.Repairer;
import org.higena.parser.A4FParser;
import org.higena.hint.Hint;

/** Narrow JSONL adapter over the authors' unmodified published JAR methods.
 * Graph persistence/policy is supplied by worker.py. No teaching-portal code is
 * linked into the baseline classloader (except its org.json dependency).
 */
public final class FM24Native {
    private static CompModule world(JSONObject q) throws Exception {
        String code=q.has("code") ? q.getString("code") : Files.readString(Path.of(q.getString("path")));
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, code);
    }
    private static Expr body(CompModule m, String name) {
        for (Func f:m.getAllFunc()) if(f.label.equals(name)||f.label.equals("this/"+name)) return f.getBody();
        throw new IllegalArgumentException("predicate_not_found");
    }
    private static String normal(Expr e) {return ExprStringify.stringify(ExprNormalizer.normalize(e));}
    private static JSONObject run(JSONObject q) throws Exception {
        long start=System.nanoTime(); String action=q.optString("action","normalize");
        JSONObject result=new JSONObject();
        CompModule m=world(q);
        if(action.equals("normalize")) {
            result.put("formula",normal(body(m,q.getString("predicate"))));
            if(q.has("oracle"))result.put("oracle_formula",normal(body(m,q.getString("oracle"))));
        } else if(action.equals("distance")) {
            Expr left=m.parseOneExpressionFromString(q.getString("source"));
            Expr right=m.parseOneExpressionFromString(q.getString("target"));
            result.put("distance",new ASTEditDiff().initFrom(left,right).computeEditDistance());
        } else if(action.equals("hint")) {
            String left=A4FParser.parse(q.getString("source"),m).toTreeString();
            String right=A4FParser.parse(q.getString("target"),m).toTreeString();
            // The paper describes GumTree mappings plus Chawathe's script.
            // The bundled authors' Hint constructor selects its own default.
            String hint=new Hint(left,right).toString();
            result.put("hint",hint);
            result.put("hint_available",!hint.isBlank());
        } else if(action.equals("mutations")) {
            JSONArray out=new JSONArray();
            for(Candidate c:Repairer.getValidCandidates(body(m,q.getString("predicate")),m.getAllReachableSigs(),1)) {
                String text="";
                for(Mutator mu:c.mutators) if(mu.hint().isPresent()) { text=mu.hint().get().toString(); break; }
                out.put(new JSONObject().put("formula",normal(c.mutated)).put("hint",text));
            }
            result.put("mutations",out);
        } else throw new IllegalArgumentException("unknown_action");
        return result.put("status","ok").put("engine_s",(System.nanoTime()-start)/1e9);
    }
    public static void main(String[] args) throws Exception {
        PrintStream protocol=System.out;
        // Upstream diagnostics must not corrupt the JSONL protocol.
        System.setOut(System.err);
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        for(String line;(line=input.readLine())!=null;) {
            try { protocol.println(run(new JSONObject(line)).toString()); }
            catch(Throwable e) {protocol.println(new JSONObject().put("status","error").put("error_type",e.getClass().getName()).put("error",String.valueOf(e.getMessage())).toString());}
            protocol.flush();
        }
    }
}
