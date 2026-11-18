package is.fivefivefive.CanDis;

import java.util.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.DeclarationReplay.Move;

/** Selected declaration links must cover actual aligned-phase reads. */
final class LiveReadMapTest {
    static void run(NormalForm form) throws Exception {
        var declarations=Class.forName("is.fivefivefive.CanDis.core.CanonicalDistance$ImportDeclarations")
                .getDeclaredConstructor(List.class,List.class,List.class);
        var support=Class.forName("is.fivefivefive.CanDis.core.CanonicalDistance$PhaseReadSupport")
                .getDeclaredConstructor(Set.class,Set.class);
        var matches=CanonicalDistance.class.getDeclaredMethod("declarationMapsMatch",List.class,List.class,List.class);
        declarations.setAccessible(true);support.setAccessible(true);matches.setAccessible(true);
        var slots=form.getMatrixQuantiVars().subList(0,2);
        String a=slots.get(0).getName(),b=slots.get(1).getName();
        var paired=List.of(declarations.newInstance(slots,slots,List.of(new Move(0,0),new Move(1,1))));
        var oneSided=List.of(declarations.newInstance(slots,slots,List.of(new Move(0,0),new Move(1,-1),new Move(-1,1))));
        var liveSource=support.newInstance(Set.of(a,b),Set.of(a));
        var liveTarget=support.newInstance(Set.of(a),Set.of(a,b));
        var unread=support.newInstance(Set.of(a),Set.of(a));
        var complete=Map.of(a,a,b,b);
        int checks=0;
        for(Object live:List.of(liveSource,liveTarget)) {
            check(!(boolean)matches.invoke(null,paired,List.of(Map.of(a,a)),List.of(live)),"missing live link");checks++;
            check((boolean)matches.invoke(null,paired,List.of(complete),List.of(live)),"exact live link");checks++;
            check(!(boolean)matches.invoke(null,paired,List.of(Map.of(a,a,b,a)),List.of(live)),"substituted target");checks++;
        }
        check((boolean)matches.invoke(null,paired,List.of(Map.of(a,a)),List.of(unread)),"unread keep needs no map");checks++;
        check((boolean)matches.invoke(null,paired,List.of(Map.of(a,a,"local-left","local-right")),List.of(unread)),"residual locals retained");checks++;
        check(!(boolean)matches.invoke(null,paired,List.of(Map.of(a,a,"local-left",b)),List.of(unread)),"foreign source cannot use declared target");checks++;
        check(!(boolean)matches.invoke(null,oneSided,List.of(complete),List.of(liveSource)),"deleted slot cannot be resurrected");checks++;
        check(!(boolean)matches.invoke(null,oneSided,List.of(Map.of(a,a,"local-left",b)),List.of(unread)),"inserted slot cannot be reused");checks++;
        check((boolean)matches.invoke(null,oneSided,List.of(Map.of(a,a)),List.of(liveSource)),"one-sided reads remain edit obligations");checks++;
        check(!(boolean)matches.invoke(null,paired,List.of(complete,Map.of(a,a)),List.of(unread,liveSource)),"earlier map cannot cover later phase");checks++;
        check((boolean)matches.invoke(null,paired,List.of(Map.of(a,a),complete),List.of(unread,liveSource)),"per-phase coverage");checks++;
        check((boolean)matches.invoke(null,paired,List.of(Map.of(a,a),Map.of()),List.of(unread)),"unaligned suffix is not paired coverage");checks++;
        check(!(boolean)matches.invoke(null,paired,List.of(complete),List.of(unread,liveSource)),"missing aligned phase");checks++;
        System.out.println("LiveReadMapTest: "+checks+" checks passed");
    }

    private static void check(boolean condition,String reason) {
        if(!condition)throw new AssertionError(reason);
    }
}
