package live;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only sequential driver; never exposes private pool identities or solutions. */
public final class BehaviorSequence {
    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        JSONArray requests = new JSONArray(new String(System.in.readNBytes(1_048_577),
                StandardCharsets.UTF_8));
        if (requests.length() > 12) throw new IllegalArgumentException();
        JSONArray observations = new JSONArray();
        BehaviorFeedback.resetPools();
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.getJSONObject(i);
            if (request.optBoolean("resetPools", false)) BehaviorFeedback.resetPools();
            observations.put(new JSONObject().put("result", BehaviorFeedback.evaluate(request))
                    .put("poolState", new JSONArray(BehaviorFeedback.poolState())));
        }
        BehaviorFeedback.resetPools();
        wire.println(observations);
    }
}
