#!/usr/bin/env python3
"""Run each synthetic benchmark in its own JVM; measure that process, not Gradle.
Requires Unix, Java 21 and the classpath prepared by the settlementBenchmark task.
"""
import argparse,json,os,platform,resource,shutil,subprocess,sys,time
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--output',default='build/settlement-benchmark/measured')
parser.add_argument('--classpath',default='build/settlement-benchmark/classpath.txt')
parser.add_argument('--worker',choices=['reference','greedy','quadratic','solver'])
args=parser.parse_args()
out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
if args.worker:
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java: raise SystemExit('Java 21 is required')
    start=time.perf_counter()
    with (out/(args.worker+'.log')).open('w') as log:
        subprocess.run([java,'-Xms16m','-Xmx96m','-cp',Path(args.classpath).read_text(),
            'ru.movereon.tennis.experiment.SettlementBenchmark',args.worker,str(out)],stdout=log,stderr=subprocess.STDOUT,check=True)
    usage=resource.getrusage(resource.RUSAGE_CHILDREN)
    rss=usage.ru_maxrss if platform.system()=='Darwin' else usage.ru_maxrss*1024
    metrics={'mode':args.worker,'wall_seconds':time.perf_counter()-start,'cpu_seconds':usage.ru_utime+usage.ru_stime,
             'peak_rss_bytes':rss,'os':platform.system(),'arch':platform.machine(),'heap_limit_mib':96}
    (out/(args.worker+'-resources.json')).write_text(json.dumps(metrics,indent=2)+'\n')
    print(json.dumps(metrics),flush=True)
else:
    for mode in ['reference','greedy','quadratic','solver']:
        subprocess.run([sys.executable,__file__,'--worker',mode,'--output',str(out),'--classpath',args.classpath],check=True)
