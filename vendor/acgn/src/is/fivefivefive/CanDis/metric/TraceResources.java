package is.fivefivefive.CanDis.metric;

/** Resource accounting, not admission of a syntactic subset. Failures never count as replay. */
public final class TraceResources {
    private static final long MAX_BYTES=64L*1024*1024;
    private long remaining=MAX_BYTES;
    public void spend(long bytes) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        if(bytes<0||bytes>remaining)throw new IllegalStateException("NATIVE_TRACE_RESOURCE_BUDGET");
        remaining-=bytes;
    }
    public void cells(long rows,long columns) {
        if(rows<0||columns<0||(rows!=0&&columns>remaining/4/rows))
            throw new IllegalStateException("NATIVE_TRACE_RESOURCE_BUDGET");
        spend(4L*rows*columns);
    }
    public static void requireCells(long rows,long columns) { new TraceResources().cells(rows,columns); }
    public static void requireBytes(long bytes) { new TraceResources().spend(bytes); }

    /** Bounds expanded JSON before serialization, including repeated DAG references. */
    public static void requireJson(Object root) {
        record Visit(Object value,boolean finish) { }
        var lengths=new java.util.IdentityHashMap<Object,Long>();
        var active=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
        var stack=new java.util.ArrayDeque<Visit>();stack.push(new Visit(root,false));
        while(!stack.isEmpty()) {
            var next=stack.pop();Object value=next.value;
            if(lengths.containsKey(value))continue;
            if(!(value instanceof org.json.JSONObject)&&!(value instanceof org.json.JSONArray)) {
                String encoded=value==null||value==org.json.JSONObject.NULL?"null":value instanceof String
                        ?org.json.JSONObject.quote((String)value):value.toString();
                requireBytes(Math.multiplyExact(2L,encoded.length()));lengths.put(value,(long)encoded.length());continue;
            }
            if(!next.finish) {
                if(!active.add(value))throw new IllegalArgumentException("Cyclic native trace JSON");
                stack.push(new Visit(value,true));
                if(value instanceof org.json.JSONObject object)for(String key:object.keySet())stack.push(new Visit(object.get(key),false));
                else for(Object item:(org.json.JSONArray)value)stack.push(new Visit(item,false));
                continue;
            }
            long size=2;
            if(value instanceof org.json.JSONObject object) {
                for(String key:object.keySet()) size=Math.addExact(size,Math.addExact(org.json.JSONObject.quote(key).length()+2L,lengths.get(object.get(key))));
                if(!object.isEmpty())size--;
            } else {
                var array=(org.json.JSONArray)value;
                for(Object item:array)size=Math.addExact(size,Math.addExact(1L,lengths.get(item)));
                if(!array.isEmpty())size--;
            }
            requireBytes(Math.multiplyExact(2L,size));lengths.put(value,size);active.remove(value);
        }
    }
}
