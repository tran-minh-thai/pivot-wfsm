#!/usr/bin/env bash
# aggregate_scale.sh <dir> - print scale tables from scale.csv (CRLF-safe).
#   E1 memory vs N (+ ratio), E2 natural OOM point, E4 time vs N.
set -u
SRC="${1:-results/pivot/scale}"
F="$SRC/scale.csv"
[ -f "$F" ] || { echo "$F not found"; exit 1; }
D=$(mktemp -d); tr -d '\r' < "$F" > "$D/scale.csv"; F="$D/scale.csv"

echo; echo "==== E1: Peak memory vs N (peak heap MB, mean+-sd across seeds; status=ok only) ===="
printf "  %-8s %18s %18s %8s\n" "N" "pivot" "embedding-storing" "reduction"
# mean+sd per (N,method) over the ok rows
awk -F, 'NR>1 && $11=="ok"{k=$2"|"$3; s[k]+=$8; ss[k]+=$8*$8; n[k]++}
  END{for(k in s){split(k,a,"|"); N=a[1]; m=a[2]; mean=s[k]/n[k];
      v=(n[k]>1)?(ss[k]-s[k]*s[k]/n[k])/(n[k]-1):0; sd=(v>0)?sqrt(v):0;
      key=N"|"m; MEAN[key]=mean; SD[key]=sd}
    for(k in MEAN){split(k,a,"|"); Ns[a[1]]=1}
    nn=0; for(N in Ns) ord[nn++]=N+0;
    # sort N ascending
    for(i=0;i<nn;i++)for(j=i+1;j<nn;j++)if(ord[j]<ord[i]){t=ord[i];ord[i]=ord[j];ord[j]=t}
    for(i=0;i<nn;i++){N=ord[i]; pk=N"|pivot"; mg=N"|embed-min";
      pm=(pk in MEAN)?MEAN[pk]:-1; mm=(mg in MEAN)?MEAN[mg]:-1;
      r=(pm>0&&mm>0)?sprintf("%.2fx",mm/pm):"-";
      pstr=(pm>0)?sprintf("%.0f+-%.0f",pm,SD[pk]):"OOM/na";
      mstr=(mm>0)?sprintf("%.0f+-%.0f",mm,SD[mg]):"OOM/na";
      printf "  %-8s %18s %18s %8s\n", N, pstr, mstr, r}}' "$F"

echo; echo "==== E2: Status by N (count OOM/fail/ok for embedding-storing vs pivot) ===="
awk -F, 'NR>1{c[$2"|"$3"|"$11]++}
  END{for(k in c){split(k,a,"|"); printf "  N=%-8s %-14s %-5s : %d times\n",a[1],a[2],a[3],c[k]}}' "$F" | sort -t= -k2 -n

echo; echo "==== E2b: Smallest N where embedding-storing OOMs (does pivot still run?) ===="
awk -F, 'NR>1 && $3=="embed-min" && $11=="oom"{if(!(($2) in seen)){seen[$2]=1; print "  embedding-storing OOM from N="$2" (heap "$7")"}}' "$F" | sort -t= -k2 -n | head -1
awk -F, 'NR>1 && $3=="pivot" && $11=="ok"{last=$2; hp=$8}
  END{if(last!="") print "  pivot still completes up to N="last" with ~"hp"MB"}' "$F"

echo; echo "==== E4: pivot time vs N (mean_ms, mean across seeds; status=ok) ===="
awk -F, 'NR>1 && $3=="pivot" && $11=="ok"{s[$2]+=$9; n[$2]++}
  END{nn=0; for(N in s) ord[nn++]=N+0;
    for(i=0;i<nn;i++)for(j=i+1;j<nn;j++)if(ord[j]<ord[i]){t=ord[i];ord[i]=ord[j];ord[j]=t}
    for(i=0;i<nn;i++){N=ord[i]; printf "  N=%-8s %.0f ms\n", N, s[N]/n[N]}}' "$F"

rm -rf "$D"
