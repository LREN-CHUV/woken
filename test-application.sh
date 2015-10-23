#!/bin/sh

id=$1

if [ -z "$id" ]; then
  id=001
fi

http -v --timeout 180 PUT localhost:8087/job \
         requestId="$id" \
         dockerImage="registry.federation.mip.hbp/mip_node/r-box-stats:latest" \
         inputDb=ldsm \
         outputDb=analytics \
         parameters:='{"PARAM_query":"select tissue1_volume from brain_feature order by tissue1_volume", "PARAM_colnames":"tissue1_volume"}'
