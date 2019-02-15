#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         user:='{"code":"user1"}' \
         variables:='[{"code":"cognitive_task2"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_math_course1"}]' \
         covariablesMustExist:=true \
         targetTable:='{"name":"sample_data"}' \
         algorithm:='{"code":"knn", "name": "KNN", "parameters": [{"code": "k", "value": "2"}]}'
