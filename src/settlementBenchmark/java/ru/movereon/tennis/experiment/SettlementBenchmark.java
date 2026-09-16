package ru.movereon.tennis.experiment;

import com.google.ortools.Loader;
import ru.movereon.tennis.core.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static ru.movereon.tennis.experiment.SettlementPrototype.*;

/** Reproducible synthetic measurements. Does not connect to Telegram, SQLite or a server. */
public final class SettlementBenchmark {
    static volatile long sink;
    static final long SEED=20260916L;
    record Case(String name,long[] balances) {}
    static long[] partition(long total,int count,Random r) {
        TreeSet<Long> cuts=new TreeSet<>();cuts.add(0L);cuts.add(total);
        while(cuts.size()<count+1)cuts.add(1+r.nextLong(total-1));
        long[] result=new long[count];long prev=0;int i=0;
        for(long c:cuts){if(c==0)continue;result[i++]=c-prev;prev=c;}return result;
    }
    static long[] balanced(int n,int senders,Random r,int scale) {
        long[] b=new long[n];long total=0;
        for(int i=senders;i<n;i++){b[i]=1+r.nextInt(100);total+=b[i];}
        // Enough whole units for every sender, including extreme 19:1 cases.
        if(total<senders){b[n-1]+=senders;total+=senders;}
        long[] d=partition(total,senders,r);for(int i=0;i<senders;i++)b[i]=-d[i];
        for(int i=0;i<n;i++)b[i]*=scale;return b;
    }
    static List<Case> cases() {
        List<Case> list=new ArrayList<>();
        for(int seed=0;seed<2;seed++) {
            Random r=new Random(SEED+seed);
            for(int d:new int[]{1,3,10,17,19})list.add(new Case("senders-"+d+"-seed-"+seed,balanced(20,d,r,seed==0?5:1)));
            long[] pairs=new long[20];for(int i=0;i<10;i++){pairs[i]=-(100+r.nextInt(900));pairs[i+10]=-pairs[i];}
            list.add(new Case("exact-pairs-"+seed,pairs));
        }
        return list;
    }
    static List<Edge> actual(Map<ParticipantId,Long> input) {
        // The benchmark measures the production function directly; conversion is outside timing.
        try {
            var rows=AccountingKt.suggestTransfers(input);var result=new ArrayList<Edge>();
            for(var row:rows) {
                String from=(String)Arrays.stream(row.getClass().getMethods()).filter(m->m.getName().startsWith("getFrom-")).findFirst().orElseThrow().invoke(row);
                String to=(String)Arrays.stream(row.getClass().getMethods()).filter(m->m.getName().startsWith("getTo-")).findFirst().orElseThrow().invoke(row);
                result.add(new Edge(Integer.parseInt(from),Integer.parseInt(to),row.getAmount()));
            }return result;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static long baseline(Map<ParticipantId,Long> input,int repetitions) {
        long sum=0,start=System.nanoTime();
        for(int i=0;i<repetitions;i++){var rows=AccountingKt.suggestTransfers(input);sum+=rows.size();for(var t:rows)sum+=t.getAmount();}
        long ns=System.nanoTime()-start;sink=sum;return ns;
    }
    static long fastTime(Map<ParticipantId,Long> input,int repetitions) {
        long sum=0,start=System.nanoTime();
        for(int i=0;i<repetitions;i++){var rows=BenchmarkAdapter.custom(input);sum+=rows.size();for(var t:rows)sum+=t.getAmount();}
        long ns=System.nanoTime()-start;sink=sum;return ns;
    }
    static Map<Integer,Integer> exhaustive(long[] b) {
        int[] from=java.util.stream.IntStream.range(0,b.length).filter(i->b[i]<0).toArray();
        int[] to=java.util.stream.IntStream.range(0,b.length).filter(i->b[i]>0).toArray();
        Map<Integer,Integer> best=new HashMap<>();
        if(from.length==0){best.put(0,0);return best;}
        long[] credits=Arrays.stream(to).mapToLong(i->b[i]).toArray();
        enumerate(b,from,credits,0,0,-b[from[0]],0,0,0,best);return best;
    }
    static void enumerate(long[] b,int[] from,long[] credits,int row,int col,long remaining,int degree,int edges,int maximum,Map<Integer,Integer> best) {
        if(col==credits.length) {
            if(remaining!=0)return;
            if(row+1==from.length){if(Arrays.stream(credits).allMatch(x->x==0))best.merge(edges,Math.max(maximum,degree),Math::min);return;}
            enumerate(b,from,credits,row+1,0,-b[from[row+1]],0,edges,Math.max(maximum,degree),best);return;
        }
        long cap=Math.min(remaining,credits[col]);long begin=col==credits.length-1?remaining:0;
        for(long x=begin;x<=cap;x++) {credits[col]-=x;enumerate(b,from,credits,row,col+1,remaining-x,degree+(x>0?1:0),edges+(x>0?1:0),maximum,best);credits[col]+=x;}
    }
    static void checks() {
        Random r=new Random(SEED);int checked=0;
        for(int k=0;k<50;k++) {
            int n=4+k%4,d=1+r.nextInt(n-1);long[] b=new long[n];
            long total=Math.max(n,8);long[] debt=partition(total,d,r),credit=partition(total,n-d,r);
            for(int i=0;i<d;i++)b[i]=-debt[i];for(int i=d;i<n;i++)b[i]=credit[i-d];
            var reference=exhaustive(b);int min=Collections.min(reference.keySet());
            int degree=reference.entrySet().stream().filter(e->e.getKey()<=min+1).mapToInt(Map.Entry::getValue).min().orElseThrow();
            int count=reference.entrySet().stream().filter(e->e.getKey()<=min+1 && e.getValue()==degree).mapToInt(Map.Entry::getKey).min().orElseThrow();
            var quick=fast(b);verify(b,quick);if(quick.size()>greedy(b,0,0).size()+1)throw new AssertionError("Heuristic transfer budget");
            if(!quick.equals(fast(b)))throw new AssertionError("Non-deterministic heuristic");
            var result=solve(b,2,true);verify(b,result.edges());
            if(result.minimum()!=min || !result.fairnessProven() || result.edges().size()!=count || outgoing(result.edges(),n)!=degree)throw new AssertionError("Oracle mismatch "+Arrays.toString(b)+" "+result+" "+reference);
            checked++;
        }
        long[] tradeoff={-12,-4,-13,2,3,7,17};var tradeOracle=exhaustive(tradeoff);var trade=solve(tradeoff,2,true);
        verify(tradeoff,trade.edges());if(Collections.min(tradeOracle.keySet())!=5 || tradeOracle.get(5)!=3 || trade.minimum()!=5 || trade.edges().size()!=6 || outgoing(trade.edges(),7)!=2)throw new AssertionError("One extra payment compromise");
        System.out.println("TRADEOFF CHECK: minimum 5 payments / max 3 outgoing; chosen 6 payments / max 2 outgoing");
        long[] edge={-6,1,1,1,1,1,1};var forced=solve(edge,2,true);if(outgoing(forced.edges(),7)!=6)throw new AssertionError("Impossible cap");
        var zero=solve(new long[20],2,true);if(!zero.edges().isEmpty())throw new AssertionError();
        for(long[] invalid:new long[][]{{1,-2},{Long.MIN_VALUE,Long.MAX_VALUE,1},new long[21]}) {
            try {solve(invalid,2,true);throw new AssertionError("Input accepted");}catch(IllegalArgumentException expected){}
        }
        // Previously discussed 5-person case: largest-first produces four payments, optimum is three.
        long[] counter={800,700,-700,-500,-300};if(greedy(counter,0,0).size()!=4||solve(counter,2,true).minimum()!=3)throw new AssertionError("Greedy counterexample");
        // Pending transfer reservation is balanced before either algorithm sees the input.
        long[] pending={1300,700,400,-900,-650,-550,-300};pending[0]-=900;pending[3]+=900;
        var adjusted=solve(pending,2,true);verify(pending,adjusted.edges());if(adjusted.edges().stream().anyMatch(e->e.from()==3))throw new AssertionError("Reserved payment suggested again");
        System.out.println("CHECKS OK: "+checked+" independent exhaustive oracle cases; explicit extra-payment fixture; forced degree, zeros, input limits, greedy counterexample, reservation");
    }
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);String mode=args.length>0?args[0]:"all";Path dir=Path.of(args.length>1?args[1]:"build/settlement-benchmark");Files.createDirectories(dir);
        if(!Set.of("all","checks","reference","greedy","quadratic","solver").contains(mode))throw new IllegalArgumentException("Unknown benchmark mode");
        if(mode.equals("checks")||mode.equals("all")||mode.equals("solver")) {
            long start=System.nanoTime();Loader.loadNativeLibraries();System.out.printf("Native load %.3f ms%n",(System.nanoTime()-start)/1e6);
        }
        if(mode.equals("checks")){checks();return;}
        if(mode.equals("all"))checks();
        var cases=cases();
        if(mode.equals("reference") || mode.equals("all")) {
            try(var ref=Files.newBufferedWriter(dir.resolve("reference.csv"))) {
                ref.write("case,min_edges\n");
                for(var c:cases){var exact=minimum(c.balances(),candidates(c.balances()).stream().min(Comparator.comparingInt(List::size)).orElseThrow());verify(c.balances(),exact.plan());ref.write(c.name()+","+exact.count()+"\n");}
            }
            if(mode.equals("reference")){System.out.println("Exact reference complete");return;}
        }
        Map<String,Integer> reference=new HashMap<>();
        for(var line:Files.readAllLines(dir.resolve("reference.csv")).subList(1,cases.size()+1)){var cols=line.split(",");reference.put(cols[0],Integer.parseInt(cols[1]));}
        try(var input=Files.newBufferedWriter(dir.resolve("inputs.csv"))){input.write("case,balances\n");for(var c:cases)input.write(c.name()+",\""+Arrays.toString(c.balances())+"\"\n");}
        var modes=mode.equals("all")?List.of("greedy","quadratic","solver"):List.of(mode);
        for(String alg:modes) {
            // Separate untimed warm-up for production code, DP and the native solver.
            if(alg.equals("greedy"))for(var c:cases)baseline(input(c.balances()),2000);
            else if(alg.equals("quadratic"))for(var c:cases)fastTime(input(c.balances()),1000);
            else {for(int i=0;i<4;i++)solve(cases.get(2).balances(),2,false);if(alg.equals("solver"))for(int i=0;i<2;i++)solve(new long[]{13,7,4,-9,-6,-5,-4},2,true);}
            try(var out=Files.newBufferedWriter(dir.resolve(alg+".csv"))) {
                out.write("case,repeat,time_us,edges,max_out,min_edges,fairness_proven,status,minimum_ms,search_ms\n");
                for(var c:cases) {
                    var prepared=input(c.balances());var baselinePlan=actual(prepared);verify(c.balances(),baselinePlan);
                    if(!baselinePlan.equals(greedy(c.balances(),0,0)))throw new AssertionError("Different baseline tie-break");
                    int optimum=reference.get(c.name());
                    for(int repeat=0;repeat<3;repeat++) {
                        if(alg.equals("greedy")) {
                            double us=baseline(prepared,5000)/5000.0/1000;
                            out.write(String.format("%s,%d,%.3f,%d,%d,-1,false,BASELINE,0,0%n",c.name(),repeat,us,baselinePlan.size(),outgoing(baselinePlan,20)));
                        } else if(alg.equals("quadratic")) {
                            var result=fast(c.balances());verify(c.balances(),result);
                            double us=fastTime(prepared,1000)/1000.0/1000;
                            out.write(String.format("%s,%d,%.3f,%d,%d,%d,false,HEURISTIC,0,0%n",c.name(),repeat,us,result.size(),outgoing(result,20),optimum));
                        } else {
                            long start=System.nanoTime();var result=solve(c.balances(),2,alg.equals("solver"));double us=(System.nanoTime()-start)/1000.0;
                            verify(c.balances(),result.edges());if(result.edges().size()>result.minimum()+1)throw new AssertionError("Transfer budget");
                            out.write(String.format("%s,%d,%.3f,%d,%d,%d,%s,%s,%.3f,%.3f%n",c.name(),repeat,us,result.edges().size(),outgoing(result.edges(),20),result.minimum(),result.fairnessProven(),result.status(),result.minimumMs(),result.searchMs()));
                            sink=result.edges().size();
                        }
                        out.flush();
                    }
                    System.out.println(alg+" completed "+c.name());
                }
            }
        }
        System.out.println("Results: "+dir);
    }
}
