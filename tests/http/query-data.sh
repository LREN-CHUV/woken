#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         variables:='[]' \
         grouping:='[]' \
         covariables:='[{"code":"\"cognitive_task2\""},{"code":"\"score_test1\""}]' \
         filters:='""' \
         algorithm:='{"code":"data", "name": "data", "parameters": []}'
