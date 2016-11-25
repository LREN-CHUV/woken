#!/bin/bash

id=$1

if [ -z "$id" ]; then
  id=$(date +'%s')
fi

http -v --timeout 180 POST localhost:8087/job \
         jobId="$id" \
         dockerImage="registry.federation.mip.hbp/mip_node/r-summary-stats:latest" \
         inputDb=ldsm \
         outputDb=analytics \
         nodes:='[]' \
         parameters:='{"PARAM_query":"select cognitive_task2 from linreg_sample order by practice_task2", "PARAM_colnames":"cognitive_task2"}'
