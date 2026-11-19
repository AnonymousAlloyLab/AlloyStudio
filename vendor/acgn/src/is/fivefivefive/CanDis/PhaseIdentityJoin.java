package is.fivefivefive.CanDis;

import java.util.*;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.metric.ReplayValidationException;

/** Presentations of frozen source objects; no label-based matching. */
final class PhaseIdentityJoin {
    private final int[] fast,certified,originalToFast,originalToCertified;
    PhaseIdentityJoin(List<NormalForm> originals,List<NormalForm> fastSources,List<NormalForm> projectedSources) {
        this(originals,fastSources,projectedSources,false);
    }
    static PhaseIdentityJoin live(List<NormalForm> originals,List<NormalForm> fastSources,List<NormalForm> projectedSources) {
        return new PhaseIdentityJoin(originals,fastSources,projectedSources,true);
    }
    private PhaseIdentityJoin(List<NormalForm> originals,List<NormalForm> fastSources,List<NormalForm> projectedSources,boolean live) {
        var identities=new IdentityHashMap<NormalForm,Integer>();
        for(int i=0;i<originals.size();i++) {
            var source=originals.get(i);
            if(source==null||!source.isFrozenForCertification()||identities.put(source,i)!=null)
                throw new ReplayValidationException("Missing, mutable or duplicate original phase object");
        }
        fast=join(identities,fastSources,live);certified=join(identities,projectedSources,live);
        originalToFast=inverse(fast,originals.size());originalToCertified=inverse(certified,originals.size());
        for(int i=0;i<originals.size();i++)
            if((originalToFast[i]>=0)!=(originalToCertified[i]>=0))
                throw new ReplayValidationException("Unequal live phase identity coverage");
        if(live)for(var root:NormalForm.originalPhaseRoots(originals))
            if(originalToFast[identities.get(root)]<0)throw new ReplayValidationException("Missing original phase root");
    }
    private static int[] join(IdentityHashMap<NormalForm,Integer> originals,List<NormalForm> view,boolean live) {
        if(!live&&view.size()!=originals.size())throw new ReplayValidationException("Incomplete phase identity coverage");
        int[] result=new int[view.size()];var seen=new boolean[originals.size()];
        for(int i=0;i<view.size();i++) {
            Integer at=originals.get(view.get(i));
            if(at==null||seen[at])throw new ReplayValidationException("Foreign or duplicate phase source object");
            seen[at]=true;result[i]=at;
        }
        return result;
    }
    private static int[] inverse(int[] forward,int size) {
        var back=new int[size];Arrays.fill(back,-1);for(int i=0;i<forward.length;i++)back[forward[i]]=i;
        for(int i=0;i<forward.length;i++)
            if(back[forward[i]]!=i)throw new ReplayValidationException("Phase inverse laws");
        for(int i=0;i<size;i++)if(back[i]>=0&&forward[back[i]]!=i)throw new ReplayValidationException("Phase inverse laws");
        return back;
    }
    int original(int phase,boolean projected) {
        int[] map=projected?certified:fast;
        if(phase<0||phase>=map.length)throw new ReplayValidationException("Foreign phase address");
        return map[phase];
    }
    private static int present(int[] map,int original) {
        if(map[original]<0)throw new ReplayValidationException("Absent live phase address");
        return map[original];
    }
    int fast(int certifiedPhase) {return present(originalToFast,original(certifiedPhase,true));}
    int certified(int fastPhase) {return present(originalToCertified,original(fastPhase,false));}
}
