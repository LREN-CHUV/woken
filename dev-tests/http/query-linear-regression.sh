#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST https://localhost:8087/mining/job \
         variables:='[{"code":"cognitive_task2"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         filters:='""' \
         algorithm:='{"code":"linearRegression", "name": "linearRegression", "parameters": []}'
