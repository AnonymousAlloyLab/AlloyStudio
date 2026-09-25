package is.fivefivefive.CanDis.macros;

import java.util.Set;
import java.util.HashSet;

/*
 * Abstract Alloy Model Environment defined by a collection of predefined sets.
 * Consider a universal representation of the various Alloy models.
 * It would be editted by the visitor in those global declarations, and then used by the visitor in the local declarations and formulas.
 */
public class ModelEnv {
    private String moduleName; // the name of the model, which can be used for error reporting and debugging.
    private Set<String> sigs; // the set of signatures in the model
    private Set<String> fields; // the set of fields in the model
    private Set<String> globalBindings; // the set of global bindings in the model, including predicates, functions, facts, assertions, etc.
    private Set<String> opens; // the set of opened modules in the model, which can be used to resolve the references in the formulas and functions, and to check the types of the quantified variables.
    private Set<String> commands; // the set of commands in the model, which can be used to check the properties of the model, and to generate the normal forms for the formulas in the commands.
    public ModelEnv() {
        this.sigs = new HashSet<>();
        this.fields = new HashSet<>();
        this.globalBindings = new HashSet<>();
        this.opens = new HashSet<>();
        this.commands = new HashSet<>();
    }
    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }
    public String getModuleName() {
        return this.moduleName;
    }
    public void addSig(String sig) {
        this.sigs.add(sig);
    }
    public void addField(String field) {
        this.fields.add(field);
    }
    public void addGlobalBinding(String binding) {
        this.globalBindings.add(binding);
    }
    public boolean containsSig(String sig) {
        return this.sigs.contains(sig);
    }
    public boolean containsField(String field) {
        return this.fields.contains(field);
    }
    public boolean containsGlobalBinding(String binding) {
        return this.globalBindings.contains(binding);
    }
    public Set<String> getSigs() {
        return this.sigs;
    }
    public Set<String> getFields() {
        return this.fields;
    }
    public Set<String> getGlobalBindings() {
        return this.globalBindings;
    }
    public void addOpen(String open) {
        this.opens.add(open);
    }
    public boolean containsOpen(String open) {
        return this.opens.contains(open);
    }
    public Set<String> getOpens() {
        return this.opens;
    }
    public void addCommand(String command) {
        this.commands.add(command);
    }
    public boolean containsCommand(String command) {
        return this.commands.contains(command);
    }
    public Set<String> getCommands() {
        return this.commands;
    }
}
