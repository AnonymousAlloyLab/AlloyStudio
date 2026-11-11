package parser.util;

import static parser.etc.Context.logger;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Type;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import kodkod.engine.satlab.SATFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import parser.ast.nodes.Assertion;
import parser.ast.nodes.Body;
import parser.ast.nodes.Fact;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.PredOrFun;
import parser.ast.nodes.SigDecl;
import parser.ast.nodes.UnaryExpr;
import parser.ast.nodes.UnaryExpr.UnaryOp;
import parser.ast.visitor.PrettyStringVisitor;
import parser.etc.Names;

public class AlloyUtil {

  public static CompModule compileAlloyModule(String modelPath) {
    try {
      return CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, modelPath);
    } catch (Err e) {
      e.printStackTrace();
      logger.debug(e.getMessage());
      return null;
    }
  }

  public static boolean isValidModel(String model) {
    FileUtil.writeText(model, Names.CHECK_FILE_PATH, false);
    if (compileAlloyModule(Names.CHECK_FILE_PATH) == null) {
      logger.debug("Error model: ");
      logger.debug(model);
      return false;
    }
    return true;
  }

  public static void loadMiniSatSolver(SATFactory solver) {
    if (solver == null || !solver.isPresent()) {
      throw new IllegalStateException("Requested Alloy solver is unavailable: " + solver);
    }
  }

  public static boolean isHomogeneous(Type type) {
    List<Sig.PrimSig> flattenTypes = type.fold().stream().flatMap(List::stream)
        .collect(Collectors.toList());
    return flattenTypes.size() == 2 && flattenTypes.get(0).equals(flattenTypes.get(1));
  }

  /**
   * Construct a string from a node recursively to its closest parent which is of type SigDecl,
   * Predicate, Funtion, Fact or Assertion.
   */
  public static String computeNodePathAsString(Node node, PrettyStringVisitor psv) {
    List<String> res = new ArrayList<>();
    Node cur = node;
    while (cur != null) {
      if (res.size() != 0 && cur instanceof UnaryExpr
          && ((UnaryExpr) cur).getOp() == UnaryOp.NOOP) {
        cur = cur.getParent();
        continue;
      }
      res.add(cur.accept(psv, null));
      if (cur instanceof SigDecl || cur instanceof PredOrFun || cur instanceof Fact
          || cur instanceof Assertion) {
        break;
      }
      cur = cur.getParent();
    }
    Collections.reverse(res);
    return String.join(Names.NEW_LINE + Names.VERTICAL_BAR + Names.NEW_LINE, res);
  }

  public static String computeNodePathAsString(Node node) {
    return computeNodePathAsString(node, new PrettyStringVisitor());
  }

  public static Node getFirstNonNOOPChild(Node node) {
    Node cur = node;
    while (cur instanceof UnaryExpr) {
      UnaryExpr unaryExpr = (UnaryExpr) cur;
      if (unaryExpr.getOp() != UnaryOp.NOOP) {
        break;
      }
      cur = unaryExpr.getSub();
    }
    return cur;
  }

  /** Transparent AST view; retains the original semantic node and source ownership. */
  public static Node unwrapAst(Node node) {
    while (true) {
      if (node instanceof UnaryExpr unary && unary.getOp() == UnaryOp.NOOP) {
        node = java.util.Objects.requireNonNull(unary.getSub(), "NOOP has no child");
      } else if (node instanceof Body body) {
        node = java.util.Objects.requireNonNull(body.getBodyExpr(), "Body has no child");
      } else {
        return node;
      }
    }
  }

  public static Node getFirstNonNOOPParent(Node node) {
    Node cur = node;
    while (cur instanceof UnaryExpr) {
      UnaryExpr unaryExpr = (UnaryExpr) cur;
      if (unaryExpr.getOp() != UnaryOp.NOOP) {
        break;
      }
      cur = cur.getParent();
    }
    return cur;
  }

  public static Node findFirstParentIn(Node child, Set<Node> possibleParents) {
    Node cur = child;
    while (cur != null && !possibleParents.contains(cur)) {
      cur = cur.getParent();
    }
    return cur;
  }

  /**
   * Return the first ancestor with a given type, or null if there is no ancestor of such type.
   */
  public static Node findFirstAncestorWithType(Node node, Class<? extends Node> clazz) {
    Node cur = node;
    while (cur != null && !clazz.isAssignableFrom(cur.getClass())) {
      cur = cur.getParent();
    }
    return cur;
  }

  public static CompModule mergeModelAndTests(ModelUnit modelUnit, String testSuitePath,
      PrettyStringVisitor psv) {
    String entireModelAsString = String.join(Names.NEW_LINE,
        Arrays.asList(
            modelUnit.accept(psv, null),
            FileUtil.readTextWithFilter(
                testSuitePath,
                (line, pattern) -> !line.startsWith(pattern),
                "open")
        )
    );
    FileUtil.writeText(entireModelAsString, Names.TMPT_FILE_PATH, false);
    return AlloyUtil.compileAlloyModule(Names.TMPT_FILE_PATH);
  }
}
