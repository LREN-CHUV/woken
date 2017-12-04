#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST https://localhost:8087/mining/job \
         variables:='[{"code":"stress_before_test1"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         filters:='""' \
         algorithm:='{"code":"statisticsSummary", "name": "Statistics Summary", "parameters": []}'
