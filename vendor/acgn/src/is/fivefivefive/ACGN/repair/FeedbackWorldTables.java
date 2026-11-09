package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.ast.Sig;

/** Numeric encoding of actual returned worlds; no bank, future-response or target access. */
final class FeedbackWorldTables {
    private FeedbackWorldTables() { }

    record Export(JSONObject tables, List<String> labels) { }

    static JSONObject encode(List<JSONObject> responses, FeedbackSourceGraph source) {
        return export(responses, source).tables();
    }
    static Export export(List<JSONObject> responses, FeedbackSourceGraph source) {
        List<JSONObject> all = new ArrayList<>();
        List<int[]> owners = new ArrayList<>();
        for (int q = 0; q < responses.size(); q++) {
            JSONObject response = responses.get(q);
            for (Object object : response.getJSONArray("witnesses")) {
                all.add((JSONObject) object);
                owners.add(new int[] {q, response.getInt("revision")});
            }
        }
        int first = Math.max(0, all.size() - 8);
        Map<String, Integer> sourceSymbols = new HashMap<>();
        for (int i = 0; i < source.symbols.size(); i++) {
            var symbol = source.symbols.get(i);
            if (symbol instanceof Sig s) sourceSymbols.put(s.label, i);
            if (symbol instanceof Sig.Field f) sourceSymbols.put(f.sig.label + "." + f.label, i);
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        Map<String, JSONArray> exactTypes = new HashMap<>();
        Map<String, Integer> origins = new HashMap<>();
        // Returned names label addresses in a private map, never embedding categories.
        for (int i = first; i < all.size(); i++) {
            JSONObject world = all.get(i);
            for (String label : world.getJSONObject("types").keySet().stream().sorted().toList()) {
                ids.computeIfAbsent(label, k -> ids.size());
                origins.putIfAbsent(label, i - first);
                JSONArray type = world.getJSONObject("types").getJSONArray(label);
                exactTypes.put(label, type);
                for (Object product : type) for (Object column : (JSONArray) product) {
                    String sig = (String) column;
                    ids.computeIfAbsent(sig, k -> ids.size());
                    origins.putIfAbsent(sig, i - first);
                }
            }
        }
        JSONArray symbols = new JSONArray(), typePorts = new JSONArray(), worlds = new JSONArray();
        JSONArray tuples = new JSONArray(), atoms = new JSONArray();
        for (var entry : ids.entrySet()) {
            String label = entry.getKey(); int id = entry.getValue();
            int builtin = switch (label) {
                case "univ" -> 0; case "none" -> 1; case "Int" -> 2;
                case "seq/Int" -> 3; case "String" -> 4; default -> -1;
            };
            symbols.put(FeedbackSourceGraph.row(label.contains(".") ? 1 : 0,
                    sourceSymbols.getOrDefault(label, -1), origins.get(label), builtin));
            JSONArray type = exactTypes.get(label);
            if (type == null) {
                // Primitive column signatures have the unary self type, not a univ fallback.
                typePorts.put(FeedbackSourceGraph.row(id, 0, 0, id));
            } else for (int p = 0; p < type.length(); p++) {
                JSONArray columns = type.getJSONArray(p);
                for (int c = 0; c < columns.length(); c++) {
                    typePorts.put(FeedbackSourceGraph.row(id, p, c, ids.get(columns.getString(c))));
                }
            }
        }
        for (int i = first; i < all.size(); i++) {
            JSONObject world = all.get(i); int index = i - first;
            worlds.put(FeedbackSourceGraph.row(owners.get(i)[0], owners.get(i)[1],
                    world.getBoolean("expected") ? 1 : 0, world.getBoolean("actual") ? 1 : 0,
                    world.getJSONArray("states").length(), world.getInt("loop")));
            Map<String, Integer> atomIds = new LinkedHashMap<>();
            int tuple = 0;
            for (int time = 0; time < world.getJSONArray("states").length(); time++) {
            JSONObject relations = world.getJSONArray("states").getJSONObject(time);
            for (String label : relations.keySet().stream().sorted().toList()) {
                for (Object object : relations.getJSONArray(label)) {
                    JSONArray columns = (JSONArray) object;
                    for (int c = 0; c < columns.length(); c++) {
                        String atom = columns.getString(c);
                        if (!atomIds.containsKey(atom)) {
                            int id = atomIds.size(); atomIds.put(atom, id);
                            boolean number = atom.matches("-?[0-9]+");
                            atoms.put(FeedbackSourceGraph.row(index, id, number ? 1 : 0,
                                    number ? Integer.parseInt(atom) : 0));
                        }
                        tuples.put(FeedbackSourceGraph.row(index, time, ids.get(label), tuple, c, atomIds.get(atom)));
                    }
                    tuple++;
                }
            }
            }
        }
        JSONObject tables = new JSONObject().put("symbols", symbols).put("typePorts", typePorts)
                .put("worlds", worlds).put("atoms", atoms).put("tuples", tuples)
                .put("omittedWorlds", first);
        return new Export(tables, List.copyOf(ids.keySet()));
    }
}
