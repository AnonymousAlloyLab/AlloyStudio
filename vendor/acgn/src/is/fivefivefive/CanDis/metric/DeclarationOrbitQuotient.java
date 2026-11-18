package is.fivefivefive.CanDis.metric;

import java.util.*;
import java.util.function.BiPredicate;

/** Remove redundant left actions only on complete, homogeneous matched classes. */
public final class DeclarationOrbitQuotient {
    private DeclarationOrbitQuotient() { }

    public static boolean[] fixedSources(int leftSize,int rightSize,
            Map<Integer,Integer> links,Set<Integer> paid,
            BiPredicate<Integer,Integer> leftExchange,BiPredicate<Integer,Integer> rightExchange) {
        boolean[] fixed=new boolean[leftSize];
        if(new HashSet<>(links.values()).size()!=links.size())
            throw new IllegalArgumentException("Noninjective declaration links");
        for(int x=0;x<leftSize;x++) {
            var group=new ArrayList<Integer>();
            for(int i=0;i<leftSize;i++)if(i==x || leftExchange.test(x,i))group.add(i);
            if(group.size()<2 || !links.keySet().containsAll(group))continue;
            var images=new HashSet<Integer>();boolean closed=true;
            for(int i:group) {
                int y=links.get(i);images.add(y);
                closed &= y>=0 && y<rightSize && paid.contains(i)==paid.contains(x);
                for(int j:group)closed &= i==j || leftExchange.test(i,j);
            }
            if(!closed)continue;
            for(int y:images)for(int j=0;j<rightSize;j++)
                closed &= images.contains(j)==(y==j || rightExchange.test(y,j));
            if(closed)for(int i:group)fixed[i]=true;
        }
        return fixed;
    }
}
