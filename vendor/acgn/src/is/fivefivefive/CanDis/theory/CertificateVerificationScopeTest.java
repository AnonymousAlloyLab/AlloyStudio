package is.fivefivefive.CanDis.theory;

import java.util.concurrent.atomic.AtomicReference;

/** Memo lifetime, identity, negative evidence and resource-policy regression. */
public final class CertificateVerificationScopeTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static InputEquationCertificate leaf(int id) {
        return leaf(id,TypedSlotContext.empty());
    }
    private static InputEquationCertificate leaf(int id,TypedSlotContext context) {
        var endpoint=TypedCertificateEndpoint.eclassWitness(new TypedEClassInterface(
                EClassId.of(id),GraphType.BOOL,context));
        return new InputEquationCertificate(CertificateOrigin.inputEquation("memo","leaf",0),endpoint,endpoint);
    }
    private static void reject(Runnable r,String why) {
        try { r.run(); } catch(IllegalArgumentException|IllegalStateException expected) { checks++;return; }
        throw new AssertionError(why);
    }
    public static void main(String[] args)throws Exception {
        check(CertificateVerificationScope.current()==null,"no ambient memo");
        var proof=leaf(1);
        CertificateVerifier.verify(proof);
        check(CertificateVerificationScope.current()==null,"ordinary verifier stays unscoped");
        try(var scope=CertificateVerificationScope.open()) {
            var symmetric=EqualityCertificates.symmetric(proof);
            var shared=EqualityCertificates.transitive(proof,symmetric);
            CertificateVerifier.verify(shared);
            check(scope.verified().contains(proof)&&scope.verified().contains(shared),"successful premises retained");
            int size=scope.verified().size();
            CertificateVerifier.verify(shared);
            check(scope.verified().size()==size,"shared DAG reused");
            var equal=leaf(1);
            check(equal.equals(proof)&&equal!=proof,"structurally equal distinct proof");
            check(!scope.verified().contains(equal),"identity is authority, not structural equality");
            CertificateVerifier.verify(equal);
            check(scope.verified().contains(equal),"distinct proof verified separately");
            var slot=TypedSlot.source(GraphType.constructor("MemoA"),0);
            var typed=leaf(20,TypedSlotContext.singleton(slot));
            CertificateVerifier.verify(typed);
            var right=TypedEqualityCertificate.class.getDeclaredField("rightEndpoint");right.setAccessible(true);
            for(var other:java.util.List.of(
                    TypedSlot.source(GraphType.constructor("MemoB"),0),
                    TypedSlot.canonicalFree(slot.type(),0),TypedSlot.source(slot.type(),1))) {
                var fresh=leaf(20,TypedSlotContext.singleton(slot));
                var foreign=leaf(20,TypedSlotContext.singleton(other));
                right.set(fresh,foreign.rightEndpoint());
                reject(()->CertificateVerifier.verify(fresh),"changed type/alphabet/ordinal cannot reuse success");
                check(!scope.verified().contains(fresh)&&scope.visiting.isEmpty(),"typed failure not retained");
                CertificateVerifier.verify(foreign);
                check(scope.verified().contains(foreign),"separate valid typed context verified independently");
            }
            var invalid=leaf(2);
            var origin=InputEquationCertificate.class.getDeclaredField("origin");origin.setAccessible(true);
            var validOrigin=origin.get(invalid);
            origin.set(invalid,CertificateOrigin.containerLaw("bad","invalid",0));
            reject(()->CertificateVerifier.verify(invalid),"invalid origin rejected");
            reject(()->CertificateVerifier.verify(invalid),"failed proof never cached");
            check(!scope.verified().contains(invalid)&&scope.visiting.isEmpty(),"exception cleanup");
            origin.set(invalid,validOrigin);
            CertificateVerifier.verify(invalid);
            check(scope.verified().contains(invalid),"valid retry after failure");
            var cycle=EqualityCertificates.symmetric(leaf(3));
            var premises=TypedEqualityCertificate.class.getDeclaredField("premises");premises.setAccessible(true);
            var validPremises=premises.get(cycle);premises.set(cycle,java.util.List.of(cycle));
            reject(()->CertificateVerifier.verify(cycle),"cycle rejected before memo insertion");
            check(scope.visiting.isEmpty()&&!scope.verified().contains(cycle),"cycle failure cleanup");
            premises.set(cycle,validPremises);
            CertificateVerifier.verify(cycle);
            try(var nested=CertificateVerificationScope.open()) {
                check(nested.verified().isEmpty(),"nested invocation isolated");
                CertificateVerifier.verify(proof);
                check(nested.verified().size()==1,"nested invocation verifies independently");
            }
            check(CertificateVerificationScope.current()==scope,"outer ownership restored");
            var failure=new AtomicReference<Throwable>();
            var thread=new Thread(()->{
                try {
                    if(CertificateVerificationScope.current()!=null)throw new AssertionError("cross-thread memo");
                    try(var separate=CertificateVerificationScope.open()) {
                        CertificateVerifier.verify(proof);
                        if(separate.verified().size()!=1)throw new AssertionError("separate verification");
                    }
                } catch(Throwable t) { failure.set(t); }
            });
            thread.start();thread.join();check(failure.get()==null,"thread isolation");
            String prior=System.getProperty("acgn.maxGroupElements");
            try {
                System.setProperty("acgn.maxGroupElements","1");
                check(scope.verified().isEmpty(),"changed policy discards success memo");
                CertificateVerifier.verify(proof);
            } finally {
                if(prior==null)System.clearProperty("acgn.maxGroupElements");else System.setProperty("acgn.maxGroupElements",prior);
            }
            check(scope.verified().isEmpty(),"restored policy does not resurrect old entries");
            CertificateVerifier.verify(proof);
            for(int i=0;i<10000;i++)CertificateVerifier.verify(leaf(10+i));
            check(scope.verified().size()==10001&&scope.verified().contains(proof),
                    "completed proofs survive the former 8192-entry cliff");
            var largeInvalid=leaf(10020);origin.set(largeInvalid,CertificateOrigin.containerLaw("bad","invalid",0));
            reject(()->CertificateVerifier.verify(largeInvalid),"invalid proof after old cache limit");
            check(!scope.verified().contains(largeInvalid)&&scope.visiting.isEmpty(),"large memo retains only successes");
            reject(()->CertificateVerifier.requirePermittedUnionDerivation(
                    EqualityCertificates.reflexive(proof.leftEndpoint())),"wrapper admission not bypassed");
        }
        check(CertificateVerificationScope.current()==null,"scope released");
        try(var failed=CertificateVerificationScope.open()) { throw new IllegalStateException("test"); }
        catch(IllegalStateException expected) { check(CertificateVerificationScope.current()==null,"exception scope released"); }
        // Existing source-tampering checks must still run with successful proofs cached.
        try(var scope=CertificateVerificationScope.open()) { GuardedJoinChainRegressionTest.main(new String[0]); }
        System.out.println("CertificateVerificationScopeTest passed: "+checks+" checks");
    }
}
