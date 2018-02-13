#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         user:='{"code":"user1"}' \
         variables:='[{"code":"brainstem"}]' \
         grouping:='[]' \
         covariables:='[{"code":"leftcaudate"}]' \
         datasets:='[{"code":"node1"},{"code":"node2"}]' \
         targetTable='sample_data' \
         algorithm:='{"code":"knn", "name": "KNN", "parameters": []}'
