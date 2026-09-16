package ru.movereon.tennis.experiment;

import com.google.ortools.sat.*;
import ru.movereon.tennis.core.*;
import java.util.*;

/** Research only: at most 20 nonzero balances, each within one billion whole rubles. */
public final class SettlementPrototype {
    public record Edge(int from, int to, long amount) {}
    record Result(List<Edge> edges, int minimum, boolean fairnessProven, String status, double minimumMs, double searchMs) {}
    static Map<ParticipantId,Long> input(long[] b) {
        try {
            var box=ParticipantId.class.getMethod("box-impl",String.class);
            Map<ParticipantId,Long> result=new LinkedHashMap<>();
            for(int i=0;i<b.length;i++) result.put((ParticipantId)box.invoke(null,String.format(Locale.ROOT,"%02d",i)),b[i]);
            return result;
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static void validate(long[] b) {
        if(b.length>20 || Arrays.stream(b).anyMatch(x->x>1_000_000_000L||x< -1_000_000_000L)||Arrays.stream(b).sum()!=0)
            throw new IllegalArgumentException("Prototype accepts balanced arrays of up to 20 amounts, each within +/- 1e9");
    }
    static int outgoing(List<Edge> plan,int n) {
        int[] d=new int[n]; for(var e:plan)d[e.from()]++; return Arrays.stream(d).max().orElse(0);
    }
    static int compare(List<Edge> a,List<Edge> b,int n) {
        int k=Integer.compare(outgoing(a,n),outgoing(b,n));return k!=0?k:Integer.compare(a.size(),b.size());
    }
    static List<Edge> greedy(long[] b,int mode,int offset) {
        long[] rest=b.clone(); List<Edge> out=new ArrayList<>();
        // One O(n^2) pass, not a pair search after every payment.
        if(mode!=0)for(int i=0;i<b.length;i++)if(rest[i]<0)for(int j=0;j<b.length;j++)if(rest[j]==-rest[i]) {
            out.add(new Edge(i,j,rest[j]));rest[i]=0;rest[j]=0;break;
        }
        while(true) {
            int from=-1,to=-1;
            if(mode>=5) {
                for(int j=0;j<b.length;j++)if(rest[j]>0 && (to<0 || (mode==5?rest[j]>rest[to]:rest[j]<rest[to])))to=j;
                if(to>=0)for(int i=0;i<b.length;i++)if(rest[i]<0 && (from<0 || bestFit(-rest[i],-rest[from],rest[to])))from=i;
            }
            if(from<0)for(int step=0;step<b.length;step++) {
                int i=(step+offset)%b.length;
                if(rest[i]>=0)continue;
                if(from<0 || (mode==2?rest[i]>rest[from]:mode==3?false:rest[i]<rest[from]))from=i;
            }
            if(from<0)break;
            if(to<0)for(int j=0;j<b.length;j++)if(rest[j]>0) {
                if(to<0 || (mode==4 ? bestFit(rest[j],rest[to],-rest[from]) : rest[j]>rest[to]))to=j;
            }
            long amount=Math.min(-rest[from],rest[to]);out.add(new Edge(from,to,amount));rest[from]+=amount;rest[to]-=amount;
        }
        return out;
    }
    private static boolean bestFit(long x,long y,long need) {
        if(x>=need && y>=need)return x<y;
        if(x>=need)return true;if(y>=need)return false;return x>y;
    }
    static List<List<Edge>> candidates(long[] b) {
        var result=new ArrayList<List<Edge>>();result.add(greedy(b,0,0));
        int[] senders=java.util.stream.IntStream.range(0,b.length).filter(i->b[i]<0).toArray();
        for(int mode=1;mode<=6;mode++)for(int step=0;step<(mode==3?4:1);step++)
            result.add(greedy(b,mode,senders.length==0?0:senders[step*senders.length/4]));
        return result;
    }
    /** Ten fixed greedy variants: O(n^2) time, O(n) space. No exact minimum guarantee. */
    public static List<Edge> fast(long[] b) {
        var choices=candidates(b);int fewest=choices.stream().mapToInt(List::size).min().orElseThrow();
        return choices.stream().filter(p->p.size()<=fewest+1).min((a,c)->compare(a,c,b.length)).orElseThrow();
    }
    record Minimum(int count,List<Edge> plan) {}
    /** Exact zero-sum partition DP: O(n*2^n) time, O(2^n) memory, no solver. */
    static Minimum minimum(long[] original,List<Edge> seed) {
        int[] ids=java.util.stream.IntStream.range(0,original.length).filter(i->original[i]!=0).toArray();int n=ids.length;
        int senders=0;for(long x:original)if(x<0)senders++;
        if(seed.size()==Math.max(senders,n-senders))return new Minimum(seed.size(),seed);
        int states=1<<n;long[] sums=new long[states];byte[] groups=new byte[states];
        for(int mask=1;mask<states;mask++) {
            int bit=Integer.numberOfTrailingZeros(mask);sums[mask]=sums[mask&(mask-1)]+original[ids[bit]];
            int best=0;
            for(int left=mask;left!=0;left&=left-1)best=Math.max(best,groups[mask^(left&-left)]);
            groups[mask]=(byte)(best+(sums[mask]==0?1:0));
        }
        List<Edge> plan=new ArrayList<>();long[] part=new long[original.length];long sum=0;
        int mask=states-1;
        while(mask!=0) {
            int chosen=-1;
            for(int left=mask;left!=0;left&=left-1) {
                int bit=left&-left;
                if(groups[mask^bit]+(sums[mask]==0?1:0)==groups[mask]){chosen=bit;break;}
            }
            int id=ids[Integer.numberOfTrailingZeros(chosen)];part[id]=original[id];sum+=original[id];mask^=chosen;
            if(sum==0){plan.addAll(greedy(part,0,0));Arrays.fill(part,0);}
        }
        if(plan.size()!=n-groups[states-1])throw new AssertionError("Partition reconstruction");
        return new Minimum(plan.size(),plan);
    }
    /** One extra transfer is allowed only when it reduces the maximum outgoing degree. */
    static Result solve(long[] b,double solverLimitSeconds,boolean useSolver) {
        validate(b);long start=System.nanoTime();var choices=candidates(b);
        var seed=choices.stream().min(Comparator.comparingInt(List::size)).orElseThrow();
        var exact=minimum(b,seed);choices.add(exact.plan());
        List<Edge> best=exact.plan();
        for(var c:choices)if(c.size()<=exact.count()+1 && compare(c,best,b.length)<0)best=c;
        double dpMs=(System.nanoTime()-start)/1e6;
        int[] debt=java.util.stream.IntStream.range(0,b.length).filter(i->b[i]<0).toArray();
        int[] credit=java.util.stream.IntStream.range(0,b.length).filter(i->b[i]>0).toArray();
        if(debt.length==0)return new Result(best,0,true,"EMPTY",dpMs,0);
        int lower=(exact.count()+debt.length-1)/debt.length;
        long[] capacities=Arrays.stream(credit).mapToLong(i->b[i]).sorted().toArray();
        for(int i:debt){long remaining=-b[i];int needed=0;while(remaining>0)remaining-=capacities[capacities.length-1-needed++];lower=Math.max(lower,needed);}
        if(outgoing(best,b.length)==lower && best.size()==exact.count())return new Result(best,exact.count(),true,"BOUND",dpMs,0);
        if(!useSolver)return new Result(best,exact.count(),false,"HEURISTIC",dpMs,0);
        start=System.nanoTime();CpModel model=new CpModel();
        IntVar[][] amounts=new IntVar[debt.length][credit.length];BoolVar[][] edges=new BoolVar[debt.length][credit.length];
        var count=LinearExpr.newBuilder();IntVar maxOut=model.newIntVar(lower,outgoing(best,b.length),"maxOutgoing");
        for(int i=0;i<debt.length;i++) {
            var rowAmount=LinearExpr.newBuilder();var rowCount=LinearExpr.newBuilder();
            for(int j=0;j<credit.length;j++) {
                long cap=Math.min(-b[debt[i]],b[credit[j]]);
                amounts[i][j]=model.newIntVar(0,cap,"a"+i+"_"+j);edges[i][j]=model.newBoolVar("e"+i+"_"+j);
                model.addLessOrEqual(amounts[i][j],LinearExpr.term(edges[i][j],cap));model.addGreaterOrEqual(amounts[i][j],edges[i][j]);
                rowAmount.add(amounts[i][j]);rowCount.add(edges[i][j]);count.add(edges[i][j]);
                long hint=0;for(var e:best)if(e.from()==debt[i] && e.to()==credit[j])hint=e.amount();
                model.addHint(amounts[i][j],hint);model.addHint(edges[i][j],hint>0?1:0);
            }
            model.addEquality(rowAmount,-b[debt[i]]);model.addLessOrEqual(rowCount,maxOut);
        }
        for(int j=0;j<credit.length;j++){var col=LinearExpr.newBuilder();for(int i=0;i<debt.length;i++)col.add(amounts[i][j]);model.addEquality(col,b[credit[j]]);}
        model.addHint(maxOut,outgoing(best,b.length));model.addLessOrEqual(count,exact.count()+1);
        // The independent DP has already proved this bound; do not ask CP-SAT to prove it again.
        model.addGreaterOrEqual(count,exact.count());
        model.minimize(LinearExpr.newBuilder().addTerm(maxOut,exact.count()+2).add(count));
        CpSolver solver=new CpSolver();solver.getParameters().setNumSearchWorkers(1).setRandomSeed(42).setMaxTimeInSeconds(solverLimitSeconds);
        var status=solver.solve(model);
        if(status==CpSolverStatus.OPTIMAL || status==CpSolverStatus.FEASIBLE) {
            List<Edge> found=new ArrayList<>();for(int i=0;i<debt.length;i++)for(int j=0;j<credit.length;j++){long a=solver.value(amounts[i][j]);if(a>0)found.add(new Edge(debt[i],credit[j],a));}
            if(compare(found,best,b.length)<0)best=found;
        }
        if(status==CpSolverStatus.MODEL_INVALID || status==CpSolverStatus.INFEASIBLE)throw new AssertionError("Seed is feasible: "+status+" "+model.validate());
        return new Result(best,exact.count(),status==CpSolverStatus.OPTIMAL,status.toString(),dpMs,(System.nanoTime()-start)/1e6);
    }
    static void verify(long[] b,List<Edge> edges) {
        long[] residual=b.clone();Set<String> pairs=new HashSet<>();
        for(var e:edges){if(b[e.from()]>=0 || b[e.to()]<=0 || e.amount()<=0 || !pairs.add(e.from()+":"+e.to()))throw new AssertionError("Not a direct settlement");residual[e.from()]+=e.amount();residual[e.to()]-=e.amount();}
        if(Arrays.stream(residual).anyMatch(x->x!=0))throw new AssertionError("Unbalanced plan");
    }
}
