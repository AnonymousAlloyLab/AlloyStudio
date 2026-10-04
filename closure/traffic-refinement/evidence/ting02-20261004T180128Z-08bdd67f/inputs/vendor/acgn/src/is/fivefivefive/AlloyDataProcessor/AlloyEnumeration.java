package is.fivefivefive.AlloyDataProcessor;

import java.io.PrintWriter;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.ErrorWarning;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

public class AlloyEnumeration {
	
	public static void main (String [] args) {
		//Test file
		String file_name = "classroom_fol.als";
		
		 //Alloy requires this reporter object - it essentially handles thrown errors when trying to run a model/command
		 A4Reporter rep = new A4Reporter() {
            @Override
            public void warning(ErrorWarning msg) {
                System.out.println(msg.toString().trim());
                System.out.flush();
            }
	    };
	        
	    //This reads in the Alloy model, parses it and then stores it as a CompModule
	    //Which basically is an object interpretation of the model that we can work with to do things like iterate over commands in the model and run them
        CompModule world = CompUtil.parseEverything_fromFile(rep, null, file_name);
        
        for(Sig s : world.getAllReachableSigs()) {
        	System.out.println(s.type());
        	System.out.println(s.isTopLevel());
        }
        
        //We can use the world object to nagivate over the different predicates stored in the model
        for(Func predicate : world.getAllFunc()) {
        	//System.out.println(predicate.getBody());
        	//When you get body, you can then recursively explore this Expr object and get the different subformulas and their types
        }
        
        //Case in point, we can get the list of all commands written in the model. This runs the first command
        int cmdNum = 0;
        Command command = world.getAllCommands().get(cmdNum); //you can also build an Alloy command yourself to run something specific you create using the API
        
        //These options configure "how" to run a command - what solver to use, what is the max memory the execution can use, etc
        //We usually just set it up in a very bare bones format - i.e. just set the SAT solver then use the default for everything else
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
		
		//Runs first command, stores the result
        A4Solution instance = TranslateAlloyToKodkod.execute_command(rep, world.getAllReachableSigs(), command, options);
        
        //Iterating over instances
        while(instance.satisfiable()) {
        	instance.next(); //get next instance - for check commands this is counterexamples, for run commands this is scenarios
        	//Check if valid for a predicate
        	//If you know the name of the predicate:
        	String pred  = "inv1";
        	boolean result = (boolean) instance.eval(CompUtil.parseOneExpression_fromString(world, pred));
        	System.out.println(result);
        	
        	//Using the API to get the predicate names in the model:
        	for(Func predicate : world.getAllFunc()) {
        		if(predicate.isPred) {
        			pred = predicate.label;
        			result = (boolean) instance.eval(CompUtil.parseOneExpression_fromString(world, pred));
                	System.out.println(result);
        		}
            }
        }
	}

}
