#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         user:='{"code":"user1"}' \
         variables:='[{"code":"stress_before_test1"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         covariablesMustExist:=true \
         targetTable:='{"name":"sample_data"}' \
         algorithm:='{"code":"statisticsSummary", "name": "Statistics Summary", "parameters": []}'
