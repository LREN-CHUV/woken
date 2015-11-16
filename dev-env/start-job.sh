#!/bin/bash

http -v --timeout 180 PUT localhost:8087/job \
                                           jobId=$(date +'%s') \
                                           dockerImage="registry.federation.mip.hbp/mip_node/r-summary-stats:296242c" \
                                           inputDb=ldsm \
                                           outputDb=analytics \
                                           nodes:='[]' \
                                           parameters:='{"PARAM_query":"select tissue1_volume from brain_feature order by tissue1_volume", "PARAM_colnames":"tissue1_volume"}'
