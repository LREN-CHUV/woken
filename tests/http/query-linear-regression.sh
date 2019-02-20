#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         user:='{"code":"user1"}' \
         variables:='[{"code":"cognitive_task2"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         covariablesMustExist:=true \
         targetTable:='{"database": "features", "dbSchema": "public", "name":"sample_data"}' \
         algorithm:='{"code":"linearRegression", "name": "linearRegression", "parameters": []}'
