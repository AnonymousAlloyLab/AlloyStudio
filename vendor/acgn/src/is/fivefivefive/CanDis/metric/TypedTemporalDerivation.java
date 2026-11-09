package is.fivefivefive.CanDis.metric;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;

/** Branch-sensitive execution of the already selected temporal program. */
final class TypedTemporalDerivation {
    private TypedTemporalDerivation() { }
    private static final class Cell {
        String label;
        int origin, role;
        Cell parent;
        final List<Cell> children = new ArrayList<>();
        Cell(String label,int origin,int role,Cell parent) {
            this.label=label;this.origin=origin;this.role=role;this.parent=parent;
        }
    }
    private record Endpoint(TemporalNode node,int parent,int role) { }

    static JSONObject capture(TemporalNode source,TemporalNode target,JSONObject selected) {
        return execute(source,target,selected,null);
    }
    static void verify(TemporalNode source,TemporalNode target,JSONObject selected,JSONObject claimed) {
        require(execute(source,target,selected,claimed).similar(claimed),"Typed temporal derivation changed");
    }
    private static JSONObject execute(TemporalNode source,TemporalNode target,JSONObject selected,JSONObject claimed) {
        TemporalReplay.verify(source,target,TemporalNode::label,TemporalNode::children,selected);
        var as=new ArrayList<Endpoint>();var bs=new ArrayList<Endpoint>();
        endpoint(source,null,as);endpoint(target,null,bs);
        var map=new HashMap<Integer,Integer>();var inverse=new HashMap<Integer,Integer>();
        for(Object value:selected.getJSONArray("selectedCorrespondence")) {
            JSONArray p=(JSONArray)value;int a=p.getInt(0),b=p.getInt(1);
            require(map.put(a,b)==null&&inverse.put(b,a)==null,"Typed temporal repeated alignment");
        }
        var preorder=new ArrayList<Integer>();preorder(bs.size(),bs,preorder);
        var insertions=new ArrayDeque<Integer>();for(int id:preorder)if(!inverse.containsKey(id))insertions.add(id);
        Cell forest=new Cell("",0,-1,null);int[] index={0};
        forest.children.add(snapshot(source,null,forest,index));
        var budget=new TraceResources();
        var operations=new JSONArray();JSONArray edits=selected.getJSONArray("operations");
        if(claimed!=null)require(claimed.getJSONArray("operations").length()==edits.length(),"Typed temporal operation coverage");
        for(int i=0;i<edits.length();i++) {
            JSONObject edit=edits.getJSONObject(i);Cell cell=resolve(forest,edit.getJSONArray("path"));
            var before=state(forest,budget);var payload=new JSONObject().put("operation",i).put("selectedEdit",edit);
            JSONObject supplied=claimed==null?null:claimed.getJSONArray("operations").getJSONObject(i);
            switch(edit.getString("kind")) {
                case "delete" -> {
                    require(cell.parent!=null&&!map.containsKey(cell.origin),"Typed deletion selected occurrence");
                    int at=cell.parent.children.indexOf(cell);Cell owner=cell.parent;
                    owner.children.remove(at);owner.children.addAll(at,cell.children);
                    for(Cell child:cell.children){child.parent=owner;child.role=cell.role;}
                    cell.parent=null;
                }
                case "update" -> {
                    Integer destination=map.get(cell.origin);
                    require(destination!=null,"Typed update selected occurrence");
                    var endpoint=bs.get(destination-1);
                    require(endpoint.node.label().equals(edit.getString("to")),"Typed update operator payload");
                    // Capture chooses a refinement of this selected head edit. Replay
                    // consumes that choice and checks the local rule without a target
                    // routing oracle; target correspondence is checked after execution.
                    List<Integer> roles=supplied==null?childRoles(cell.children,destination,bs,map):roles(supplied);
                    payload.put("targetOccurrence",destination).put("childRoles",new JSONArray(roles));
                    require(edit.getInt("cost")==1&&cell.label.equals(edit.getString("from"))
                            &&!cell.label.equals(edit.getString("to")),"Routing requires the selected paid head update");
                    payload.put("routingRule",routingRule("paid-head-update",cell,cell.children,
                            edit.getString("to"),cell.role,roles));
                    cell.label=edit.getString("to");
                    for(int j=0;j<roles.size();j++)cell.children.get(j).role=roles.get(j);
                }
                case "insert" -> {
                    require(!insertions.isEmpty(),"Typed insertion selected occurrence");int destination=insertions.remove();
                    var endpoint=bs.get(destination-1);int at=edit.getInt("index"),count=edit.getInt("childCount");
                    require(endpoint.node.label().equals(edit.getString("label")),"Typed insertion operator payload");
                    require(at>=0&&count>=0&&at<=cell.children.size()&&count<=cell.children.size()-at,"Typed insertion interval");
                    var children=new ArrayList<>(cell.children.subList(at,at+count));
                    // Wrapping cannot silently move retained children across a binary
                    // owner's branches. The paid insertion supplies only its own split.
                    int incoming=supplied==null?endpoint.role:supplied.getInt("incomingRole");
                    for(Cell child:children)require(child.role==incoming,"Typed insertion crosses owner branch");
                    List<Integer> roles=supplied==null?childRoles(children,destination,bs,map):roles(supplied);
                    require(edit.getInt("cost")==1,"Routing requires the selected paid insertion");
                    require(cell.parent==null?incoming==-1:incoming>=0&&incoming<kinds(cell.label).size(),"Typed insertion incoming owner role");
                    require((at==0||cell.children.get(at-1).role<=incoming)
                            &&(at+count==cell.children.size()||incoming<=cell.children.get(at+count).role),"Typed insertion owner order");
                    payload.put("targetOccurrence",destination).put("incomingRole",incoming).put("childRoles",new JSONArray(roles));
                    payload.put("routingRule",routingRule("paid-interval-insert",cell,children,
                            edit.getString("label"),incoming,roles));
                    Cell inserted=new Cell(edit.getString("label"),-destination,incoming,cell);
                    cell.children.subList(at,at+count).clear();cell.children.add(at,inserted);
                    for(int j=0;j<children.size();j++){Cell child=children.get(j);child.parent=inserted;child.role=roles.get(j);inserted.children.add(child);}
                }
                default -> throw new IllegalArgumentException("Unknown typed temporal operation");
            }
            payload.put("before",before).put("after",state(forest,budget));
            if(claimed!=null)require(payload.similar(claimed.getJSONArray("operations").getJSONObject(i)),"Typed temporal step/payload/precondition changed at "+i);
            operations.put(payload);
        }
        require(insertions.isEmpty()&&forest.children.size()==1,"Typed temporal final root");
        var actual=new ArrayList<Cell>();postorder(forest.children.get(0),actual);
        require(actual.size()==bs.size(),"Typed temporal final coverage");
        var phases=new IdentityHashMap<Cell,List<Integer>>();
        phases.put(forest.children.get(0),List.of(0));reserveBranches(forest.children.get(0),phases,new int[]{1});
        var lineage=new JSONArray();
        for(int i=0;i<actual.size();i++) {
            Cell c=actual.get(i);Endpoint b=bs.get(i);int parent=c.parent==forest?0:actual.indexOf(c.parent)+1;
            require(c.label.equals(b.node.label())&&c.role==b.role&&parent==b.parent,"Typed temporal final owner/role/operator at "+(i+1));
            require(c.origin>0?Objects.equals(map.get(c.origin),i+1):c.origin==-(i+1),"Typed temporal final lineage");
            require(kinds(c.label).equals(b.node.sourceBranches().stream().map(n->n.getTemporalOp().name()).toList()),"Typed temporal branch kinds");
            int ownerPhase=parent==0?-1:phases.get(c.parent).get(c.role);
            require(ownerPhase==b.node.ownerPhase()&&phases.get(c).equals(b.node.branchPhases()),"Executed typed branch reservation/phase coordinates");
            int localSlot=-1;
            if(b.node.sourceOwner()!=null) {
                var original=b.node.sourceOwner().getTemporalChildren();
                for(int j=0;j<original.size();j++)if(original.get(j)==b.node.sourceBranches().get(0))localSlot=j;
                require(localSlot>=0,"Executed edge target source alias");
            }
            lineage.put(new JSONObject().put("targetOccurrence",i+1).put("sourceOccurrence",Math.max(0,c.origin))
                    .put("insertedTargetOccurrence",Math.max(0,-c.origin)).put("ownerOccurrence",parent).put("role",c.role)
                    .put("operator",c.label).put("branchKinds",new JSONArray(kinds(c.label)))
                    .put("ownerKind",parent==0?"NONE":kinds(c.parent.label).get(c.role))
                    .put("ownerPhase",ownerPhase).put("branchPhases",new JSONArray(phases.get(c)))
                    .put("originalLocalSlot",localSlot)
                    .put("targetSourcePaths",new JSONArray(b.node.sourceBranches().stream().map(NormalForm::getPhasePath).toList())));
        }
        return new JSONObject().put("schema","typed-temporal-splice-v2").put("operations",operations)
                .put("lineage",lineage).put("cost",selected.getInt("cost"));
    }
    private static List<Integer> roles(JSONObject supplied) {
        var result=new ArrayList<Integer>();var array=supplied.getJSONArray("childRoles");
        for(int i=0;i<array.length();i++) {
            Object value=array.get(i);
            require(value instanceof Integer,"Typed routing role must be an exact integer");
            result.add((Integer)value);
        }
        return result;
    }
    /** Refines one paid head edit, not an equivalence rewrite. Child payloads
     * stay in place; only their explicit incoming branch annotations change. */
    private static JSONObject routingRule(String rule,Cell before,List<Cell> children,
                                         String head,int incoming,List<Integer> roles) {
        List<String> branches=kinds(head);
        for(String kind:branches)NormalForm.TemporalOp.valueOf(kind);
        require(roles.size()==children.size(),"Typed routing child coverage");
        var origins=new JSONArray();var fromRoles=new JSONArray();var branchKinds=new JSONArray();int previous=-1;
        for(int i=0;i<children.size();i++) {
            int role=roles.get(i);
            require(role>=0&&role<branches.size()&&role>=previous,"Typed routing operator partition/order");
            previous=role;origins.put(children.get(i).origin);fromRoles.put(children.get(i).role);branchKinds.put(branches.get(role));
        }
        return new JSONObject().put("rule",rule).put("metricCharge",1).put("beforeHead",before.label)
                .put("afterHead",head).put("incomingRole",incoming).put("childOrigins",origins)
                .put("beforeChildRoles",fromRoles).put("afterChildKinds",branchKinds);
    }
    private static List<Integer> childRoles(List<Cell> children,int owner,List<Endpoint> target,Map<Integer,Integer> map) {
        var result=new ArrayList<Integer>();
        for(Cell child:children) {
            Integer id=child.origin>0?map.get(child.origin):-child.origin;
            require(id!=null,"Typed routed child lacks selected lineage");
            while(target.get(id-1).parent!=owner) {
                id=target.get(id-1).parent;require(id>0,"Typed routed child lacks target ancestor");
            }
            result.add(target.get(id-1).role);
        }
        return result;
    }
    private static Cell snapshot(TemporalNode n,TemporalNode parent,Cell owner,int[] index) {
        Cell cell=new Cell(n.label(),0,role(n,parent),owner);
        for(var child:n.children())cell.children.add(snapshot(child,n,cell,index));
        cell.origin=++index[0];return cell;
    }
    private static int endpoint(TemporalNode n,TemporalNode parent,List<Endpoint> result) {
        var ids=new ArrayList<Integer>();for(var child:n.children())ids.add(endpoint(child,n,result));
        int id=result.size()+1;result.add(new Endpoint(n,0,role(n,parent)));
        for(int child:ids){var e=result.get(child-1);result.set(child-1,new Endpoint(e.node,id,e.role));}return id;
    }
    private static int role(TemporalNode n,TemporalNode parent) {
        if(parent==null)return -1;
        for(int i=0;i<parent.sourceBranches().size();i++)if(parent.sourceBranches().get(i)==n.sourceOwner())return i;
        throw new IllegalArgumentException("Typed temporal source owner missing");
    }
    private static List<String> kinds(String label) {
        return switch(label){case "UNTIL","RELEASES","SINCE","TRIGGERED" -> List.of(label+"L",label+"R");default -> List.of(label);};
    }
    private static void preorder(int id,List<Endpoint> nodes,List<Integer> out) {
        out.add(id);for(int i=0;i<nodes.size();i++)if(nodes.get(i).parent==id)preorder(i+1,nodes,out);
    }
    private static void postorder(Cell c,List<Cell> out){for(Cell child:c.children)postorder(child,out);out.add(c);}
    private static void reserveBranches(Cell owner,Map<Cell,List<Integer>> phases,int[] next) {
        int previousRole=-1;
        for(Cell child:owner.children) {
            require(child.role>=previousRole&&child.role<kinds(owner.label).size(),"Executed branch grouping/order");
            previousRole=child.role;var slots=new ArrayList<Integer>();
            for(int i=0;i<kinds(child.label).size();i++)slots.add(next[0]++);
            phases.put(child,List.copyOf(slots));reserveBranches(child,phases,next);
        }
    }
    private static Cell resolve(Cell root,JSONArray path) {
        for(int i=0;i<path.length();i++){int at=path.getInt(i);require(at>=0&&at<root.children.size(),"Typed temporal address");root=root.children.get(at);}return root;
    }
    private static JSONArray state(Cell c,TraceResources budget) {
        budget.spend(Math.addExact(96L,Math.multiplyExact(2L,c.label.length())));
        var children=new JSONArray();for(Cell child:c.children)children.put(state(child,budget));
        String role=c.role<0?"ROOT":kinds(c.parent.label).get(c.role);
        return new JSONArray().put(c.label).put(c.origin).put(c.role).put(children).put(role);
    }
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException(reason);}
}
