package is.fivefivefive.CanDis;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Shared enclosing scope is not shared local-owner identity. */
public final class DistinctLocalOwnersTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object field(Object o,String name)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static int count(NormalForm f)throws Exception {
        var m=FastLocalPaidScope.class.getDeclaredMethod("checkedOwnerCount",NormalForm.class);m.setAccessible(true);return (int)m.invoke(null,f);
    }
    private static List<EGraphNode> nodes(EGraphNode n){var out=new ArrayList<EGraphNode>();out.add(n);for(var c:n.getChildren())out.addAll(nodes(c));return out;}
    private static void corrupt(NormalForm form,Object object,String name,Object bad)throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);var old=f.get(object);
        int original=count(form);check(original>1,"multiple original owners");
        try{f.set(object,bad);check(count(form)==0,"reject substituted "+name);rejections++;}finally{f.set(object,old);}
        check(count(form)==original,"original owners restored");
    }
    public static void main(String[] args)throws Exception {
        OrderedLocalPaidAdmissionTest.main(args);
        var rows=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);var controls=new JSONArray();
        for(Object value:rows){var row=(JSONObject)value;if(!row.optBoolean("distinctOwners"))continue;
            var id=row.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);var covered=new HashSet<String>();
            for(int d=0;d<2;d++)for(Object endpoint:row.getJSONArray("localEndpoints")){
                int e=((Number)endpoint).intValue();var form=CanonicalDistance.prepare(pair.get(e).normalizedForms()).comparisonForms().get(0);
                var all=nodes(form.getMatrixEGraph());var owners=all.stream().filter(n->n.getChildren().stream().anyMatch(LocalOwnerCorrespondence::declaration)).toList();
                check(owners.size()>=2&&count(form)==owners.size(),"all retained sibling owners admitted");
                var a=owners.get(0);var b=owners.get(1);var av=a.getChildren().get(0).getChildren().get(1);var bv=b.getChildren().get(0).getChildren().get(1);
                check(av.getAlphaName().equals(bv.getAlphaName()),"same lexical coordinate has distinct owners");
                corrupt(form,b,"sourceOccurrenceLineage",a.getSourceOccurrenceLineage());
                corrupt(form,bv,"sourceOccurrenceLineage",av.getSourceOccurrenceLineage());
                var uses=nodes(b.getChildren().get(b.getChildren().size()-1));
                var use=uses.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&bv.getAlphaName().equals(n.getAlphaName())).findFirst().orElseThrow();
                corrupt(form,use,"sourceOccurrenceLineage",av.getSourceOccurrenceLineage());
                var parent=all.stream().filter(n->n.getChildren().contains(b)).findFirst().orElseThrow();
                var children=new ArrayList<Object>((List<?>)field(a,"childClasses"));
                children.set(children.size()-1,((List<?>)field(parent,"childClasses")).get(parent.getChildren().indexOf(b)));
                corrupt(form,a,"childClasses",children);
                covered.add(d+":"+e);controls.put(new JSONObject().put("identity",id).put("direction",d).put("endpoint",e).put("ownerOccurrences",new JSONArray().put(a.getSourceOccurrenceLineage()).put(b.getSourceOccurrenceLineage())).put("rejected",4));
            }
            var expected=new HashSet<String>();for(Object e:row.getJSONArray("localEndpoints"))for(int d=0;d<2;d++)expected.add(d+":"+e);
            check(covered.equals(expected)&&!covered.isEmpty(),"both directions and each prepared local endpoint");
            if(row.optBoolean("newAdmission"))for(int d=0;d<2;d++){
                String name=id.getString("id").replace('/','_')+"-"+d+".json";
                var before=new JSONObject(Files.readString(Path.of(row.getString("priorDirectory"),name)));
                var after=new JSONObject(Files.readString(out.resolve(name)));
                var old=before.getJSONObject("fast");var now=after.getJSONObject("fast");
                check(old.getInt("distance")==now.getInt("distance"),"original native charge retained");
                check(old.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").isEmpty(),"actual prior empty selection");
                check(!now.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"actual selected paid map now retained");
                var x=old.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");
                var y=now.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");
                check(x.length()==y.length(),"native matrix operation count retained");
                for(int i=0;i<x.length();i++)for(String key:List.of("kind","cost","source","target","premise"))check(Objects.equals(x.getJSONObject(i).get(key),y.getJSONObject(i).get(key)),"native action/address/premise retained "+i+" "+key);
                after.put("fastPriorPathRetention",new JSONObject().put("kindCostAddressPremiseUnchanged",true).put("allPayloadsUnchanged",false).put("explanation","Selected paid alpha map is now embedded in Fast heads; old empty-map payloads remain in original receipts, not silently equated."));
                Files.writeString(out.resolve(name),after.toString());
            }
        }
        var previous=new JSONObject(Files.readString(out.resolve("results.json")));
        var result=new JSONObject(previous.toString()).put("checks",previous.getInt("checks")+checks).put("tamperedEvidenceRejections",previous.getInt("tamperedEvidenceRejections")+rejections).put("distinctOwnerControls",controls).put("previousFocusedChecks",previous.getInt("checks"));
        Files.writeString(out.resolve("distinct-results.json"),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DistinctLocalOwnersTest: "+result.getInt("checks")+" checks, "+result.getInt("tamperedEvidenceRejections")+" tampered-evidence rejections, "+previous.getJSONArray("rows").length()+" directions");
    }
}
